package com.genx.ai.photo.editpdf.data.pdf

import android.util.Log
import com.genx.ai.photo.editpdf.domain.model.FontInfo
import com.genx.ai.photo.editpdf.domain.model.PdfAnchor
import com.genx.ai.photo.editpdf.domain.model.PdfMatrix
import com.genx.ai.photo.editpdf.domain.model.PdfRect
import com.genx.ai.photo.editpdf.domain.model.TextBlock
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * True in-place content-stream text editing on top of PDFBox — the PDFBox equivalent of what
 * ContentStreamTokenEditor did against Apryse's Element/ElementReader/ElementWriter API. Two
 * independent operations, both scoped to exactly one page:
 *
 *  - [extractTextRuns] walks the page with a [PDFStreamEngine] subclass that already tracks
 *    graphics state (CTM, text matrix, font, color, spacing...) for us, and emits one [TextBlock]
 *    per text-showing operator (Tj / ' / " / TJ).
 *  - [replaceTextRun] walks the RAW token list of the same content stream ([PDFStreamParser]),
 *    finds the Nth text-showing operator (same counting order as extraction), and swaps only that
 *    operator's operand bytes. Every other token — including the target run's own preceding
 *    Tf/Tm/color operators, and every other run on the page — passes through completely
 *    untouched, so font, size, color, position, and background are never affected. No whiteout
 *    rectangle, no overlay text: the original operator is mutated in place and the content stream
 *    is rewritten around it unchanged.
 *
 * Both passes agree on one rule: only Tj/'/"/TJ operators belonging to the page's OWN content
 * stream are counted — text drawn inside a Form XObject is not descended into (v1 scope, same
 * limitation the old Apryse-based engine documented). That shared rule is what keeps a
 * [PdfAnchor.runIndex] captured during extraction valid for a later [replaceTextRun] call no
 * matter how many edits have already been applied: neither pass ever inserts, removes, or
 * reorders a text-showing operator, so the same ordinal always refers to the same one.
 */
class ContentStreamTextEditor {

    companion object {
        private const val TAG = "ContentStreamTextEditor"

        // Used only when a font has no FontDescriptor (e.g. a bare standard-14 reference) —
        // approximate cap-height/descent for a typical Latin text font, in 1/1000 em.
        private const val DEFAULT_ASCENT = 800f
        private const val DEFAULT_DESCENT = -200f
    }

    fun extractTextRuns(document: PDDocument, pageIndex: Int): List<TextBlock> {
        val page = document.getPage(pageIndex)
        val collector = TextRunCollector(page, pageIndex)
        return try {
            collector.processPage(page)
            collector.runs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract text runs for page $pageIndex: ${e.message}", e)
            collector.runs
        }
    }

    fun replaceTextRun(document: PDDocument, pageIndex: Int, anchor: PdfAnchor, newText: String): Boolean {
        val page = document.getPage(pageIndex)
        
        var currentResources = page.resources ?: PDResources()
        var currentFormXObject: com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject? = null
        val modifiedObjects = mutableListOf<com.tom_roush.pdfbox.cos.COSUpdateInfo>()
        
        for (name in anchor.xobjectPath) {
            val xobj = currentResources.getXObject(com.tom_roush.pdfbox.cos.COSName.getPDFName(name))
            if (xobj is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject) {
                currentFormXObject = xobj
                currentResources = xobj.resources ?: PDResources()
                modifiedObjects.add(xobj.cosObject)
            } else {
                throw IllegalStateException("Invalid XObject path: $name is not a PDFormXObject (type: ${xobj?.javaClass?.simpleName})")
            }
        }

        val parser = if (currentFormXObject != null) {
            val cosStream = currentFormXObject.cosObject as com.tom_roush.pdfbox.cos.COSStream
            PDFStreamParser(com.tom_roush.pdfbox.pdmodel.common.PDStream(cosStream))
        } else {
            PDFStreamParser(page)
        }
        
        parser.parse()
        @Suppress("UNCHECKED_CAST")
        val tokens = parser.tokens as MutableList<Any?>

        var currentFont: PDFont? = null
        var currentFontResourceName: COSName? = null
        var currentFontSizeOperand: COSBase? = null
        var textShowingCount = 0
        var edited = false
        var resourcesTouched = false
        val operandIndices = mutableListOf<Int>()

        var i = 0
        outer@ while (i < tokens.size) {
            when (val token = tokens[i]) {
                is COSBase -> operandIndices.add(i)
                is Operator -> {
                    when (token.name) {
                        "Tf" -> {
                            currentFontResourceName = operandAt(tokens, operandIndices, 0) as? COSName
                            currentFontSizeOperand = operandAt(tokens, operandIndices, 1)
                            currentFont = currentFontResourceName?.let { name ->
                                runCatching { currentResources.getFont(name) }.getOrNull()
                            }
                        }
                        "Tj", "'", "\"" -> {
                            if (textShowingCount == anchor.runIndex) {
                                val operandIndex = operandIndices.lastOrNull()
                                if (operandIndex != null && tokens[operandIndex] is COSString) {
                                    val result = applySingleStringEdit(
                                        page, tokens, operandIndex,
                                        currentFont, currentFontResourceName, currentFontSizeOperand, newText
                                    )
                                    edited = result.edited
                                    resourcesTouched = result.resourcesTouched
                                } else {
                                    throw IllegalStateException("Found Tj at index ${anchor.runIndex} but operand is invalid. Operand index: $operandIndex, type: ${tokens.getOrNull(operandIndex ?: -1)?.javaClass?.simpleName}")
                                }
                                break@outer
                            }
                            textShowingCount++
                        }
                        "TJ" -> {
                            if (textShowingCount == anchor.runIndex) {
                                val operandIndex = operandIndices.lastOrNull()
                                if (operandIndex != null && tokens[operandIndex] is COSArray) {
                                    val result = applyArrayEdit(
                                        page, tokens, i, operandIndex,
                                        currentFont, currentFontResourceName, currentFontSizeOperand, newText
                                    )
                                    edited = result.edited
                                    resourcesTouched = result.resourcesTouched
                                } else {
                                    throw IllegalStateException("Found TJ at index ${anchor.runIndex} but operand is invalid. Operand index: $operandIndex, type: ${tokens.getOrNull(operandIndex ?: -1)?.javaClass?.simpleName}")
                                }
                                break@outer
                            }
                            textShowingCount++
                        }
                    }
                    operandIndices.clear()
                }
            }
            i++
        }

        if (!edited) {
            throw IllegalStateException("Failed to find text run at index ${anchor.runIndex}. Total text showing tokens found: $textShowingCount. XObject path: ${anchor.xobjectPath}")
        }

        val out = ByteArrayOutputStream()
        ContentStreamWriter(out).writeTokens(tokens)
        
        if (currentFormXObject != null) {
            val cosStream = currentFormXObject.cosObject as com.tom_roush.pdfbox.cos.COSStream
            cosStream.createOutputStream().use { it.write(out.toByteArray()) }
            cosStream.setNeedToBeUpdated(true)
            for (obj in modifiedObjects) {
                obj.setNeedToBeUpdated(true)
            }
            markDirtyForIncrementalSave(document, page, resourcesTouched)
        } else {
            page.setContents(PDStream(document, ByteArrayInputStream(out.toByteArray())))
            markDirtyForIncrementalSave(document, page, resourcesTouched)
        }
        
        return true
    }

    // PDFBox's incremental-save writer (COSWriter, invoked by PDDocument#saveIncremental) only
    // serializes objects reachable through an unbroken chain of COSUpdateInfo.setNeedToBeUpdated
    // (true), starting at the document Catalog — see PDDocument#saveIncremental's own javadoc
    // ("a path of objects... starting from the document catalog"). Skipping this makes the save
    // silently succeed while the new content stream ends up byte-present in the file but
    // unreferenced by anything a reader can reach — the edit would vanish on reopen. Mark every
    // dictionary on the path from the Catalog down to this page (walking /Parent handles any
    // page-tree depth), plus /Resources when a substitute font was added to it.
    private fun markDirtyForIncrementalSave(document: PDDocument, page: PDPage, resourcesTouched: Boolean) {
        page.getCOSObject().setNeedToBeUpdated(true)
        if (resourcesTouched) {
            page.resources?.getCOSObject()?.setNeedToBeUpdated(true)
        }
        var parent = page.getCOSObject().getDictionaryObject(COSName.PARENT) as? COSDictionary
        var guard = 0
        while (parent != null && guard < 64) {
            parent.setNeedToBeUpdated(true)
            parent = parent.getDictionaryObject(COSName.PARENT) as? COSDictionary
            guard++
        }
        document.documentCatalog.getCOSObject().setNeedToBeUpdated(true)
    }

    private fun operandAt(tokens: List<Any?>, operandIndices: List<Int>, position: Int): COSBase? {
        val idx = operandIndices.getOrNull(position) ?: return null
        return tokens[idx] as? COSBase
    }

    private data class EditResult(val edited: Boolean, val resourcesTouched: Boolean)

    private fun applySingleStringEdit(
        page: PDPage,
        tokens: MutableList<Any?>,
        operandIndex: Int,
        currentFont: PDFont?,
        currentFontResourceName: COSName?,
        currentFontSizeOperand: COSBase?,
        newText: String
    ): EditResult {
        val encoded = encodeWithFallback(currentFont, newText)
        tokens[operandIndex] = COSString(encoded.bytes)
        var resourcesTouched = false
        if (encoded.substituted) {
            spliceSubstituteFont(page, tokens, operandIndex, encoded.font, currentFontResourceName, currentFontSizeOperand)
            resourcesTouched = true
        }
        return EditResult(edited = true, resourcesTouched = resourcesTouched)
    }

    private fun applyArrayEdit(
        page: PDPage,
        tokens: MutableList<Any?>,
        operatorIndex: Int,
        operandIndex: Int,
        currentFont: PDFont?,
        currentFontResourceName: COSName?,
        currentFontSizeOperand: COSBase?,
        newText: String
    ): EditResult {
        val encoded = encodeWithFallback(currentFont, newText)
        
        val oldArray = tokens[operandIndex] as? com.tom_roush.pdfbox.cos.COSArray
        val newArray = com.tom_roush.pdfbox.cos.COSArray()
        
        // Preserve any numbers (kerning) that appear before the first string in the array.
        // PDF generators often use initial kerning to center or right-align text on a line.
        // Losing this offset shifts the text horizontally.
        if (oldArray != null) {
            for (i in 0 until oldArray.size()) {
                val item = oldArray.get(i)
                if (item is com.tom_roush.pdfbox.cos.COSNumber) {
                    newArray.add(item)
                } else {
                    break // Stop at the first string
                }
            }
        }
        
        newArray.add(COSString(encoded.bytes))
        
        tokens[operandIndex] = newArray
        // We keep the operator as TJ, do not change to Tj
        // tokens[operatorIndex] remains Operator.getOperator("TJ")
        
        var resourcesTouched = false
        if (encoded.substituted) {
            spliceSubstituteFont(page, tokens, operandIndex, encoded.font, currentFontResourceName, currentFontSizeOperand)
            resourcesTouched = true
        }
        return EditResult(edited = true, resourcesTouched = resourcesTouched)
    }

    private data class EncodedRun(val bytes: ByteArray, val font: PDFont, val substituted: Boolean)

    // Encodes newText with the run's ORIGINAL font whenever possible — the whole point of this
    // engine is that an edited run keeps its exact original font. Only when the embedded font's
    // glyph set genuinely cannot represent a character in the new text (font.encode throws
    // IllegalArgumentException — PDFBox's own signal for "unsupported by this font") do we fall
    // back to a close standard-14 font, per the confirmed product policy (same behavior Adobe
    // Acrobat itself falls back to). Position, size, and color are untouched either way.
    private fun encodeWithFallback(originalFont: PDFont?, text: String): EncodedRun {
        val font = originalFont ?: PDType1Font.HELVETICA
        return try {
            EncodedRun(font.encode(text), font, substituted = false)
        } catch (e: IllegalArgumentException) {
            val substitute = pickSubstituteFont(originalFont)
            Log.w(TAG, "Font '${runCatching { originalFont?.name }.getOrNull()}' has no glyph for the " +
                "edited text; substituting '${substitute.name}' for this run only")
            EncodedRun(substitute.encode(text), substitute, substituted = true)
        }
    }

    private fun pickSubstituteFont(original: PDFont?): PDType1Font {
        val descriptor = runCatching { original?.fontDescriptor }.getOrNull()
        val name = runCatching { original?.name }.getOrNull().orEmpty()
        val bold = descriptor?.isForceBold == true || name.contains("Bold", ignoreCase = true)
        val italic = descriptor?.isItalic == true ||
            name.contains("Italic", ignoreCase = true) || name.contains("Oblique", ignoreCase = true)
        val isMono = descriptor?.isFixedPitch == true ||
            name.contains("Courier", ignoreCase = true) || name.contains("Mono", ignoreCase = true)
        val isSerif = !isMono && (descriptor?.isSerif == true ||
            name.contains("Times", ignoreCase = true) || name.contains("Serif", ignoreCase = true) ||
            name.contains("Georgia", ignoreCase = true) || name.contains("Garamond", ignoreCase = true))
        return when {
            isMono -> when {
                bold && italic -> PDType1Font.COURIER_BOLD_OBLIQUE
                bold -> PDType1Font.COURIER_BOLD
                italic -> PDType1Font.COURIER_OBLIQUE
                else -> PDType1Font.COURIER
            }
            isSerif -> when {
                bold && italic -> PDType1Font.TIMES_BOLD_ITALIC
                bold -> PDType1Font.TIMES_BOLD
                italic -> PDType1Font.TIMES_ITALIC
                else -> PDType1Font.TIMES_ROMAN
            }
            else -> when {
                bold && italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
                bold -> PDType1Font.HELVETICA_BOLD
                italic -> PDType1Font.HELVETICA_OBLIQUE
                else -> PDType1Font.HELVETICA
            }
        }
    }

    // Brackets the edited run with an isolated font switch: [substitute Tf] [Tj] [original Tf].
    // This is the one documented exception to "never insert operators" — it only ever inserts
    // non-text-showing Tf tokens, so it never changes what any PdfAnchor.runIndex refers to
    // (those only ever count Tj/'/"/TJ), and restoring the original font immediately after this
    // run means any later text on the page that relied on the same inherited Tf is unaffected.
    private fun spliceSubstituteFont(
        page: PDPage,
        tokens: MutableList<Any?>,
        operandIndex: Int,
        substituteFont: PDFont,
        originalFontResourceName: COSName?,
        originalFontSizeOperand: COSBase?
    ) {
        val resources = page.resources ?: PDResources().also { page.resources = it }
        val substituteName = resources.add(substituteFont)
        val fontSizeOperand = originalFontSizeOperand ?: COSFloat(12f)
        val operatorIndex = operandIndex + 1

        // Insert the "restore original font" triple right after this run first (higher index),
        // then the "switch to substitute" triple right before it (lower index) — inserting
        // high-to-low means neither insertion has to account for shifts caused by the other.
        if (originalFontResourceName != null) {
            tokens.add(operatorIndex + 1, Operator.getOperator("Tf"))
            tokens.add(operatorIndex + 1, fontSizeOperand)
            tokens.add(operatorIndex + 1, originalFontResourceName)
        }
        tokens.add(operandIndex, Operator.getOperator("Tf"))
        tokens.add(operandIndex, fontSizeOperand)
        tokens.add(operandIndex, substituteName)
    }

    /**
     * Walks a page's content stream via [PDFStreamEngine], which already tracks graphics state
     * for us, and emits one [TextBlock] per text-showing operator. Runs inside a Form XObject
     * ([getLevel] > 0, PDFStreamEngine's own recursion-depth counter around the Do operator) are
     * skipped entirely — not extracted, not counted — so the ordinal assigned here always matches
     * what [replaceTextRun]'s raw, XObject-blind token walk would count for the same operator.
     */
    private class TextRunCollector(
        private val page: PDPage,
        private val pageIndex: Int
    ) : PDFStreamEngine() {

        val runs = mutableListOf<TextBlock>()
        private val runCounters = mutableMapOf<List<String>, Int>()
        private val xobjectStack = mutableListOf<String>()
        private val visual = page.visualSize()

        init {
            // Graphics state
            addOperator(com.tom_roush.pdfbox.contentstream.operator.state.Save())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.state.Restore())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.state.Concatenate())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters())
            
            // Text state
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.SetFontAndSize())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.BeginText())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.EndText())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.MoveText())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.MoveTextSetLeading())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.NextLine())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.SetCharSpacing())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.SetWordSpacing())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.SetTextHorizontalScaling())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.SetTextLeading())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.SetTextRenderingMode())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.SetTextRise())
            
            // Text showing
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.ShowText())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.ShowTextAdjusted())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLine())
            addOperator(com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLineAndSpace())
        }

        override fun processOperator(operator: com.tom_roush.pdfbox.contentstream.operator.Operator, operands: MutableList<com.tom_roush.pdfbox.cos.COSBase>?) {
            if (operator.name == "Do" && operands?.isNotEmpty() == true) {
                val name = (operands[0] as? com.tom_roush.pdfbox.cos.COSName)?.name
                if (name != null) {
                    xobjectStack.add(name)
                    val xobjName = com.tom_roush.pdfbox.cos.COSName.getPDFName(name)
                    val xobj = resources?.getXObject(xobjName)
                    if (xobj is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject) {
                        try {
                            showForm(xobj)
                        } catch (e: Exception) {
                            android.util.Log.e("PdfBox", "Failed to show form: ${e.message}", e)
                        }
                    } else {
                        android.util.Log.e("PdfBox", "XObject $name not found or not a form. resources=$resources")
                    }
                    xobjectStack.removeLast()
                    return // We handled it, don't call super
                }
            }
            super.processOperator(operator, operands)
        }

        override fun showTextString(string: ByteArray) {
            recordRun(string, null)
            super.showTextString(string)
        }

        override fun showTextStrings(array: COSArray) {
            recordRun(null, array)
            super.showTextStrings(array)
        }

        private fun recordRun(bytes: ByteArray?, array: com.tom_roush.pdfbox.cos.COSArray?) {
            val currentPath = xobjectStack.toList()
            val myOrdinal = runCounters.getOrDefault(currentPath, 0)
            runCounters[currentPath] = myOrdinal + 1
            try {
                val gs = graphicsState
                val textState = gs.textState
                val font = textState.font ?: return
                val text = if (bytes != null) decodeText(font, bytes) else decodeArrayText(font, array!!)
                // Do not return early if text is blank. PDFBox often fails to decode CID/Type0 fonts,
                // producing blank text. We must still record the run so MuPdfEngine can spatially map
                // its anchor index for replacement.

                val ctm = gs.currentTransformationMatrix
                val tm = textMatrix ?: Matrix()
                val runMatrix = tm.multiply(ctm)
                val pdfMatrix = PdfMatrix(
                    a = runMatrix.scaleX, b = runMatrix.shearY,
                    c = runMatrix.shearX, d = runMatrix.scaleY,
                    e = runMatrix.translateX, f = runMatrix.translateY
                )

                val fontSize = textState.fontSize
                val charSpacing = textState.characterSpacing
                val wordSpacing = textState.wordSpacing
                val hScale = textState.horizontalScaling
                val rise = textState.rise
                val renderMode = runCatching { textState.renderingMode.intValue() }.getOrDefault(0)

                val widthEm = runCatching { font.getStringWidth(text) / 1000f }.getOrDefault(text.length * 0.5f)
                val spaceCount = text.count { it == ' ' }
                val extraSpacing = charSpacing * text.length + wordSpacing * spaceCount
                val widthPt = (widthEm * fontSize + extraSpacing) * (hScale / 100f)

                val descriptor = runCatching { font.fontDescriptor }.getOrNull()
                val ascentEm = (descriptor?.ascent?.takeIf { it != 0f } ?: DEFAULT_ASCENT) / 1000f
                val descentEm = (descriptor?.descent?.takeIf { it != 0f } ?: DEFAULT_DESCENT) / 1000f

                // Run's axis-aligned bounding box: corners in text space -> run matrix -> visual
                // (rotation-adjusted) PDF space -> top-left-origin screen space.
                val corners = listOf(
                    0f to descentEm * fontSize,
                    widthPt to descentEm * fontSize,
                    0f to ascentEm * fontSize,
                    widthPt to ascentEm * fontSize
                ).map { (x, y) -> pdfMatrix.apply(x, y) }
                    .map { (x, y) -> page.toVisualPoint(x, y) }

                val minX = corners.minOf { it.first }
                val maxX = corners.maxOf { it.first }
                val minY = corners.minOf { it.second }
                val maxY = corners.maxOf { it.second }

                val rect = PdfRect(
                    left = minX,
                    top = visual.heightPt - maxY,
                    right = maxX,
                    bottom = visual.heightPt - minY
                )
                val baseline = page.toVisualPoint(pdfMatrix.e, pdfMatrix.f)

                var argbColor = 0xFF000000.toInt()
                runCatching {
                    val rgb = gs.nonStrokingColor?.toRGB() ?: 0
                    argbColor = (0xFF shl 24) or (rgb and 0x00FFFFFF)
                }
                val alpha = runCatching { gs.nonStrokeAlphaConstant.toFloat() }.getOrDefault(1f)

                val fontName = runCatching { font.name }.getOrNull() ?: "Sans-Serif"
                val descriptorBold = runCatching { descriptor?.isForceBold == true }.getOrDefault(false)
                val descriptorItalic = runCatching { descriptor?.isItalic == true }.getOrDefault(false)
                val isBold = descriptorBold || fontName.contains("Bold", ignoreCase = true)
                val isItalic = descriptorItalic ||
                    fontName.contains("Italic", ignoreCase = true) || fontName.contains("Oblique", ignoreCase = true)
                val isEmbedded = runCatching { font.isEmbedded }.getOrDefault(false)
                val subType = runCatching { font.subType }.getOrNull() ?: ""

                runs.add(
                    TextBlock(
                        id = "block_${pageIndex}_$myOrdinal",
                        text = text,
                        boundingBox = rect,
                        fontInfo = FontInfo(
                            fontName = fontName,
                            fontSize = fontSize,
                            isBold = isBold,
                            isItalic = isItalic,
                            baseFont = fontName,
                            isEmbedded = isEmbedded,
                            subType = subType
                        ),
                        color = argbColor,
                        pageIndex = pageIndex,
                        anchor = PdfAnchor(runIndex = myOrdinal, xobjectPath = currentPath),
                        matrix = pdfMatrix,
                        baselineX = baseline.first,
                        baselineY = baseline.second,
                        charSpacing = charSpacing,
                        wordSpacing = wordSpacing,
                        horizontalScalingPercent = hScale,
                        rise = rise,
                        renderMode = renderMode,
                        alpha = alpha,
                        rotationDegrees = pdfMatrix.rotationDegrees()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record text run $myOrdinal on page $pageIndex: ${e.message}", e)
            }
        }

        private fun decodeText(font: PDFont, bytes: ByteArray): String {
            val sb = StringBuilder()
            val input = ByteArrayInputStream(bytes)
            try {
                while (input.available() > 0) {
                    val code = font.readCode(input)
                    sb.append(font.toUnicode(code) ?: " ")
                }
            } catch (e: Exception) {
                // If decoding fails, append dummy spaces so the run still gets a valid 
                // bounding box width for spatial mapping.
                val remaining = input.available().coerceAtLeast(1)
                for (i in 0 until remaining) sb.append(" ")
            }
            return sb.toString().ifEmpty { " " }
        }

        private fun decodeArrayText(font: PDFont, array: COSArray): String {
            val sb = StringBuilder()
            for (obj in array) {
                if (obj is COSString) {
                    sb.append(decodeText(font, obj.bytes))
                }
            }
            return sb.toString()
        }
    }
}
