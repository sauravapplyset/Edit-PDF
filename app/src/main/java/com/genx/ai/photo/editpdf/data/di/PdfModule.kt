package com.genx.ai.photo.editpdf.data.di

import com.genx.ai.photo.editpdf.data.pdf.MuPdfEngine
import com.genx.ai.photo.editpdf.data.pdf.PdfEngine
import com.genx.ai.photo.editpdf.data.repository.PdfRepositoryImpl
import com.genx.ai.photo.editpdf.domain.repository.PdfRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PdfModule {

    @Binds
    @Singleton
    abstract fun bindPdfEngine(
        muPdfEngine: MuPdfEngine
    ): PdfEngine

    @Binds
    @Singleton
    abstract fun bindPdfRepository(
        pdfRepositoryImpl: PdfRepositoryImpl
    ): PdfRepository
}
