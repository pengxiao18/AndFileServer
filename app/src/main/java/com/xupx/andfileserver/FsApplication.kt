package com.xupx.andfileserver

import android.app.Application

class FsApplication : Application() {
    companion object {
        lateinit var application: FsApplication
    }

    override fun onCreate() {
        super.onCreate()
        application = this
    }

}