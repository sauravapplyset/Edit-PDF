package com.genx.ai.photo.editpdf.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.genx.ai.photo.editpdf.domain.model.PdfAnchor
import com.genx.ai.photo.editpdf.domain.model.PdfDocument
import com.genx.ai.photo.editpdf.domain.model.PdfPage
import com.genx.ai.photo.editpdf.domain.model.TextBlock
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// TODO: Support multiple simultaneously open documents (for compare / split-view feature)
// TODO: Add support for password-protected PDFs — show a password dialog before opening
@Singleton
class PdfBoxEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : PdfEngine {

    companion object {
        private const val TAG = "PdfBoxEngine"
    }

    private var activeDocument: PDDocument? = null
    private var activeUriString: String? = null
    private val textEditor = ContentStreamTextEditor()
    // TODO: Add bitmap cache (pageIndex -> Bitmap) to avoid re-rendering unchanged pages

    override suspend fun open(uriString: String): PdfDocument = withContext(Dispatchers.IO) {
        close() // Close any existing document
        try {
            val uri = Uri.parse(uriString)
            val document = context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input)
            } ?: throw IllegalStateException("Unable to open input stream for $uriString")
            activeDocument = document
            activeUriString = uriString

            val pages = (0 until document.numberOfPages).map { pageIndex -> pdfPageOf(document, pageIndex) }
            PdfDocument(uriString, document.numberOfPages, pages)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    override suspend fun getPageCount(): Int = withContext(Dispatchers.IO) {
        val document = activeDocument ?: throw IllegalStateException("No active document")
        document.numberOfPages
    }

    override suspend fun getPageDimensions(pageIndex: Int): PdfPage = withContext(Dispatchers.IO) {
        val document = activeDocument ?: throw IllegalStateException("No active document")
        pdfPageOf(document, pageIndex)
    }

    private fun pdfPageOf(document: PDDocument, pageIndex: Int): PdfPage {
        val visual = document.getPage(pageIndex).visualSize()
        return PdfPage(pageIndex = pageIndex, widthPt = visual.widthPt, heightPt = visual.heightPt)
    }

    // TODO: Add a Bitmap LRU cache here — return cached bitmap if page hasn't changed since last render
    // TODO: Consider adding night-mode / invert-color support as a rendering flag
    override suspend fun renderPage(pageIndex: Int, scale: Float): Bitmap = withContext(Dispatchers.IO) {
        val document = activeDocument ?: throw IllegalStateException("No active document")
        val renderer = PDFRenderer(document)
        renderer.renderImage(pageIndex, scale, ImageType.ARGB)
    }

    // TODO: Add support for extracting image blocks (not just text) so images can be replaced too
    override suspend fun extractTextBlocks(pageIndex: Int): List<TextBlock> = withContext(Dispatchers.IO) {
        val document = activeDocument ?: throw IllegalStateException("No active document")
        textEditor.extractTextRuns(document, pageIndex)
    }

    // anchor identifies the exact content-stream text-showing operator to mutate in place — no
    // search, no overlay; the operator's own font/color/position/transparency are left untouched
    // by ContentStreamTextEditor unless the new text needs a glyph the font doesn't have, in
    // which case only that run's font is swapped for a matching standard-14 substitute.
    override suspend fun replaceText(
        pageIndex: Int,
        newText: String,
        anchor: PdfAnchor
    ): Boolean = withContext(Dispatchers.IO) {
        val document = activeDocument ?: throw IllegalStateException("No active document")
        textEditor.replaceTextRun(document, pageIndex, anchor, newText)
    }

    // TODO: Add PDF compression option before saving (reduce file size for large PDFs)
    // TODO: Add progress callback for large PDF saves so UI can show a progress bar
    //
    // INCREMENTAL SAVE: only the changed/new objects (the mutated page content stream, any
    // substitute-font resource ContentStreamTextEditor added) are appended as a new revision,
    // exactly like the previous Apryse engine's e_incremental save mode. Untouched objects —
    // every other page, every font not touched by a substitution fallback, images, xref entries
    // — remain byte-for-byte identical to the source file, so repeated edits never drift and
    // other readers can still round-trip everything that wasn't touched.
    override suspend fun save(outputUriString: String): Unit = withContext(Dispatchers.IO) {
        val document = activeDocument ?: throw IllegalStateException("No active document")
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
        activeDocument?.close()
        activeDocument = null
        activeUriString = null
        Log.d(TAG, "Document closed")
    }
}
