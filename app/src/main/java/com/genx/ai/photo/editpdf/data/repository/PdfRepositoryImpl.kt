package com.genx.ai.photo.editpdf.data.repository

import android.graphics.Bitmap
import com.genx.ai.photo.editpdf.data.pdf.PdfEngine
import com.genx.ai.photo.editpdf.domain.model.EditOperation
import com.genx.ai.photo.editpdf.domain.model.PdfDocument
import com.genx.ai.photo.editpdf.domain.model.PdfAnchor
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
            val mergedBlocks = mergeTextBlocks(blocks)
            mergedBlocks.forEach { textBlockCache[it.id] = it }
            pageBlockOrder[pageIndex] = mergedBlocks.map { it.id }
            Result.success(mergedBlocks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Each visual LINE becomes its own independently editable block — never merged with any
    // other line. Editing one line must only ever touch that line's own text-showing
    // operator(s); every other line, and every other block on the page, must stay byte-for-byte
    // untouched. (A previous version merged an entire xobjectPath's worth of lines into one
    // page-spanning "paragraph" block. Editing that block caused ContentStreamTextEditor to blank
    // out every original run except the first and reflow the whole page from the first run's
    // font/position — the root cause of full-page corruption on a single-word edit.)
    private fun mergeTextBlocks(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.isEmpty()) return emptyList()

        val groupedByPath = blocks.groupBy { it.anchor.xobjectPath }
        val finalBlocks = mutableListOf<TextBlock>()

        for ((_, pathBlocks) in groupedByPath) {
            val sortedBlocks = pathBlocks.sortedWith(compareBy({ it.baselineY }, { it.baselineX }))

            val lines = mutableListOf<MutableList<TextBlock>>()
            var currentLine = mutableListOf(sortedBlocks.first())

            for (i in 1 until sortedBlocks.size) {
                val block = sortedBlocks[i]
                val lastBlock = currentLine.last()

                val yDiff = java.lang.Math.abs(block.baselineY - lastBlock.baselineY)
                val xGap = block.boundingBox.left - lastBlock.boundingBox.right

                if (yDiff < lastBlock.fontInfo.fontSize * 0.8f && xGap < lastBlock.fontInfo.fontSize * 5f) {
                    currentLine.add(block)
                } else {
                    lines.add(currentLine)
                    currentLine = mutableListOf(block)
                }
            }
            lines.add(currentLine)
            lines.forEach { line ->
                line.sortBy { it.baselineX }
                finalBlocks.add(mergeLineBlocks(line))
            }
        }

        return finalBlocks
    }

    // Merges only the runs that make up ONE visual line (e.g. separate Tj/TJ runs for "Hello"
    // and "World" on the same baseline) into a single editable unit. Never combines runs from
    // different lines.
    private fun mergeLineBlocks(line: List<TextBlock>): TextBlock {
        val firstBlock = line.first()
        if (line.size == 1) return firstBlock

        val mergedText = java.lang.StringBuilder()
        val mergedIndices = mutableListOf<Int>()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (j in line.indices) {
            val block = line[j]

            // Add space if there is a horizontal gap between blocks on the same line
            if (j > 0) {
                val prevBlock = line[j - 1]
                val gap = block.baselineX - (prevBlock.baselineX + (prevBlock.fontInfo.fontSize * prevBlock.text.length * 0.5f)) // rough estimate of width
                if (gap > prevBlock.fontInfo.fontSize * 0.2f && !mergedText.endsWith(" ")) {
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

        return firstBlock.copy(
            id = "merged_${firstBlock.id}",
            text = mergedText.toString(),
            boundingBox = com.genx.ai.photo.editpdf.domain.model.PdfRect(minX, minY, maxX, maxY),
            anchor = PdfAnchor(mergedIndices, firstBlock.anchor.xobjectPath)
        )
    }

    // TODO: Support batch text edits in a single operation (one undo entry for multiple block changes)
    //
    // Never shifts any other block on the page. Each TextBlock now corresponds to exactly one
    // visual line (see mergeTextBlocks) written at its own original anchor, so an edit here can
    // only ever rewrite that line's own text-showing operator(s) — every other line and every
    // other block keeps its original position untouched.
    override suspend fun applyTextEdit(blockId: String, newText: String, pageIndex: Int): Result<Unit> {
        return try {
            val block = textBlockCache[blockId] ?: return Result.failure(
                IllegalArgumentException("TextBlock with ID $blockId not found in cache")
            )

            // Always write at the block's original anchor — never a position re-derived from
            // the document's current (possibly already-edited) state. No whiteout/overlay: the
            // engine mutates the original text-showing operator's own operand directly, which is
            // what makes the old glyphs stop being drawn.
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
