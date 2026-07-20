package com.genx.ai.photo.editpdf.domain.usecase

import com.genx.ai.photo.editpdf.domain.repository.PdfRepository
import javax.inject.Inject

class ApplyTextEditUseCase @Inject constructor(
    private val repository: PdfRepository
) {
    suspend operator fun invoke(blockId: String, newText: String, pageIndex: Int): Result<Unit> {
        return repository.applyTextEdit(blockId, newText, pageIndex)
    }
}
