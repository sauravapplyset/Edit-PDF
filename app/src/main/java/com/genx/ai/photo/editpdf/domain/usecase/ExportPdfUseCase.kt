package com.genx.ai.photo.editpdf.domain.usecase

import com.genx.ai.photo.editpdf.domain.repository.PdfRepository
import javax.inject.Inject

class ExportPdfUseCase @Inject constructor(
    private val repository: PdfRepository
) {
    suspend operator fun invoke(outputUriString: String): Result<Unit> {
        return repository.exportDocument(outputUriString)
    }
}
