package com.genx.ai.photo.editpdf.data.repository

import android.graphics.Bitmap
import com.genx.ai.photo.editpdf.data.pdf.PdfEngine
import com.genx.ai.photo.editpdf.domain.model.EditOperation
import com.genx.ai.photo.editpdf.domain.model.PdfDocument
import com.genx.ai.photo.editpdf.domain.model.TextBlock
import com.genx.ai.photo.editpdf.domain.repository.PdfRepository
import javax.inject.Inject
import javax.inject.Singleton

// TODO: Add a max undo stack size (e.g. 50 operations) to prevent memory growth
// TODO: Persist edit history to disk so edits survive app restarts
@Singleton
class PdfRepositoryImpl @Inject constructor(
    private val pdfEngine: PdfEngine
) : PdfRepository {

    // Current TextBlock per id. The block's `anchor` (original PDF-space position) is frozen
    // at first extraction and never overwritten — only `text` is updated in place on edit/undo/
    // redo. Re-deriving position from a fresh engine extraction after an edit is what caused
    // repeated edits to drift, since the engine would then be scanning its own previous output.
    private val textBlockCache = mutableMapOf<String, TextBlock>()

    // Stable per-page ordering captured on first extraction of that page.
    private val pageBlockOrder = mutableMapOf<Int, List<String>>()

    // Undo and Redo stacks
    private val undoStack = ArrayDeque<EditOperation.TextEdit>()
    private val redoStack = ArrayDeque<EditOperation.TextEdit>()

    override suspend fun openDocument(uriString: String): Result<PdfDocument> {
        return try {
            val doc = pdfEngine.open(uriString)
            textBlockCache.clear()
            pageBlockOrder.clear()
            undoStack.clear()
            redoStack.clear()
            Result.success(doc)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getPageCount(): Int {
        return pdfEngine.getPageCount()
    }

    override suspend fun renderPage(pageIndex: Int, scale: Float): Result<Bitmap> {
        return try {
            val bitmap = pdfEngine.renderPage(pageIndex, scale)
            Result.success(bitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Extracted from the engine only ONCE per page. Subsequent calls (e.g. refreshing the UI
    // after an edit) return the cached blocks — same ids, same frozen anchor, current text —
    // instead of re-scanning the mutated PDF and generating new ids/positions from it.
    override suspend fun extractTextBlocks(pageIndex: Int): Result<List<TextBlock>> {
        pageBlockOrder[pageIndex]?.let { order ->
            return Result.success(order.mapNotNull { textBlockCache[it] })
        }
        return try {
            val blocks = pdfEngine.extractTextBlocks(pageIndex)
            blocks.forEach { textBlockCache[it.id] = it }
            pageBlockOrder[pageIndex] = blocks.map { it.id }
            Result.success(blocks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // TODO: Support batch text edits in a single operation (one undo entry for multiple block changes)
    override suspend fun applyTextEdit(blockId: String, newText: String, pageIndex: Int): Result<Unit> {
        return try {
            val block = textBlockCache[blockId] ?: return Result.failure(
                IllegalArgumentException("TextBlock with ID $blockId not found in cache")
            )

            // Always write at the block's original anchor — never a position re-derived from
            // the document's current (possibly already-edited) state.
            val success = pdfEngine.replaceText(pageIndex, newText, block.anchor)
            if (success) {
                val editOp = EditOperation.TextEdit(
                    blockId = blockId,
                    originalText = block.text,
                    newText = newText,
                    pageIndex = pageIndex
                )
                undoStack.addLast(editOp)
                redoStack.clear() // Clear redo stack on new edit
                textBlockCache[blockId] = block.copy(text = newText)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to replace text in content stream"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun exportDocument(outputUriString: String): Result<Unit> {
        return try {
            pdfEngine.save(outputUriString)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun closeDocument() {
        pdfEngine.close()
        textBlockCache.clear()
        pageBlockOrder.clear()
        undoStack.clear()
        redoStack.clear()
    }

    override suspend fun undo(): Result<EditOperation?> {
        if (undoStack.isEmpty()) return Result.success(null)

        return try {
            val op = undoStack.removeLast()
            val block = textBlockCache[op.blockId] ?: return Result.failure(Exception("Block not found"))
            val success = pdfEngine.replaceText(op.pageIndex, op.originalText, block.anchor)
            if (success) {
                textBlockCache[op.blockId] = block.copy(text = op.originalText)
                redoStack.addLast(op)
                Result.success(op)
            } else {
                // Put it back if failed
                undoStack.addLast(op)
                Result.failure(Exception("Failed to undo text replacement in engine"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun redo(): Result<EditOperation?> {
        if (redoStack.isEmpty()) return Result.success(null)

        return try {
            val op = redoStack.removeLast()
            val block = textBlockCache[op.blockId] ?: return Result.failure(Exception("Block not found"))
            val success = pdfEngine.replaceText(op.pageIndex, op.newText, block.anchor)
            if (success) {
                textBlockCache[op.blockId] = block.copy(text = op.newText)
                undoStack.addLast(op)
                Result.success(op)
            } else {
                // Put it back if failed
                redoStack.addLast(op)
                Result.failure(Exception("Failed to redo text replacement in engine"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun canUndo(): Boolean = undoStack.isNotEmpty()

    override fun canRedo(): Boolean = redoStack.isNotEmpty()
}
