package com.genx.ai.photo.editpdf.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.genx.ai.photo.editpdf.domain.model.FontInfo
import com.genx.ai.photo.editpdf.domain.model.PdfAnchor
import com.genx.ai.photo.editpdf.domain.model.PdfDocument
import com.genx.ai.photo.editpdf.domain.model.PdfMatrix
import com.genx.ai.photo.editpdf.domain.model.PdfPage
import com.genx.ai.photo.editpdf.domain.model.PdfRect
import com.genx.ai.photo.editpdf.domain.model.TextBlock
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MuPdfEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : PdfEngine {

    companion object {
        private const val TAG = "MuPdfEngine"
    }

    private var muPdfDocument: Document? = null
    private var pdBoxDocument: PDDocument? = null
    private var activeUriString: String? = null
    
    private val textEditor = ContentStreamTextEditor()

    override suspend fun open(uriString: String): PdfDocument = withContext(Dispatchers.IO) {
        close()
        
        try {
            val uri = Uri.parse(uriString)
            
            // Open PDFBox Document
            context.contentResolver.openInputStream(uri)?.use { input ->
                pdBoxDocument = PDDocument.load(input)
            } ?: throw IllegalStateException("Unable to open input stream for $uriString")
            
            // Open MuPDF Document
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to read bytes for $uriString")
            muPdfDocument = Document.openDocument(bytes, "pdf")
            
            activeUriString = uriString
            
            val numPages = muPdfDocument!!.countPages()
            val pages = (0 until numPages).map { pageIndex -> pdfPageOf(pageIndex) }
            
            PdfDocument(uriString, numPages, pages)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    override suspend fun getPageCount(): Int = withContext(Dispatchers.IO) {
        muPdfDocument?.countPages() ?: throw IllegalStateException("No active document")
    }

    override suspend fun getPageDimensions(pageIndex: Int): PdfPage = withContext(Dispatchers.IO) {
        pdfPageOf(pageIndex)
    }

    private fun pdfPageOf(pageIndex: Int): PdfPage {
        val document = muPdfDocument ?: throw IllegalStateException("No active document")
        val page = document.loadPage(pageIndex)
        val bounds = page.bounds
        val width = bounds.x1 - bounds.x0
        val height = bounds.y1 - bounds.y0
        page.destroy()
        return PdfPage(pageIndex = pageIndex, widthPt = width, heightPt = height)
    }

    override suspend fun renderPage(pageIndex: Int, scale: Float): Bitmap = withContext(Dispatchers.IO) {
        val document = muPdfDocument ?: throw IllegalStateException("No active document")
        val page = document.loadPage(pageIndex)
        val bounds = page.bounds
        val width = ((bounds.x1 - bounds.x0) * scale).toInt()
        val height = ((bounds.y1 - bounds.y0) * scale).toInt()
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val matrix = Matrix(scale, scale)
        val device = AndroidDrawDevice(bitmap, bounds.x0.toInt(), bounds.y0.toInt())
        page.run(device, matrix, null)
        device.close()
        device.destroy()
        page.destroy()
        
        bitmap
    }

    override suspend fun extractTextBlocks(pageIndex: Int): List<TextBlock> = withContext(Dispatchers.IO) {
        val document = muPdfDocument ?: throw IllegalStateException("No active document")
        val pdDocument = pdBoxDocument ?: throw IllegalStateException("No active PDFBox document")
        
        // 1. Run PDFBox extraction to get anchors
        val pdBoxBlocks = textEditor.extractTextRuns(pdDocument, pageIndex)
        
        // 2. Run MuPDF extraction
        val page = document.loadPage(pageIndex)
        val stext = page.toStructuredText()
        val muPdfBlocks = mutableListOf<TextBlock>()
        
        // Match MuPDF lines with PDFBox runs
        var pdBoxIndex = 0
        
        val blocks = stext.blocks ?: emptyArray()
        for (block in blocks) {
            val lines = block.lines ?: continue
            for (line in lines) {
                var text = ""
                val chars = line.chars ?: emptyArray()
                for (char in chars) {
                    text += char.c.toChar()
                }
                text = text.trim()
                if (text.isEmpty()) continue
                
                // Construct bounding box
                val bbox = line.bbox
                val rect = PdfRect(
                    left = bbox.x0,
                    top = bbox.y0,
                    right = bbox.x1,
                    bottom = bbox.y1
                )
                
                // Match MuPDF lines to PDFBox runs using Spatial Mapping (Nearest Neighbor)
                // We cannot rely on string matching because PDFBox often extracts garbled text 
                // for CID/Type0 fonts.
                var anchor = PdfAnchor(emptyList())
                var bestMatch: com.genx.ai.photo.editpdf.domain.model.TextBlock? = null
                var minDistance = Float.MAX_VALUE
                
                val cx = (rect.left + rect.right) / 2
                val cy = (rect.top + rect.bottom) / 2
                
                // Start search across the ENTIRE page. MuPDF outputs in reading order,
                // but PDFBox outputs in raw content stream order, which can be completely random.
                for (pdBlock in pdBoxBlocks) {
                    val pcx = (pdBlock.boundingBox.left + pdBlock.boundingBox.right) / 2
                    val pcy = (pdBlock.boundingBox.top + pdBlock.boundingBox.bottom) / 2
                    
                    val dx = cx - pcx
                    val dy = cy - pcy
                    // Weight Y-distance heavily so it prefers blocks on the exact same line
                    val distSq = (dx * dx) + (dy * dy * 10)
                    
                    if (distSq < minDistance) {
                        minDistance = distSq
                        bestMatch = pdBlock
                    }
                }
                
                // We will always find a match as long as pdBoxBlocks is not empty
                var pdBoxBlock: com.genx.ai.photo.editpdf.domain.model.TextBlock? = null
                if (bestMatch != null) {
                    anchor = bestMatch.anchor
                    pdBoxBlock = bestMatch
                }
                
                muPdfBlocks.add(
                    TextBlock(
                        id = "mupdf_${pageIndex}_${muPdfBlocks.size}",
                        text = text,
                        boundingBox = rect,
                        fontInfo = pdBoxBlock?.fontInfo ?: FontInfo("Sans", 12f, false, false, "Sans", false, ""),
                        color = pdBoxBlock?.color ?: 0xFF000000.toInt(),
                        pageIndex = pageIndex,
                        anchor = anchor,
                        matrix = pdBoxBlock?.matrix ?: PdfMatrix(1f,0f,0f,1f,bbox.x0,bbox.y0),
                        baselineX = pdBoxBlock?.baselineX ?: bbox.x0,
                        baselineY = pdBoxBlock?.baselineY ?: bbox.y1,
                        charSpacing = pdBoxBlock?.charSpacing ?: 0f,
                        wordSpacing = pdBoxBlock?.wordSpacing ?: 0f,
                        horizontalScalingPercent = pdBoxBlock?.horizontalScalingPercent ?: 100f,
                        rise = pdBoxBlock?.rise ?: 0f,
                        renderMode = pdBoxBlock?.renderMode ?: 0,
                        alpha = pdBoxBlock?.alpha ?: 1f,
                        rotationDegrees = pdBoxBlock?.rotationDegrees ?: 0f
                    )
                )
            }
        }
        
        stext.destroy()
        page.destroy()
        
        muPdfBlocks
    }

    override suspend fun replaceText(
        pageIndex: Int,
        newText: String,
        anchor: PdfAnchor
    ): Boolean = withContext(Dispatchers.IO) {
        val document = pdBoxDocument ?: throw IllegalStateException("No active document")
        if (anchor.runIndices.isEmpty() || anchor.runIndex == -1) {
            throw IllegalStateException("Invalid anchor (runIndex = -1). Spatial mapping failed to find a matching block for this text.")
        }
        
        val replaced = textEditor.replaceTextRun(document, pageIndex, anchor, newText)
        
        if (replaced) {
            // Re-sync MuPDF document by saving PDFBox to byte array and re-opening
            val out = ByteArrayOutputStream()
            document.save(out) // Full save to memory
            val bytes = out.toByteArray()
            
            muPdfDocument?.destroy()
            muPdfDocument = Document.openDocument(bytes, "pdf")
        }
        
        replaced
    }

    override suspend fun save(outputUriString: String): Unit = withContext(Dispatchers.IO) {
        val document = pdBoxDocument ?: throw IllegalStateException("No active document")
        val tempFile = File.createTempFile("pdf_edit_temp", ".pdf", context.cacheDir)
        try {
            tempFile.outputStream().use { out -> document.saveIncremental(out) }
            Log.d(TAG, "PDF saved incrementally to ${tempFile.name}")

            val outputUri = Uri.parse(outputUriString)
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    override fun close() {
        muPdfDocument?.destroy()
        muPdfDocument = null
        
        pdBoxDocument?.close()
        pdBoxDocument = null
        
        activeUriString = null
        Log.d(TAG, "Document closed")
    }
}
