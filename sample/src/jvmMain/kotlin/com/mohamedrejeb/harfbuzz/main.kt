package com.mohamedrejeb.harfbuzz

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "kotlin-harfbuzz",
    ) {
        App()
    }
}