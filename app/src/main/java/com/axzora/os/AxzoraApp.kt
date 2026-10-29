package com.axzora.os

import android.app.Application
import org.osmdroid.config.Configuration

/**
 * Application entry point. Initialises the OpenStreetMap tile library
 * (osmdroid) with a unique user-agent, which the OSM tile servers require.
 */
class AxzoraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            userAgentValue = packageName
            // Cache tiles in the app's private storage.
            osmdroidBasePath = getExternalFilesDir(null) ?: filesDir
        }
    }
}
