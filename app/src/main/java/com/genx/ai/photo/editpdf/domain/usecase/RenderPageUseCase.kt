package com.genx.ai.photo.editpdf.domain.usecase

import android.graphics.Bitmap
import com.genx.ai.photo.editpdf.domain.repository.PdfRepository
import javax.inject.Inject

class RenderPageUseCase @Inject constructor(
    private val repository: PdfRepository
) {
    suspend operator fun invoke(pageIndex: Int, scale: Float = 2.0f): Result<Bitmap> {
        return repository.renderPage(pageIndex, scale)
    }
}
