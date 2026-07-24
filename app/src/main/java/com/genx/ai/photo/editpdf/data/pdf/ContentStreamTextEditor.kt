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

    // True in-place replacement, no overlay: the target Tj/TJ operator's own string operand is
    // mutated directly (see the Tj/TJ branches below). No whiteout rectangle, no extra graphics
    // or text is ever inserted to mask the original glyphs — mutating the operator's operand is
    // itself what makes the old glyphs stop being drawn, so nothing needs to be painted over
    // them. (A previous version inserted a filled white rectangle ahead of the run's text object
    // to mask the original glyphs before drawing the new ones. Besides being an overlay — the
    // opposite of in-place editing — its visual-space-to-PDF-space transform, which also had to
    // undo page rotation, was a real source of misplacement: the rectangle could land somewhere
    // other than where the old glyphs actually were, leaving them visible next to the new text.)
    fun replaceTextRun(
        document: PDDocument,
        pageIndex: Int,
        anchor: PdfAnchor,
        newText: String,
        shiftY: Float = 0f,
        shiftAnchors: List<PdfAnchor> = emptyList()
    ): Boolean {
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

        var currentCharSpacing = 0f
        var currentWordSpacing = 0f
        var currentHorizontalScaling = 100f
        var currentFontSize = 12f

        var i = 0
        outer@ while (i < tokens.size) {
            when (val token = tokens[i]) {
                is COSBase -> operandIndices.add(i)
                is Operator -> {
                    when (token.name) {
                        "Tc" -> currentCharSpacing = (operandAt(tokens, operandIndices, 0) as? com.tom_roush.pdfbox.cos.COSNumber)?.floatValue() ?: 0f
                        "Tw" -> currentWordSpacing = (operandAt(tokens, operandIndices, 0) as? com.tom_roush.pdfbox.cos.COSNumber)?.floatValue() ?: 0f
                        "Tz" -> currentHorizontalScaling = (operandAt(tokens, operandIndices, 0) as? com.tom_roush.pdfbox.cos.COSNumber)?.floatValue() ?: 100f
                        "Tf" -> {
                            currentFontResourceName = operandAt(tokens, operandIndices, 0) as? COSName
                            currentFontSizeOperand = operandAt(tokens, operandIndices, 1)
                            currentFontSize = (currentFontSizeOperand as? com.tom_roush.pdfbox.cos.COSNumber)?.floatValue() ?: 12f
                            currentFont = currentFontResourceName?.let { name ->
                                runCatching { currentResources.getFont(name) }.getOrNull()
                            }
                        }
                        "Tj", "'", "\"", "TJ" -> {
                            if (anchor.runIndices.contains(textShowingCount)) {
                                val isTJ = token.name == "TJ"
                                val operandIndex = operandIndices.lastOrNull()
                                if (operandIndex != null && (tokens[operandIndex] is COSString || tokens[operandIndex] is com.tom_roush.pdfbox.cos.COSArray)) {
                                    if (textShowingCount == anchor.runIndices.first()) {
                                        val oldAdvance = calculateAdvancePx(tokens[operandIndex] as COSBase, currentFont, currentFontSize, currentCharSpacing, currentWordSpacing, currentHorizontalScaling)
                                        
                                        val lines = newText.split("\n")
                                        val lastLineText = lines.last()
                                        val encodedNew = encodeWithFallback(currentFont, lastLineText)
                                        val newAdvance = calculateStringAdvancePx(encodedNew.bytes, encodedNew.font, currentFontSize, currentCharSpacing, currentWordSpacing, currentHorizontalScaling)
                                        
                                        var kerningTJ = 0f
                                        if (currentFontSize != 0f && currentHorizontalScaling != 0f) {
                                            val diff = oldAdvance - newAdvance
                                            kerningTJ = - (diff * 1000f) / (currentFontSize * (currentHorizontalScaling / 100f))
                                        }

                                        var actualOpIdx = i
                                        var actualOpndIdx = operandIndex
                                        if (token.name == "'") {
                                            tokens[i] = Operator.getOperator("Tj")
                                            tokens.add(i, Operator.getOperator("T*"))
                                            actualOpIdx = i + 1
                                        } else if (token.name == "\"") {
                                            if (operandIndices.size >= 3) {
                                                val stringOp = tokens[operandIndices[2]]
                                                val acOp = tokens[operandIndices[1]]
                                                val awOp = tokens[operandIndices[0]]
                                                
                                                tokens[operandIndices[0]] = awOp
                                                tokens.add(operandIndices[0] + 1, Operator.getOperator("Tw"))
                                                tokens.add(operandIndices[0] + 2, acOp)
                                                tokens.add(operandIndices[0] + 3, Operator.getOperator("Tc"))
                                                tokens.add(operandIndices[0] + 4, Operator.getOperator("T*"))
                                                tokens.add(operandIndices[0] + 5, stringOp)
                                                tokens.add(operandIndices[0] + 6, Operator.getOperator("Tj"))
                                                tokens.removeAt(i + 6) // remove original "
                                                
                                                actualOpndIdx = operandIndices[0] + 5
                                                actualOpIdx = operandIndices[0] + 6
                                                i = actualOpIdx
                                            }
                                        }
                                        
                                        val result = applyMultiLineEdit(
                                            page, tokens, actualOpIdx, actualOpndIdx,
                                            currentFont, currentFontResourceName, currentFontSizeOperand, newText, isArray = isTJ, kerningOffsetTJ = kerningTJ
                                        )
                                        edited = result.edited
                                        resourcesTouched = resourcesTouched || result.resourcesTouched
                                        i = result.newOperatorIndex
                                    } else {
                                        if (isTJ) {
                                            val arr = tokens[operandIndex] as com.tom_roush.pdfbox.cos.COSArray
                                            arr.clear()
                                            arr.add(COSString(""))
                                        } else {
                                            tokens[operandIndex] = COSString("")
                                        }
                                    }
                                } else {
                                    throw IllegalStateException("Found ${token.name} at index $textShowingCount but operand is invalid. Operand index: $operandIndex")
                                }
                                if (textShowingCount == anchor.runIndices.last() && shiftAnchors.isEmpty()) break@outer
                            } else if (shiftAnchors.any { it.runIndices.first() == textShowingCount }) {
                                val operandIndex = operandIndices.firstOrNull() ?: i
                                tokens.add(operandIndex, com.tom_roush.pdfbox.cos.COSFloat(0f))
                                tokens.add(operandIndex + 1, com.tom_roush.pdfbox.cos.COSFloat(shiftY))
                                tokens.add(operandIndex + 2, Operator.getOperator("Td"))
                                i += 3
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

    private fun calculateAdvancePx(
        baseObj: COSBase,
        font: PDFont?,
        fontSize: Float,
        charSpacing: Float,
        wordSpacing: Float,
        hScale: Float
    ): Float {
        if (font == null) return 0f
        var total = 0f
        if (baseObj is com.tom_roush.pdfbox.cos.COSString) {
            total += calculateStringAdvancePx(baseObj.bytes, font, fontSize, charSpacing, wordSpacing, hScale)
        } else if (baseObj is com.tom_roush.pdfbox.cos.COSArray) {
            for (i in 0 until baseObj.size()) {
                val item = baseObj.get(i)
                if (item is com.tom_roush.pdfbox.cos.COSString) {
                    total += calculateStringAdvancePx(item.bytes, font, fontSize, charSpacing, wordSpacing, hScale)
                } else if (item is com.tom_roush.pdfbox.cos.COSNumber) {
                    val w = item.floatValue()
                    total -= (w / 1000f) * fontSize * (hScale / 100f)
                }
            }
        }
        return total
    }

    private fun calculateStringAdvancePx(
        bytes: ByteArray,
        font: PDFont,
        fontSize: Float,
        charSpacing: Float,
        wordSpacing: Float,
        hScale: Float
    ): Float {
        var totalAdvance = 0f
        val input = java.io.ByteArrayInputStream(bytes)
        try {
            while (input.available() > 0) {
                val code = font.readCode(input)
                val glyphWidthEm = runCatching { font.getWidth(code) / 1000f }.getOrDefault(0.5f)
                val isSpace = runCatching { font.toUnicode(code) == " " }.getOrDefault(false)
                val tx = (glyphWidthEm * fontSize + charSpacing + (if (isSpace) wordSpacing else 0f)) * (hScale / 100f)
                totalAdvance += tx
            }
        } catch (e: Exception) {
        }
        return totalAdvance
    }

    private data class MultiEditResult(val edited: Boolean, val resourcesTouched: Boolean, val newOperatorIndex: Int)

    private fun applyMultiLineEdit(
        page: PDPage,
        tokens: MutableList<Any?>,
        operatorIndex: Int,
        operandIndex: Int,
        currentFont: PDFont?,
        currentFontResourceName: COSName?,
        currentFontSizeOperand: COSBase?,
        newText: String,
        isArray: Boolean,
        kerningOffsetTJ: Float = 0f
    ): MultiEditResult {
        val lines = newText.split("\n")
        var resourcesTouched = false
        var currentOpIdx = operatorIndex

        for (i in lines.indices) {
            val lineText = lines[i]
            val encoded = encodeWithFallback(currentFont, lineText)
            
            if (i == 0) {
                // First line replaces the original operator in place
                val newArray = com.tom_roush.pdfbox.cos.COSArray()
                if (isArray) {
                    val oldArray = tokens[operandIndex] as? com.tom_roush.pdfbox.cos.COSArray
                    if (oldArray != null) {
                        for (k in 0 until oldArray.size()) {
                            val item = oldArray.get(k)
                            if (item is com.tom_roush.pdfbox.cos.COSNumber) newArray.add(item) else break
                        }
                    }
                }
                newArray.add(COSString(encoded.bytes))
                
                if (i == lines.size - 1 && kerningOffsetTJ != 0f) {
                    newArray.add(com.tom_roush.pdfbox.cos.COSFloat(kerningOffsetTJ))
                }
                
                tokens[operandIndex] = newArray
                if (!isArray) {
                    tokens[currentOpIdx] = Operator.getOperator("TJ")
                }

                if (encoded.substituted) {
                    spliceSubstituteFont(page, tokens, operandIndex, encoded.font, currentFontResourceName, currentFontSizeOperand)
                    resourcesTouched = true
                    currentOpIdx += 3 // spliceSubstituteFont adds 3 tokens before the operand (Tf switch)
                }
            } else {
                // Subsequent lines: insert Td and Tj
                val fontSize = (currentFontSizeOperand as? com.tom_roush.pdfbox.cos.COSNumber)?.floatValue() ?: 12f
                val leading = fontSize * 1.2f
                
                // Insert: 0 -leading Td
                tokens.add(currentOpIdx + 1, com.tom_roush.pdfbox.cos.COSFloat(0f))
                tokens.add(currentOpIdx + 2, com.tom_roush.pdfbox.cos.COSFloat(-leading))
                tokens.add(currentOpIdx + 3, Operator.getOperator("Td"))
                
                // Insert string and Tj
                val newArray = com.tom_roush.pdfbox.cos.COSArray()
                newArray.add(COSString(encoded.bytes))
                if (i == lines.size - 1 && kerningOffsetTJ != 0f) {
                    newArray.add(com.tom_roush.pdfbox.cos.COSFloat(kerningOffsetTJ))
                }
                
                tokens.add(currentOpIdx + 4, newArray)
                tokens.add(currentOpIdx + 5, Operator.getOperator("TJ"))
                
                if (encoded.substituted) {
                    spliceSubstituteFont(page, tokens, currentOpIdx + 4, encoded.font, currentFontResourceName, currentFontSizeOperand)
                    resourcesTouched = true
                    currentOpIdx += 3 // 3 tokens added
                }
                
                currentOpIdx += 5
            }
        }
        
        return MultiEditResult(edited = true, resourcesTouched = resourcesTouched, newOperatorIndex = currentOpIdx)
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
    // glyph set genuinely cannot represent a character in the new text do we fall back to a
    // close standard-14 font, per the confirmed product policy (same behavior Adobe Acrobat
    // itself falls back to). Position, size, and color are untouched either way.
    //
    // Two failure modes both funnel into the same fallback path, and BOTH must be flagged
    // `substituted = true` so the caller splices in a matching Tf switch:
    //
    //  1. `originalFont` is null — the run's font resource itself failed to resolve (seen for
    //     some embedded Type0/CID fonts pdfbox-android cannot fully parse). There is no real
    //     font to reuse here. Silently encoding with a stand-in WITHOUT switching Tf — the old
    //     bug — leaves stand-in single-byte codes being interpreted by whatever font the Tf
    //     operator still points at (often a 2-byte CID font), rendering as garbage or nothing.
    //  2. `font.encode(text)` throws. PDFBox signals "no glyph for this character" via
    //     IllegalArgumentException, but embedded/subset/CID fonts can also throw IOException (or
    //     other RuntimeExceptions) while lazily parsing a malformed glyph/cmap table. Catching
    //     only IllegalArgumentException let those propagate up and abort the whole edit — which
    //     is exactly "text extraction works but replacement fails" for certain embedded fonts.
    private fun encodeWithFallback(originalFont: PDFont?, text: String): EncodedRun {
        if (originalFont == null) {
            val substitute = pickSubstituteFont(null)
            Log.w(TAG, "Run's font resource could not be resolved; substituting '${substitute.name}' for this run only")
            return EncodedRun(substitute.encode(text), substitute, substituted = true)
        }
        return try {
            EncodedRun(originalFont.encode(text), originalFont, substituted = false)
        } catch (e: Exception) {
            val substitute = pickSubstituteFont(originalFont)
            Log.w(TAG, "Font '${runCatching { originalFont.name }.getOrNull()}' could not encode the " +
                "edited text (${e.javaClass.simpleName}: ${e.message}); substituting '${substitute.name}' for this run only")
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

        // Every operator is isolated: a single unsupported/malformed operator ANYWHERE on the
        // page (a broken shading pattern, an exotic color space, a corrupt inline image...) must
        // never abort the scan. Before this fix, an uncaught exception from ANY operator here
        // propagated out of processPage() and was only caught by extractTextRuns()'s top-level
        // try/catch — which returns whatever runs were collected UP TO that point and silently
        // drops every run after it. Any text positioned later in the content stream (e.g. a
        // caption or heading drawn after a problematic graphics operator) then has no PDFBox run
        // to match against, so MuPdfEngine's nearest-neighbor spatial matching latches onto the
        // closest surviving (but wrong) run instead — which is why editing/replacing such text
        // silently failed or corrupted unrelated text, even though extraction seemed to work.
        private var currentOrdinal: Int = 0

        override fun processOperator(operator: com.tom_roush.pdfbox.contentstream.operator.Operator, operands: MutableList<com.tom_roush.pdfbox.cos.COSBase>?) {
            if (operator.name == "Tj" || operator.name == "TJ" || operator.name == "'" || operator.name == "\"") {
                val currentPath = xobjectStack.toList()
                val count = runCounters.getOrDefault(currentPath, 0)
                currentOrdinal = count
                runCounters[currentPath] = count + 1
            }
            try {
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
            } catch (e: Exception) {
                Log.e(TAG, "Skipping operator '${operator.name}' on page $pageIndex after failure: ${e.message}", e)
            }
        }

        private var inShowTextStrings = false

        override fun showTextString(string: ByteArray) {
            if (!inShowTextStrings) {
                recordRun(string, null)
            }
            super.showTextString(string)
        }

        override fun showTextStrings(array: COSArray) {
            recordRun(null, array)
            inShowTextStrings = true
            try {
                super.showTextStrings(array)
            } finally {
                inShowTextStrings = false
            }
        }

        private fun recordRun(bytes: ByteArray?, array: com.tom_roush.pdfbox.cos.COSArray?) {
            val currentPath = xobjectStack.toList()
            val myOrdinal = currentOrdinal
            try {
                val gs = graphicsState
                val textState = gs.textState
                val font = textState.font ?: return
                val text = if (bytes != null) decodeText(font, bytes) else decodeArrayText(font, array!!)
                if (text.trim().isEmpty()) {
                    return // Do not record invisible space blocks; their index was already counted.
                }

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
                        anchor = PdfAnchor(runIndices = listOf(myOrdinal), xobjectPath = currentPath),
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
