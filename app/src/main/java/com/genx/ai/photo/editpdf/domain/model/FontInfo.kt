package com.genx.ai.photo.editpdf.domain.model

// TODO: Add underline and strikethrough Boolean flags
// TODO: Add fontWeight: Int (100-900) for finer weight control beyond just isBold
data class FontInfo(
    val fontName: String,
    val fontSize: Float,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    // PDF /BaseFont name of the resource this run was drawn with (e.g. "ABCDEF+Calibri-Bold").
    val baseFont: String = "",
    // False for the 14 standard fonts / non-embedded references — replaceText() only ever
    // needs to fall back to a substitute font when this is true AND the new text needs a
    // glyph outside what the embedded (often subsetted) program actually contains.
    val isEmbedded: Boolean = false,
    // PDFont subtype: Type0 (composite/CID), TrueType, Type1, MMType1...
    val subType: String = "",
    // True once replaceText() has swapped this run onto a substitute standard-14 font because
    // the edited text needed a glyph the original embedded font didn't have. Position, size,
    // and color are preserved even when this is true — only the glyph program changes.
    val isSubstituted: Boolean = false
)
