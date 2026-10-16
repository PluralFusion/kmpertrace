package dev.goquick.kmpertrace.sampleapp

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKmperTrace()
    return ComposeUIViewController { App() }
}