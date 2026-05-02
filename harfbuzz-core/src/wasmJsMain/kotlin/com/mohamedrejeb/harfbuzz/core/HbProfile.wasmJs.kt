@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Lightweight Wasm-only profiling that surfaces hot paths in Chrome
 * DevTools' Performance panel without dragging in a global toolchain.
 *
 *  - [perfTrack] wraps a suspend block with a `performance.mark` / `measure`
 *    pair. The measured ranges show up in the **User Timing** track, with
 *    the name we pass - much more readable than walking minified Wasm
 *    stack frames.
 *  - [installLongTaskObserver] subscribes to `PerformanceObserver`'s
 *    `longtask` entries and logs every JS task >50 ms to the console with
 *    its start time. Useful for catching unexpected blockers we forgot to
 *    instrument.
 *
 * Cost is essentially free in production builds (a couple of microseconds
 * per call) so we leave it always on; flip [HB_PROFILE_ENABLED] to false
 * here if you ever need to silence it.
 */

private const val HB_PROFILE_ENABLED: Boolean = true

@JsFun("(name) => { try { performance.mark(name); } catch (e) {} }")
private external fun jsPerfMark(name: String)

@JsFun("(name, startMark) => { try { performance.measure(name, startMark); } catch (e) {} }")
private external fun jsPerfMeasure(name: String, startMark: String)

private var markCounter: Int = 0

/**
 * Wrap [block] with a `performance.measure` named [name]. Each call gets
 * a unique start-mark suffix so concurrent calls (or re-entrant ones)
 * don't trample each other's mark in the buffer.
 */
internal suspend inline fun <T> perfTrack(name: String, crossinline block: suspend () -> T): T {
    if (!HB_PROFILE_ENABLED) return block()
    val startMark = "$name:start#${++markCounter}"
    jsPerfMark(startMark)
    try {
        return block()
    } finally {
        jsPerfMeasure(name, startMark)
    }
}

/** Sync variant for non-suspend hot paths (e.g. wire decoders). */
internal inline fun <T> perfTrackSync(name: String, block: () -> T): T {
    if (!HB_PROFILE_ENABLED) return block()
    val startMark = "$name:start#${++markCounter}"
    jsPerfMark(startMark)
    try {
        return block()
    } finally {
        jsPerfMeasure(name, startMark)
    }
}

@JsFun(
    """
    () => {
        if (self.__hbLongTaskObserver) return;
        try {
            const obs = new PerformanceObserver((list) => {
                for (const entry of list.getEntries()) {
                    const name = entry.name || 'task';
                    const dur = entry.duration | 0;
                    const start = entry.startTime | 0;
                    console.warn('[hb-longtask] ' + dur + 'ms ' + name + ' @ +' + start + 'ms');
                }
            });
            obs.observe({ type: 'longtask', buffered: true });
            self.__hbLongTaskObserver = obs;
        } catch (e) {
            // Long Tasks API may be unsupported - ignore.
        }
    }
    """
)
private external fun jsInstallLongTaskObserver()

/**
 * Install a `PerformanceObserver` that logs every `longtask` (>50 ms on
 * the main thread, per the Long Tasks API) to the console. Call once at
 * startup; safe to call multiple times - duplicate observers won't fire
 * twice because we keep a single global handle.
 */
public fun installLongTaskObserver() {
    if (!HB_PROFILE_ENABLED) return
    jsInstallLongTaskObserver()
}

@JsFun("(resume) => { setTimeout(resume, 0); }")
private external fun jsScheduleMacrotask(resume: () -> Unit)

/**
 * Wasm actual for `yieldToBrowser` (declared in commonMain). Hops the
 * coroutine continuation through `setTimeout(0)` so the next chunk of
 * work runs in a fresh JS event-loop task.
 */
public actual suspend fun yieldToBrowser(): Unit = suspendCancellableCoroutine { cont ->
    jsScheduleMacrotask { cont.resume(Unit) }
}
