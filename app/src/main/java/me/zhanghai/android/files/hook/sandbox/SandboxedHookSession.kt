/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook.sandbox

import android.util.Log
import me.zhanghai.android.files.hook.HookEngine
import me.zhanghai.android.files.hook.LsplantBridge
import me.zhanghai.android.files.hook.MethodHook
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One hook session against a [SandboxedAppLoader.LoadedApp].
 *
 * A session owns the lifecycle of the loaded target: it ensures lsplant is initialized in the
 * sandbox process, queues method hooks expressed against the target's classes, and applies them
 * once the target's ClassLoader is live. Hooks can be added before or after the target's
 * Application.onCreate; they take effect immediately on apply.
 *
 * This runs entirely inside the sandbox process — no IPC, no ptrace, no root. The target's
 * methods are hooked because the target's bytecode is executing in *our* address space, where
 * lsplant has full ArtMethod access.
 */
class SandboxedHookSession(
    val loadedApp: SandboxedAppLoader.LoadedApp
) {
    private val hooks = CopyOnWriteArrayList<MethodHook>()
    private val installedTargets = CopyOnWriteArrayList<Method>()
    @Volatile private var closed = false

    /**
     * Ensures lsplant is initialized in this process. Idempotent.
     *
     * The native bridge (libnexhook.so) bundles ShadowHook as the inline-hook backend, so
     * lsplant::Init activates it automatically — no manual wiring needed. After this returns
     * true, [hookMethod] can hook any method reachable from the target's ClassLoader.
     */
    fun ensureInitialized(): Boolean {
        return LsplantBridge.isInitialized || LsplantBridge.init()
    }

    /**
     * Queues a hook against a method located by [className].[methodName]([paramTypes]) within
     * the target's isolated ClassLoader. The hook is applied immediately; throws if the method
     * can't be found.
     */
    fun hookMethod(
        className: String,
        methodName: String,
        paramTypes: Array<Class<*>>,
        replacement: (receiver: Any?, args: Array<Any?>, original: () -> Any?) -> Any?
    ): Boolean {
        check(!closed) { "Session is closed" }
        val clazz = try {
            loadedApp.loadClass(className)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Class not found in target: $className", e)
            return false
        }
        val method = try {
            clazz.getDeclaredMethod(methodName, *paramTypes)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Method not found: $className.$methodName", e)
            return false
        }
        method.isAccessible = true
        val hook = MethodHook(method, replacement)
        hooks += hook
        return applyHook(hook)
    }

    /**
     * Queues a hook against an already-resolved [Method] from the target's ClassLoader.
     */
    fun hookMethod(
        method: Method,
        replacement: (receiver: Any?, args: Array<Any?>, original: () -> Any?) -> Any?
    ): Boolean {
        check(!closed) { "Session is closed" }
        method.isAccessible = true
        val hook = MethodHook(method, replacement)
        hooks += hook
        return applyHook(hook)
    }

    private fun applyHook(hook: MethodHook): Boolean {
        if (!ensureInitialized()) {
            Log.e(TAG, "lsplant not initialized; hook on ${hook.target} skipped")
            return false
        }
        val success = HookEngine.applySingle(hook)
        if (success) {
            installedTargets += hook.target
        }
        return success
    }

    /** Removes all hooks installed by this session. */
    fun close() {
        if (closed) return
        closed = true
        for (target in installedTargets) {
            HookEngine.uninstall(target)
        }
        installedTargets.clear()
        hooks.clear()
    }

    /** Snapshot of methods currently hooked by this session. */
    fun hookedMethods(): List<Method> = installedTargets.toList()

    companion object {
        private const val TAG = "SandboxedHookSession"
    }
}
