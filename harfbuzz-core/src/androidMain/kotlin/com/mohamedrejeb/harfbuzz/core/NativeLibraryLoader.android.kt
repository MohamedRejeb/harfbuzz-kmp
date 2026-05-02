package com.mohamedrejeb.harfbuzz.core

internal actual fun loadNativeLib(name: String) {
    System.loadLibrary(name)
}
