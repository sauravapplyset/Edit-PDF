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
            val sortedBlocks = pathBlocks.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))

            val deduplicatedBlocks = mutableListOf<TextBlock>()
            for (block in sortedBlocks) {
                val shadowOf = deduplicatedBlocks.find { 
                    it.text == block.text && 
                    java.lang.Math.abs(it.boundingBox.left - block.boundingBox.left) < it.fontInfo.fontSize &&
                    java.lang.Math.abs(it.boundingBox.top - block.boundingBox.top) < it.fontInfo.fontSize * 0.5f
                }
                if (shadowOf != null) {
                    val mergedIndices = (shadowOf.anchor.runIndices + block.anchor.runIndices).sorted()
                    val index = deduplicatedBlocks.indexOf(shadowOf)
                    deduplicatedBlocks[index] = shadowOf.copy(
                        anchor = shadowOf.anchor.copy(runIndices = mergedIndices)
                    )
                } else {
                    deduplicatedBlocks.add(block)
                }
            }

            val lines = mutableListOf<MutableList<TextBlock>>()
            if (deduplicatedBlocks.isEmpty()) continue
            var currentLine = mutableListOf(deduplicatedBlocks.first())

            for (i in 1 until deduplicatedBlocks.size) {
                val block = deduplicatedBlocks[i]
                val lastBlock = currentLine.last()

                val yDiff = java.lang.Math.abs(block.boundingBox.top - lastBlock.boundingBox.top)
                val xGap = block.boundingBox.left - lastBlock.boundingBox.right

                // Allow larger xGap to support justified text and table of contents dots, but prevent merging separate columns
                if (yDiff < lastBlock.fontInfo.fontSize * 0.8f && xGap < lastBlock.fontInfo.fontSize * 8.0f && xGap > -lastBlock.fontInfo.fontSize * 0.5f) {
                    currentLine.add(block)
                } else {
                    lines.add(currentLine)
                    currentLine = mutableListOf(block)
                }
            }
            lines.add(currentLine)
            
            val lineBlocks = mutableListOf<TextBlock>()
            lines.forEach { line ->
                line.sortBy { it.boundingBox.left }
                lineBlocks.add(mergeLineBlocks(line))
            }

            // Vertical grouping pass for cells
            val cellGroups = mutableListOf<MutableList<TextBlock>>()
            for (lineBlock in lineBlocks) {
                var addedToGroup = false
                for (group in cellGroups) {
                    val lastInGroup = group.last()
                    val verticalGap = lineBlock.boundingBox.top - lastInGroup.boundingBox.bottom
                    
                    // Also ensure they have similar font sizes and styles to prevent merging headings with paragraphs
                    val fontSizeRatio = lineBlock.fontInfo.fontSize / lastInGroup.fontInfo.fontSize
                    val hasSimilarFontSize = fontSizeRatio > 0.85f && fontSizeRatio < 1.15f
                    val hasSameStyle = lineBlock.fontInfo.isBold == lastInGroup.fontInfo.isBold && lineBlock.fontInfo.isItalic == lastInGroup.fontInfo.isItalic
                    if (hasSimilarFontSize && hasSameStyle && verticalGap > -lastInGroup.fontInfo.fontSize && verticalGap < lastInGroup.fontInfo.fontSize * 1.5f) {
                        val overlapX = maxOf(0f, minOf(lineBlock.boundingBox.right, lastInGroup.boundingBox.right) - maxOf(lineBlock.boundingBox.left, lastInGroup.boundingBox.left))
                        val minWidth = minOf(lineBlock.boundingBox.width, lastInGroup.boundingBox.width)
                        val leftDiff = java.lang.Math.abs(lineBlock.boundingBox.left - lastInGroup.boundingBox.left)
                        
                        // Merge if they significantly overlap horizontally OR are left-aligned
                        if (overlapX > minWidth * 0.5f || leftDiff < lastInGroup.fontInfo.fontSize * 2f) {
                            group.add(lineBlock)
                            addedToGroup = true
                            break
                        }
                    }
                }
                if (!addedToGroup) {
                    cellGroups.add(mutableListOf(lineBlock))
                }
            }

            cellGroups.forEach { group ->
                finalBlocks.add(mergeVerticalBlocks(group))
            }
        }

        return finalBlocks
    }

    private fun mergeVerticalBlocks(group: List<TextBlock>): TextBlock {
        val firstBlock = group.first()
        if (group.size == 1) return firstBlock

        val mergedText = java.lang.StringBuilder()
        val mergedIndices = mutableListOf<Int>()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (j in group.indices) {
            val block = group[j]
            if (j > 0) mergedText.append("\n")
            mergedText.append(block.text)
            mergedIndices.addAll(block.anchor.runIndices)

            minX = minOf(minX, block.boundingBox.left)
            minY = minOf(minY, block.boundingBox.top)
            maxX = maxOf(maxX, block.boundingBox.right)
            maxY = maxOf(maxY, block.boundingBox.bottom)
        }

        mergedIndices.sort()

        return firstBlock.copy(
            id = "merged_v_${firstBlock.id}",
            text = mergedText.toString(),
            boundingBox = com.genx.ai.photo.editpdf.domain.model.PdfRect(minX, minY, maxX, maxY),
            anchor = PdfAnchor(mergedIndices, firstBlock.anchor.xobjectPath)
        )
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
                val gap = block.boundingBox.left - (prevBlock.boundingBox.left + (prevBlock.fontInfo.fontSize * prevBlock.text.length * 0.5f)) // rough estimate of width
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
    override suspend fun applyTextEdit(anchor: com.genx.ai.photo.editpdf.domain.model.PdfAnchor, newText: String, pageIndex: Int): Result<Unit> {
        return try {
            // Always write at the block's original anchor — never a position re-derived from
            // the document's current (possibly already-edited) state. No whiteout/overlay: the
            // engine mutates the original text-showing operator's own operand directly, which is
            // what makes the old glyphs stop being drawn.
            val success = pdfEngine.replaceText(pageIndex, newText, anchor)
            if (success) {
                // Find all blocks in the cache that match this anchor and update them
                val affectedBlocks = textBlockCache.values.filter { it.anchor == anchor }
                
                val editOp = EditOperation.TextEdit(
                    blockId = affectedBlocks.firstOrNull()?.id ?: "unknown",
                    originalText = affectedBlocks.firstOrNull()?.text ?: "",
                    newText = newText,
                    pageIndex = pageIndex
                )
                undoStack.addLast(editOp)
                redoStack.clear() // Clear redo stack on new edit

                affectedBlocks.forEach { block ->
                    textBlockCache[block.id] = block.copy(text = newText)
                }

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
