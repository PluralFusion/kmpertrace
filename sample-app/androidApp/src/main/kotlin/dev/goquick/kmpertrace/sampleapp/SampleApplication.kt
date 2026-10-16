package dev.goquick.kmpertrace.sampleapp

import android.app.Application

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKmperTrace()
    }
}
