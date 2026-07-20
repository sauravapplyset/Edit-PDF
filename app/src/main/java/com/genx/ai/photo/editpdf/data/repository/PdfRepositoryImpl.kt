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
            val mergedBlocks = mergeTextBlocks(blocks)
            mergedBlocks.forEach { textBlockCache[it.id] = it }
            pageBlockOrder[pageIndex] = mergedBlocks.map { it.id }
            Result.success(mergedBlocks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mergeTextBlocks(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.isEmpty()) return emptyList()

        // 1. Sort blocks top-to-bottom, then left-to-right
        val sortedBlocks = blocks.sortedWith(compareBy({ it.baselineY }, { it.baselineX }))

        val lines = mutableListOf<MutableList<TextBlock>>()
        var currentLine = mutableListOf(sortedBlocks.first())

        // 2. Group into lines based on Y coordinate proximity
        for (i in 1 until sortedBlocks.size) {
            val block = sortedBlocks[i]
            val lastBlock = currentLine.last()
            
            // If the vertical difference is less than half the font size, consider it the same line
            val yDiff = java.lang.Math.abs(block.baselineY - lastBlock.baselineY)
            val xGap = block.boundingBox.left - lastBlock.boundingBox.right
            // Ensure xGap is not too large (e.g. gap across columns). Max gap 3x font size.
            if (yDiff < lastBlock.fontInfo.fontSize * 0.5f && xGap < lastBlock.fontInfo.fontSize * 3f) {
                currentLine.add(block)
            } else {
                lines.add(currentLine)
                currentLine = mutableListOf(block)
            }
        }
        lines.add(currentLine)

        // 3. Sort each line left-to-right (should already be mostly sorted, but just in case)
        lines.forEach { line -> line.sortBy { it.baselineX } }

        val paragraphs = mutableListOf<TextBlock>()
        var currentParagraphLines = mutableListOf(lines.first())

        // 4. Group lines into paragraphs
        for (i in 1 until lines.size) {
            val line = lines[i]
            val lastLine = currentParagraphLines.last()
            
            val lastLineFirstBlock = lastLine.first()
            val lineFirstBlock = line.first()
            
            val yDiff = java.lang.Math.abs(lineFirstBlock.baselineY - lastLineFirstBlock.baselineY)
            val expectedLineHeight = lastLineFirstBlock.fontInfo.fontSize * 1.8f // typical max line height
            
            // Check horizontal alignment to avoid merging columns
            val xAlignDiff = java.lang.Math.abs(lineFirstBlock.baselineX - lastLineFirstBlock.baselineX)
            val isAligned = xAlignDiff < lastLineFirstBlock.fontInfo.fontSize * 2.0f
            
            // Check if font, color matches, and it's physically close enough
            val isSameFont = lastLineFirstBlock.fontInfo.fontName == lineFirstBlock.fontInfo.fontName
            val isSameColor = lastLineFirstBlock.color == lineFirstBlock.color
            
            if (isSameFont && isSameColor && yDiff <= expectedLineHeight && isAligned) {
                currentParagraphLines.add(line)
            } else {
                paragraphs.add(mergeLinesToParagraph(currentParagraphLines))
                currentParagraphLines = mutableListOf(line)
            }
        }
        paragraphs.add(mergeLinesToParagraph(currentParagraphLines))

        return paragraphs
    }

    private fun mergeLinesToParagraph(lines: List<List<TextBlock>>): TextBlock {
        val firstBlock = lines.first().first()
        
        val mergedText = java.lang.StringBuilder()
        val mergedIndices = mutableListOf<Int>()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (i in lines.indices) {
            val line = lines[i]
            var lineText = ""
            for (j in line.indices) {
                val block = line[j]
                
                // Add space if there is a horizontal gap between blocks on the same line
                if (j > 0) {
                    val prevBlock = line[j - 1]
                    val gap = block.baselineX - (prevBlock.baselineX + (prevBlock.fontInfo.fontSize * prevBlock.text.length * 0.5f)) // rough estimate of width
                    if (gap > prevBlock.fontInfo.fontSize * 0.2f && !lineText.endsWith(" ")) {
                        lineText += " "
                    }
                }
                
                lineText += block.text
                mergedIndices.addAll(block.anchor.runIndices)
                
                minX = minOf(minX, block.boundingBox.left)
                minY = minOf(minY, block.boundingBox.top)
                maxX = maxOf(maxX, block.boundingBox.right)
                maxY = maxOf(maxY, block.boundingBox.bottom)
            }
            mergedText.append(lineText)
            if (i < lines.size - 1) {
                mergedText.append("\n")
            }
        }

        return firstBlock.copy(
            id = "merged_${firstBlock.id}",
            text = mergedText.toString(),
            boundingBox = com.genx.ai.photo.editpdf.domain.model.PdfRect(minX, minY, maxX, maxY),
            anchor = com.genx.ai.photo.editpdf.domain.model.PdfAnchor(mergedIndices, firstBlock.anchor.xobjectPath)
        )
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
