package com.genx.ai.photo.editpdf.domain.usecase

import com.genx.ai.photo.editpdf.domain.repository.PdfRepository
import javax.inject.Inject

import com.genx.ai.photo.editpdf.domain.model.TextBlock

class ApplyTextEditUseCase @Inject constructor(
    private val repository: PdfRepository
) {
    suspend operator fun invoke(block: TextBlock, newText: String, pageIndex: Int): Result<Unit> {
        return repository.applyTextEdit(block, newText, pageIndex)
    }
}
