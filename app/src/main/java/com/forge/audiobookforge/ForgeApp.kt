package com.forge.audiobookforge

import android.app.Application
import com.forge.audiobookforge.di.AppContainer

class ForgeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
