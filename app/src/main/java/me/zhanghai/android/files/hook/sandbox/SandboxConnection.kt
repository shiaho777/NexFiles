/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred

/**
 * Main-process handle to the sandbox service.
 *
 * Binds [SandboxService] (which runs in `:sandbox`) and exposes its [ISandboxService] as a
 * suspend-friendly API. The connection survives across configuration changes when held by a
 * ViewModel-scoped owner; call [close] to unbind and tear down the sandbox.
 *
 * This is the seam between the UI (which runs in the main process) and the hook engine (which
 * runs in the sandbox process). All heavy work — ClassLoader construction, lsplant init, method
 * hooking, target Application startup — happens over Binder in the sandbox process.
 */
class SandboxConnection(private val context: Context) : ServiceConnection {
    @Volatile
    private var service: ISandboxService? = null
    private var ready: CompletableDeferred<ISandboxService>? = null
    private val lock = Any()

    /**
     * Binds the sandbox service and suspends until it's connected. Safe to call multiple times;
     * subsequent calls reuse the existing connection. If the service died and reconnected,
     * a fresh deferred is created so callers observe the new binder.
     */
    suspend fun connect(): ISandboxService {
        service?.let { return it }
        val deferred = synchronized(lock) {
            ready ?: CompletableDeferred<ISandboxService>().also { ready = it }
        }
        if (!context.bindService(Intent(context, SandboxService::class.java), this, Context.BIND_AUTO_CREATE)) {
            deferred.completeExceptionally(IllegalStateException("Failed to bind SandboxService"))
        }
        return deferred.await()
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val svc = ISandboxService.Stub.asInterface(binder)
        service = svc
        synchronized(lock) { ready?.complete(svc) }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        // Reset the deferred so a subsequent connect() awaits the reconnected binder.
        synchronized(lock) { ready = null }
        Log.w(TAG, "SandboxService disconnected (process died)")
    }

    /** The live service handle, or null if not connected. */
    fun serviceOrNull(): ISandboxService? = service

    /** Unbinds and tears down the sandbox session. */
    fun close() {
        runCatching { service?.destroy() }
        runCatching { context.unbindService(this) }
        service = null
        synchronized(lock) { ready = null }
    }

    companion object {
        private const val TAG = "SandboxConnection"
    }
}

/**
 * High-level sandbox operations, usable as suspending extension functions on a connected
 * [SandboxConnection]. Each maps to one Binder transaction.
 */

/** Loads an installed package into the sandbox. */
suspend fun SandboxConnection.loadPackage(packageName: String): Result<Unit> = withService {
    val exception = ParcelableException()
    val ok = it.loadPackage(packageName, exception)
    if (ok) Result.success(Unit) else Result.failure(RuntimeException(exception.toDisplayString()))
}

/** Loads an APK by file path into the sandbox. */
suspend fun SandboxConnection.loadApk(apkPath: String): Result<Unit> = withService {
    val exception = ParcelableException()
    val ok = it.loadApk(apkPath, exception)
    if (ok) Result.success(Unit) else Result.failure(RuntimeException(exception.toDisplayString()))
}

/** Initializes lsplant in the sandbox. */
suspend fun SandboxConnection.initHookEngine(): Result<Unit> = withService {
    val exception = ParcelableException()
    val ok = it.initHookEngine(exception)
    if (ok) Result.success(Unit) else Result.failure(RuntimeException(exception.toDisplayString()))
}

/** Hooks a method with a built-in [HookRule]. */
suspend fun SandboxConnection.hookMethod(
    className: String,
    methodName: String,
    paramTypeNames: Array<String>,
    rule: HookRule,
    ruleArg: String = ""
): HookResult = withService {
    it.hookMethod(className, methodName, paramTypeNames, rule.id, ruleArg)
}

/** Lists the declared methods of [className] in the loaded target. */
suspend fun SandboxConnection.listClassMethods(className: String): List<String> = withService {
    it.listClassMethods(className)
}

/** Searches the loaded target's classes by name. */
suspend fun SandboxConnection.searchClasses(query: String, limit: Int = 100): List<String> =
    withService {
        it.searchClasses(query, limit)
    }

/**
 * Registers a hook-log listener that receives real-time hook activity from the sandbox.
 * Pass null to unregister. The callback runs on a Binder thread.
 */
suspend fun SandboxConnection.setHookLogListener(
    onLog: ((timestamp: Long, level: String, tag: String, message: String) -> Unit)?
) = withService {
    if (onLog == null) {
        it.setHookLogListener(null)
    } else {
        it.setHookLogListener(object : IHookLogListener.Stub() {
            override fun onHookLog(timestamp: Long, level: String, tag: String, message: String) {
                onLog(timestamp, level, tag, message)
            }
        })
    }
}

/** Starts the target's Application (analysis mode). */
suspend fun SandboxConnection.startTargetApplication(): Result<Unit> = withService {
    val exception = ParcelableException()
    val ok = it.startTargetApplication(exception)
    if (ok) Result.success(Unit) else Result.failure(RuntimeException(exception.toDisplayString()))
}

private suspend inline fun <T> SandboxConnection.withService(
    crossinline block: (ISandboxService) -> T
): T {
    val svc = connect()
    return block(svc)
}
