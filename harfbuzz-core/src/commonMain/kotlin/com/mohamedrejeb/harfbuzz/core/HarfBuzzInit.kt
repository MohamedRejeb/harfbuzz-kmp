package com.mohamedrejeb.harfbuzz.core

/**
 * One-time initialization gate. Most platforms (JVM, Android, iOS) load
 * HarfBuzz synchronously when the native library is first touched, so this
 * is a no-op there. On Wasm it `await`s the `harfbuzzjs` module's promise
 * (wasm + JS wrapper). Calling `HbFace.from` before this resolves on Wasm
 * throws with a helpful message.
 *
 * Safe to call repeatedly; subsequent calls return immediately.
 *
 * Higher-level helpers (e.g. `rememberHbFont` in `harfbuzz-compose`) call
 * this for you, so application code rarely needs to invoke it directly.
 */
public expect suspend fun harfBuzzInit()

/**
 * Pre-warm HarfBuzz so the first user-visible call doesn't pay the
 * one-time init cost on the cold-paint timeline. Intended to be called
 * from app startup (e.g. `main()` on Desktop, app delegate on iOS,
 * splash screen / Application.onCreate on Android if you want stronger
 * control than the default `androidx.startup` initializer).
 *
 * Behaviour mirrors [harfBuzzInit] but is named to make the intent
 * obvious at call sites: the goal is "warm the engine before the user
 * needs it," not "make sure init has happened." Idempotent.
 *
 * On Android, the library auto-pre-warms via an `androidx.startup`
 * initializer registered in the AAR's manifest, so calling this is
 * usually unnecessary there. JVM/iOS have no equivalent auto-init -
 * call this from your entry point if you care about cold-start
 * latency. On Wasm, this resolves the worker's wasm-module-ready
 * promise.
 */
public suspend fun prewarmHarfBuzz(): Unit = harfBuzzInit()
