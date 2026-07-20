package com.genx.ai.photo.editpdf.domain.model

import kotlin.math.atan2

// PDF text-rendering matrix (CTM x Tm) in the standard PDF [a b c d e f] form:
// | a  b  0 |
// | c  d  0 |
// | e  f  1 |
// (e, f) is the untransformed origin's image — for a text-showing operator this is the
// glyph-space origin, i.e. the run's baseline start point in PDF user space (bottom-left origin).
data class PdfMatrix(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float
) {
    fun apply(x: Float, y: Float): Pair<Float, Float> =
        (a * x + c * y + e) to (b * x + d * y + f)

    // Angle of the matrix's x-axis relative to the page's x-axis — how far this run's
    // baseline is rotated away from horizontal.
    fun rotationDegrees(): Float = Math.toDegrees(atan2(b.toDouble(), a.toDouble())).toFloat()

    companion object {
        val IDENTITY = PdfMatrix(1f, 0f, 0f, 1f, 0f, 0f)
    }
}
