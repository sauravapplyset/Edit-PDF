package com.genx.ai.photo.editpdf.domain.usecase

import com.genx.ai.photo.editpdf.domain.repository.PdfRepository
import javax.inject.Inject

class ApplyTextEditUseCase @Inject constructor(
    private val repository: PdfRepository
) {
    suspend operator fun invoke(anchor: com.genx.ai.photo.editpdf.domain.model.PdfAnchor, newText: String, pageIndex: Int): Result<Unit> {
        return repository.applyTextEdit(anchor, newText, pageIndex)
    }
}
