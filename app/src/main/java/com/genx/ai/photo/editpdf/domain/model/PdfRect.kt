package com.genx.ai.photo.editpdf.domain.model

// TODO: Add intersects(other: PdfRect): Boolean utility for hit-test checks
// TODO: Add expandBy(pt: Float): PdfRect convenience extension for padding
data class PdfRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
