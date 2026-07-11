/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook

import java.io.File

/**
 * Concrete [RemoteInvoker] that uses ptrace to load the nexhook payload into the target process.
 *
 * The injector lives in a separate native library (`libnexhook_inject.so`) so that the ptrace
 * code — which is privileged and only meaningful in the root service — isn't pulled into the
 * normal app process at all. This object is a thin JNI shim over the single C entry point
 * [nativeInjectDlopen].
 *
 * Preconditions (enforced by the root service before this is reached):
 *  - The caller runs as root (uid 0), which is the only uid with ptrace rights on arbitrary
 *    processes under enforcing SELinux.
 *  - The target process exists and is stoppable.
 *
 * The block passed to [invokeInProcess] is executed via a follow-up remote JNI call after dlopen
 * completes; this two-step design keeps the native injector focused on "load the library" while
 * the Kotlin side owns "what to do once loaded".
 */
object PtraceInjectorNative {
    @Volatile
    private var loaded: Boolean = false

    /**
     * Loads `libnexhook_inject.so`. Safe to call from any process; it is a no-op if the library
     * isn't packaged (it is only shipped when the hook feature is built). Returns whether the
     * load succeeded.
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = runCatching { System.loadLibrary("nexhook_inject"); true }.getOrDefault(false)
        return loaded
    }

    /** True when [ensureLoaded] has succeeded in this process. */
    val isAvailable: Boolean
        get() = loaded

    /**
     * Loads [libraryPath] into [targetPid] by remote-calling dlopen. Returns the remote handle,
     * or 0 on failure. Must be called from the root service.
     */
    fun injectLibrary(targetPid: Int, libraryPath: String): Long {
        if (!ensureLoaded()) return 0
        return nativeInjectDlopen(targetPid, libraryPath)
    }

    @JvmStatic
    private external fun nativeInjectDlopen(targetPid: Int, libraryPath: String): Long
}

/**
 * A [RemoteInvoker] backed by [PtraceInjectorNative]. It performs the dlopen, then runs [block]
 * to apply the pending hooks.
 *
 * The full "run [block] inside the target" step requires a remote-JNI bridge that is part of the
 * payload; until that trampoline lands, this invoker delivers the library and then runs [block]
 * locally so the hook registry can at least be reconciled. This is the one piece that benefits
 * most from on-device validation.
 */
class PtraceRemoteInvoker : RemoteInvoker {
    override fun invokeInProcess(
        targetProcess: Int,
        payload: Any,
        block: () -> Int
    ): Int {
        val path = (payload as? HookPayload)?.nexhookPath ?: return -1
        val handle = PtraceInjectorNative.injectLibrary(targetProcess, path)
        if (handle == 0L) return -1
        return block()
    }
}

/** The staged payload delivered by [HookInjector]: the absolute path to libnexhook.so. */
data class HookPayload(val nexhookPath: String) {
    constructor(nexhook: File) : this(nexhook.absolutePath)
}
