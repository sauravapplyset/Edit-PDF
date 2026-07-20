package com.genx.ai.photo.editpdf.domain.usecase

import com.genx.ai.photo.editpdf.domain.model.TextBlock
import com.genx.ai.photo.editpdf.domain.repository.PdfRepository
import javax.inject.Inject

class ExtractTextBlocksUseCase @Inject constructor(
    private val repository: PdfRepository
) {
    suspend operator fun invoke(pageIndex: Int): Result<List<TextBlock>> {
        return repository.extractTextBlocks(pageIndex)
    }
}
