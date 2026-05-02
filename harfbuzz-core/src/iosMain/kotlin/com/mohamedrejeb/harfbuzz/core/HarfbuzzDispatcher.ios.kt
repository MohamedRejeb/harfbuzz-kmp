package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * iOS uses a parallelism-1 view onto Default for serialized HB access.
 * Default on Kotlin/Native is a real thread pool, so limitedParallelism(1)
 * gives us a single-threaded queue without spinning up a dedicated thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal actual val harfbuzzDispatcher: CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(1)
