package com.genx.ai.photo.editpdf.domain.model

// One TextBlock == exactly one text-showing content-stream operator (Tj / ' / " / TJ) on a
// page. This is the "custom text extraction model" the PDFBox engine builds during
// ContentStreamTextEditor extraction — everything here comes straight from the graphics state
// active at the moment that operator ran, so replaceText() can restore the identical state
// after swapping only the shown bytes.
// TODO: Add isHyperlink: Boolean and linkUrl: String? for clickable link detection
// TODO: Add confidence: Float (0.0-1.0) for text extraction quality score
data class TextBlock(
    val id: String,
    val text: String,
    val boundingBox: PdfRect,
    val fontInfo: FontInfo,
    val color: Int, // ARGB format
    val pageIndex: Int,
    val anchor: PdfAnchor, // original PDF-space position — permanent write target for every edit
    // CTM x Tm at the moment this run's text-showing operator executed. (e, f) is the run's
    // glyph-space origin in PDF user space (bottom-left origin) — the baseline start point.
    val matrix: PdfMatrix = PdfMatrix.IDENTITY,
    val baselineX: Float = 0f,
    val baselineY: Float = 0f,
    val charSpacing: Float = 0f,     // Tc, in unscaled text space units
    val wordSpacing: Float = 0f,     // Tw, in unscaled text space units
    val horizontalScalingPercent: Float = 100f, // Tz
    val rise: Float = 0f,            // Ts
    val renderMode: Int = 0,         // Tr (0 = fill, 1 = stroke, 2 = fill+stroke, 3 = invisible...)
    val alpha: Float = 1f,           // non-stroking alpha constant (/ca) from the active ExtGState
    val rotationDegrees: Float = 0f  // derived from matrix — 0 for normal horizontal text
)
