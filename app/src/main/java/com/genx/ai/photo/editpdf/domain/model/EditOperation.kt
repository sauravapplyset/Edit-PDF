package com.genx.ai.photo.editpdf.domain.model

// TODO: Add ImageEdit operation type (replace an image on the page)
// TODO: Add FormattingEdit type to change font size / color without changing text
// TODO: Add PageOperation type for add / delete / reorder pages
sealed class EditOperation {
    data class TextEdit(
        val blockId: String,
        val originalText: String,
        val newText: String,
        val pageIndex: Int
    ) : EditOperation()
}
