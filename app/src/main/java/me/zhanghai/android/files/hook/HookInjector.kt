/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Orchestrates getting lsplant + the user's hooks into a target app process.
 *
 * The two halves of the problem are kept cleanly separated:
 *
 *  1. **Payload delivery** — `libnexhook.so` must be present in the target process's address
 *     space. We locate it in the app's nativeLibraryDir and hand its absolute path to the
 *     [RemoteInvoker], which makes the target dlopen it.
 *
 *  2. **Remote invocation** — once the library is loaded, [HookEngine.applyAll] runs the pending
 *     hooks. [RemoteInvoker] abstracts the concrete mechanism (ptrace inject, JDWP, app_process
 *     respawn) so the rest of the engine stays mechanism-agnostic.
 *
 * The full chain runs inside the root service, which is the only context in this app with the
 * privileges ptrace needs.
 */
class HookInjector(
    private val context: Context,
    private val remoteInvoker: RemoteInvoker
) {
    /**
     * Prepares the hook payload for injection into [targetProcess] and performs the injection.
     *
     * The inline-hook backend (ShadowHook) is built into libnexhook.so, so no manual backend
     * wiring is needed — lsplant::Init activates it automatically.
     *
     * @param targetProcess the PID of the app process to hook into.
     * @return the number of hooks installed in the target, or -1 on failure.
     */
    fun inject(targetProcess: Int): Int {
        val payload = stagePayload() ?: return -1
        return remoteInvoker.invokeInProcess(targetProcess, payload) {
            // Runs in the invoker's chosen context (inside the target once the remote-JNI
            // bridge is in place). ShadowHook is built into libnexhook.so, so init just works.
            if (!LsplantBridge.init()) {
                throw IOException("lsplant::Init failed in target process")
            }
            HookEngine.applyAll()
        }
    }

    /**
     * Locates libnexhook.so in the app's nativeLibraryDir and packages it for delivery.
     * Returns null if the library isn't present (the hook feature wasn't built into this APK).
     */
    private fun stagePayload(): HookPayload? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nexhook = File(nativeDir, "libnexhook.so")
        if (!nexhook.exists()) return null
        return HookPayload(nexhook)
    }
}

/**
 * Abstraction over the remote-invocation mechanism (ptrace inject, JDWP, app_process respawn).
 *
 * [invokeInProcess] runs [block] inside [targetProcess], after ensuring [payload] has been loaded
 * there. The concrete implementation ([PtraceRemoteInvoker]) lives next to the root service;
 * there is no default implementation because injection is never available without a privileged
 * backend.
 */
fun interface RemoteInvoker {
    fun invokeInProcess(
        targetProcess: Int,
        payload: HookPayload,
        block: () -> Int
    ): Int
}
