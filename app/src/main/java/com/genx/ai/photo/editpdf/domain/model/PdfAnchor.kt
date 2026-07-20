package com.genx.ai.photo.editpdf.domain.model

// Identifies a specific text-showing operator (Tj / ' / " / TJ) in a page's content stream, as
// an ordinal counted only among text-showing operators in stream order (Form XObject content is
// not descended into, so this ordinal only ever counts operators belonging to the page's own
// content stream). This ordinal is stable across edits: ContentStreamTextEditor.replaceTextRun
// mutates that operator's operand bytes in place and never inserts, removes, or reorders
// operators (except the isolated font-substitution fallback path, which only ever splices in a
// non-text-showing Tf operator, so it never changes what any runIndex refers to) — so the same
// anchor always points at the same PDF text object, no matter how many times it has already
// been edited.
data class PdfAnchor(
    val runIndices: List<Int>,
    val xobjectPath: List<String> = emptyList()
) {
    // For backward compatibility or single-run blocks
    val runIndex: Int get() = runIndices.firstOrNull() ?: -1
}
