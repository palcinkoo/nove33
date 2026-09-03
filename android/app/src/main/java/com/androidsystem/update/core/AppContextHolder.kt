package com.androidsystem.update.core

import android.content.Context

/**
 * Lightweight global Context for components that are not @ApplicationScoped
 * (e.g. the OtaUpdater, which is initialised by the foreground service).
 *
 * Set once in ApplicationController.onCreate. Reads outside the main thread
 * are safe — `Context` is immutable here.
 */
object AppContextHolder {
    @Volatile lateinit var context: Context
        private set
    fun init(c: Context) { context = c.applicationContext }
}
