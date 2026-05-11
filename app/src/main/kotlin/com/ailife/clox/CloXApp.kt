package com.ailife.clox

import android.app.Application

class CloXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CloXApp
            private set
    }
}
