package com.mohamedrejeb.harfbuzz

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform