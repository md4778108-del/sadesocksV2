package com.sadesocks.app

import android.app.Application
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                Diag.log(this, "CRASH in ${thread.name}: " + Log.getStackTraceString(error))
            } catch (e: Throwable) {
                // ignore
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
