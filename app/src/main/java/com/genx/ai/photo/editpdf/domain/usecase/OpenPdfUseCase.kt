package com.genx.ai.photo.editpdf.domain.usecase

import com.genx.ai.photo.editpdf.domain.model.PdfDocument
import com.genx.ai.photo.editpdf.domain.repository.PdfRepository
import javax.inject.Inject

class OpenPdfUseCase @Inject constructor(
    private val repository: PdfRepository
) {
    suspend operator fun invoke(uriString: String): Result<PdfDocument> {
        return repository.openDocument(uriString)
    }
}
