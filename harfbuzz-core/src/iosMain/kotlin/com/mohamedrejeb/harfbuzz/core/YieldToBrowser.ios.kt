package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.yield

/**
 * iOS actual: a regular `yield()`. The build runs on a background
 * dispatcher so there's no browser event loop to coordinate with -
 * yielding gives other coroutines a turn and acts as a cancellation
 * check.
 */
public actual suspend fun yieldToBrowser(): Unit = yield()
