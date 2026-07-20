package com.genx.ai.photo.editpdf.domain.usecase

import com.genx.ai.photo.editpdf.domain.model.EditOperation
import com.genx.ai.photo.editpdf.domain.repository.PdfRepository
import javax.inject.Inject

class RedoEditUseCase @Inject constructor(
    private val repository: PdfRepository
) {
    suspend operator fun invoke(): Result<EditOperation?> {
        return repository.redo()
    }
    
    fun canRedo(): Boolean {
        return repository.canRedo()
    }
}
