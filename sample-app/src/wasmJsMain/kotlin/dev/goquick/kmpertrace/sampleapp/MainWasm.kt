package dev.goquick.kmpertrace.sampleapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.w3c.dom.HTMLDivElement

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    initKmperTrace()
    ComposeViewport(document.getElementById("root") as HTMLDivElement) {
        App()
    }
}
