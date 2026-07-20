package com.genx.ai.photo.editpdf

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class EditPdfApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Loads the AFM/font-metrics resources bundled in the pdfbox-android AAR — required
        // before any PDFBox font, text, or rendering operation.
        PDFBoxResourceLoader.init(applicationContext)
    }
}


