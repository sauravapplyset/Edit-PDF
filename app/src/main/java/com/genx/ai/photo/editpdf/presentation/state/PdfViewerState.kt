package com.genx.ai.photo.editpdf.presentation.state

import android.graphics.Bitmap
import com.genx.ai.photo.editpdf.domain.model.PdfDocument
import com.genx.ai.photo.editpdf.domain.model.TextBlock

// TODO: Add zoomLevel: Float and panOffset: Offset so zoom/pan state survives page changes
// TODO: Add searchQuery: String and searchResults: List<TextBlock> for find-in-PDF feature
// TODO: Add exportInProgress: Boolean to show export progress separately from isLoading
data class PdfViewerState(
    val isLoading: Boolean = false,
    val pdfDocument: PdfDocument? = null,
    val currentPageIndex: Int = 0,
    val renderedBitmap: Bitmap? = null,
    val textBlocks: List<TextBlock> = emptyList(),
    val selectedTextBlock: TextBlock? = null,
    val errorMessage: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)
