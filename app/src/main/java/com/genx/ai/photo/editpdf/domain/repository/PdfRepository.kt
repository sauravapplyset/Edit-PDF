package com.genx.ai.photo.editpdf.domain.repository

import android.graphics.Bitmap
import com.genx.ai.photo.editpdf.domain.model.EditOperation
import com.genx.ai.photo.editpdf.domain.model.PdfDocument
import com.genx.ai.photo.editpdf.domain.model.TextBlock

// TODO: Add addPage(afterIndex: Int) and deletePage(pageIndex: Int) operations
// TODO: Add reorderPages(fromIndex: Int, toIndex: Int) for drag-to-reorder
// TODO: Add replaceImage(pageIndex: Int, blockId: String, newImageUri: String) for image swap
interface PdfRepository {
    suspend fun openDocument(uriString: String): Result<PdfDocument>
    suspend fun getPageCount(): Int
    suspend fun renderPage(pageIndex: Int, scale: Float = 2.0f): Result<Bitmap>
    suspend fun extractTextBlocks(pageIndex: Int): Result<List<TextBlock>>
    suspend fun applyTextEdit(anchor: com.genx.ai.photo.editpdf.domain.model.PdfAnchor, newText: String, pageIndex: Int): Result<Unit>
    suspend fun exportDocument(outputUriString: String): Result<Unit>
    fun closeDocument()
    
    // Undo / Redo operations
    suspend fun undo(): Result<EditOperation?>
    suspend fun redo(): Result<EditOperation?>
    fun canUndo(): Boolean
    fun canRedo(): Boolean
}
