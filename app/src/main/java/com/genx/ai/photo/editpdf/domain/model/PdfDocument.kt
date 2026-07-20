package com.genx.ai.photo.editpdf.domain.model

data class PdfDocument(
    val uri: String,
    val pageCount: Int,
    val pages: List<PdfPage>
)
