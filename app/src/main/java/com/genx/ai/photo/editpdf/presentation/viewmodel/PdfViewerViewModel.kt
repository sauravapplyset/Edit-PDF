package com.genx.ai.photo.editpdf.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genx.ai.photo.editpdf.domain.model.EditOperation
import com.genx.ai.photo.editpdf.domain.model.TextBlock
import com.genx.ai.photo.editpdf.domain.usecase.ApplyTextEditUseCase
import com.genx.ai.photo.editpdf.domain.usecase.ExportPdfUseCase
import com.genx.ai.photo.editpdf.domain.usecase.ExtractTextBlocksUseCase
import com.genx.ai.photo.editpdf.domain.usecase.OpenPdfUseCase
import com.genx.ai.photo.editpdf.domain.usecase.RedoEditUseCase
import com.genx.ai.photo.editpdf.domain.usecase.RenderPageUseCase
import com.genx.ai.photo.editpdf.domain.usecase.UndoEditUseCase
import com.genx.ai.photo.editpdf.presentation.state.PdfViewerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// TODO: Add SavedStateHandle support so the open PDF survives process death
// TODO: Add a search-in-PDF feature (find & replace across all pages)
@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    private val openPdfUseCase: OpenPdfUseCase,
    private val renderPageUseCase: RenderPageUseCase,
    private val extractTextBlocksUseCase: ExtractTextBlocksUseCase,
    private val applyTextEditUseCase: ApplyTextEditUseCase,
    private val exportPdfUseCase: ExportPdfUseCase,
    private val undoEditUseCase: UndoEditUseCase,
    private val redoEditUseCase: RedoEditUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PdfViewerState())
    val uiState: StateFlow<PdfViewerState> = _uiState.asStateFlow()

    // TODO: After opening, preload the next 1-2 pages in background for smoother navigation
    // TODO: Show file name in the toolbar after opening
    fun openPdf(uriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            openPdfUseCase(uriString)
                .onSuccess { doc ->
                    _uiState.update { 
                        it.copy(
                            pdfDocument = doc,
                            currentPageIndex = 0,
                            canUndo = false,
                            canRedo = false
                        )
                    }
                    loadPage(0)
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to open PDF: ${error.message}"
                        )
                    }
                }
        }
    }

    // TODO: Preload adjacent pages (pageIndex-1, pageIndex+1) in the background
    // TODO: Show per-page loading progress instead of full-screen spinner
    fun loadPage(pageIndex: Int) {
        val doc = _uiState.value.pdfDocument ?: return
        if (pageIndex < 0 || pageIndex >= doc.pageCount) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, currentPageIndex = pageIndex, errorMessage = null) }
            
            // Render bitmap and extract text blocks
            val renderResult = renderPageUseCase(pageIndex)
            val extractResult = extractTextBlocksUseCase(pageIndex)

            if (renderResult.isSuccess && extractResult.isSuccess) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        renderedBitmap = renderResult.getOrNull(),
                        textBlocks = extractResult.getOrDefault(emptyList()),
                        canUndo = undoEditUseCase.canUndo(),
                        canRedo = redoEditUseCase.canRedo()
                    )
                }
            } else {
                val errorMsg = renderResult.exceptionOrNull()?.message 
                    ?: extractResult.exceptionOrNull()?.message 
                    ?: "Unknown error loading page"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error loading page: $errorMsg"
                    )
                }
            }
        }
    }

    // TODO: Keep a selection history so user can jump back to previously edited blocks
    fun selectTextBlock(textBlock: TextBlock?) {
        _uiState.update { it.copy(selectedTextBlock = textBlock) }
    }

    fun expandSelection(newRect: com.genx.ai.photo.editpdf.domain.model.PdfRect) {
        val currentBlocks = _uiState.value.textBlocks
        val intersectingBlocks = currentBlocks.filter { it.boundingBox.intersects(newRect) }
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))

        if (intersectingBlocks.isEmpty()) return

        val mergedText = java.lang.StringBuilder()
        val mergedIndices = mutableListOf<Int>()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (j in intersectingBlocks.indices) {
            val block = intersectingBlocks[j]
            // If they are somewhat on the same line, use a space, otherwise newline
            if (j > 0) {
                val prev = intersectingBlocks[j - 1]
                if (java.lang.Math.abs(block.boundingBox.top - prev.boundingBox.top) > block.fontInfo.fontSize * 0.8f) {
                    mergedText.append("\n")
                } else {
                    mergedText.append(" ")
                }
            }
            mergedText.append(block.text)
            mergedIndices.addAll(block.anchor.runIndices)

            minX = minOf(minX, block.boundingBox.left)
            minY = minOf(minY, block.boundingBox.top)
            maxX = maxOf(maxX, block.boundingBox.right)
            maxY = maxOf(maxY, block.boundingBox.bottom)
        }

        mergedIndices.sort()
        val firstBlock = intersectingBlocks.first()

        val newBlock = firstBlock.copy(
            id = "merged_v_${System.currentTimeMillis()}",
            text = mergedText.toString().replace(" \n", "\n").replace("\n ", "\n"),
            boundingBox = com.genx.ai.photo.editpdf.domain.model.PdfRect(minX, minY, maxX, maxY),
            anchor = com.genx.ai.photo.editpdf.domain.model.PdfAnchor(mergedIndices, firstBlock.anchor.xobjectPath)
        )

        _uiState.update { it.copy(selectedTextBlock = newBlock) }
    }

    // TODO: Validate newText is not empty before applying; show inline error if empty
    // TODO: Support multi-line text editing (newlines in the edit dialog)
    fun confirmEdit(newText: String) {
        val selectedBlock = _uiState.value.selectedTextBlock ?: return
        val pageIndex = _uiState.value.currentPageIndex
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedTextBlock = null, errorMessage = null) }
            
            applyTextEditUseCase(selectedBlock.id, newText, pageIndex)
                .onSuccess {
                    // Reload current page to reflect changes
                    loadPage(pageIndex)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to edit text: ${error.message}"
                        )
                    }
                }
        }
    }

    fun undo() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            undoEditUseCase()
                .onSuccess { op ->
                    when (op) {
                        is EditOperation.TextEdit -> loadPage(op.pageIndex)
                        null -> _uiState.update { it.copy(isLoading = false) }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Undo failed: ${error.message}"
                        )
                    }
                }
        }
    }

    fun redo() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            redoEditUseCase()
                .onSuccess { op ->
                    when (op) {
                        is EditOperation.TextEdit -> loadPage(op.pageIndex)
                        null -> _uiState.update { it.copy(isLoading = false) }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Redo failed: ${error.message}"
                        )
                    }
                }
        }
    }

    // TODO: Add retry logic if export fails (e.g., storage permission denied mid-export)
    fun exportPdf(outputUriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            exportPdfUseCase(outputUriString)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Export failed: ${error.message}"
                        )
                    }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
