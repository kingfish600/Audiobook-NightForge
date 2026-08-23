package com.forge.audiobookforge

import android.app.Application
import com.forge.audiobookforge.di.AppContainer

class ForgeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        com.forge.audiobookforge.CrashRecorder.install(this)
        container = AppContainer(this)
    }
}
