package com.genx.ai.photo.editpdf.data.pdf

import android.graphics.Bitmap
import com.genx.ai.photo.editpdf.domain.model.PdfAnchor
import com.genx.ai.photo.editpdf.domain.model.PdfDocument
import com.genx.ai.photo.editpdf.domain.model.PdfPage
import com.genx.ai.photo.editpdf.domain.model.TextBlock

// TODO: Add extractImageBlocks(pageIndex: Int) for image block detection
// TODO: Add addPage(), deletePage(), movePage() for page management
// TODO: Add addAnnotation() for highlighting / sticky note support
interface PdfEngine {
    suspend fun open(uriString: String): PdfDocument
    suspend fun getPageCount(): Int
    suspend fun getPageDimensions(pageIndex: Int): PdfPage
    suspend fun renderPage(pageIndex: Int, scale: Float): Bitmap
    suspend fun extractTextBlocks(pageIndex: Int): List<TextBlock>
    // anchor identifies the exact content-stream text element to mutate in place — no search,
    // no overlay; the element's own font/color/position/transparency are left untouched.
    suspend fun replaceText(pageIndex: Int, newText: String, anchor: PdfAnchor): Boolean
    suspend fun save(outputUriString: String)
    fun close()
}
