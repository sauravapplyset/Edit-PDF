package com.genx.ai.photo.editpdf.data.pdf

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle

// Small shared geometry helpers used by both PdfBoxEngine (page dimensions reported to the UI)
// and ContentStreamTextEditor (per-run bounding boxes) so both agree on what "visual" page space
// means for a rotated page. PDF content-stream coordinates (Tm, CTM, MediaBox) are always defined
// in the page's own UNROTATED space; /Rotate is an additional display-time transform layered on
// top by viewers (and by PDFRenderer when it rasterizes a page) — best-effort here, since the
// core text-editing correctness never depends on it (content-stream mutation targets operators
// directly, never derived screen coordinates).

internal data class VisualPageSize(val widthPt: Float, val heightPt: Float)

private fun PDPage.normalizedRotation(): Int = ((rotation % 360) + 360) % 360

internal fun PDPage.visualSize(): VisualPageSize {
    val box = mediaBox ?: PDRectangle(612f, 792f)
    return if (normalizedRotation() == 90 || normalizedRotation() == 270) {
        VisualPageSize(box.height, box.width)
    } else {
        VisualPageSize(box.width, box.height)
    }
}

// Maps a point in the page's own unrotated PDF space (bottom-left origin) into visual/display
// space (still bottom-left origin) by applying /Rotate the same way a viewer would.
internal fun PDPage.toVisualPoint(x: Float, y: Float): Pair<Float, Float> {
    val box = mediaBox ?: PDRectangle(612f, 792f)
    val lx = box.lowerLeftX
    val ly = box.lowerLeftY
    val w = box.width
    val h = box.height
    
    val sx = x - lx
    val sy = y - ly
    
    return when (normalizedRotation()) {
        90 -> sy to (w - sx)
        180 -> (w - sx) to (h - sy)
        270 -> (h - sy) to sx
        else -> sx to sy
    }
}
