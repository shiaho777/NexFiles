/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook.sandbox

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process dispatcher for hook activity logs.
 *
 * Runs inside the sandbox process. Hook rules (notably LOG_CALLS) call [dispatch] to report
 * activity; [SandboxService] registers an [IHookLogListener] that forwards each entry over
 * Binder to the main process UI.
 *
 * Using a process-global singleton (rather than threading a dispatcher through every hook rule)
 * keeps the rule lambdas simple — they just call HookLogDispatcher.dispatch(...).
 */
object HookLogDispatcher {
    interface Listener {
        fun onHookLog(timestamp: Long, level: String, tag: String, message: String)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun dispatch(level: String, tag: String, message: String) {
        val timestamp = System.currentTimeMillis()
        // Also log to logcat so the output is captured even without a UI listener attached.
        when (level) {
            "WARN" -> Log.w(tag, message)
            "ERROR" -> Log.e(tag, message)
            else -> Log.i(tag, message)
        }
        for (listener in listeners) {
            runCatching { listener.onHookLog(timestamp, level, tag, message) }
        }
    }

    fun info(tag: String, message: String) = dispatch("INFO", tag, message)
    fun warn(tag: String, message: String) = dispatch("WARN", tag, message)
    fun error(tag: String, message: String) = dispatch("ERROR", tag, message)
}
