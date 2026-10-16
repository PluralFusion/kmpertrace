package dev.goquick.kmpertrace.sampleapp

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    initKmperTrace()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "SampleApp",
        ) {
            App()
        }
    }
}