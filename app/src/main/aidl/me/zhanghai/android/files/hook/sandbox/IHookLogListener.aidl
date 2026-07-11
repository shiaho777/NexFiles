/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook.sandbox;

/**
 * Callback interface through which the sandbox pushes hook activity back to the main process.
 *
 * Registered via [ISandboxService.setHookLogListener]. The sandbox calls [onHookLog] whenever a
 * hooked method is invoked (from the LOG_CALLS rule) or when any hook reports noteworthy
 * activity. This lets the UI surface hook results in real time without the user having to read
 * logcat.
 */
interface IHookLogListener {
    /**
     * Called from the sandbox process on the thread that triggered the hook.
     * @param timestamp epoch millis when the event occurred.
     * @param level one of "INFO", "WARN", "ERROR".
     * @param tag a short category, typically the hooked method's class.name.
     * @param message the detail line.
     */
    void onHookLog(long timestamp, String level, String tag, String message);
}
