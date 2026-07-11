/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook

import java.lang.reflect.Method

/**
 * A method hook definition: which method to intercept, and what to run instead.
 *
 * The [replacement] receives the original receiver (`null` for statics), the original arguments,
 * and a continuation it can call to invoke the original implementation. It runs *inside the
 * target process*, on the exact thread the hooked method was called from — this is real runtime
 * interception, not a sandbox replay.
 */
data class MethodHook(
    val target: Method,
    val replacement: (receiver: Any?, args: Array<Any?>, original: () -> Any?) -> Any?
)

/**
 * High-level hook engine: takes [MethodHook] definitions and installs them through
 * [LsplantBridge]. Each definition is wrapped in a generated [Hooker] subclass instance that
 * adapts lsplant's `Object[] args` callback contract to the more ergonomic (receiver, args,
 * original) triple.
 *
 * This object must be driven from inside the target process (after injection), since that is
 * where lsplant operates. The app process uses it only to *enqueue* hooks ahead of injection;
 * the injected payload calls [applyAll] to make them live.
 */
object HookEngine {
    private val pending = mutableListOf<MethodHook>()
    private val installed = LinkedHashMap<Method, Hooker>()

    @Synchronized
    fun enqueue(hook: MethodHook) {
        pending += hook
    }

    @Synchronized
    fun drainPending(): List<MethodHook> {
        val drained = pending.toList()
        pending.clear()
        return drained
    }

    /**
     * Initializes lsplant (if needed) and installs every pending hook. Returns the number of
     * hooks that were successfully installed.
     */
    @Synchronized
    fun applyAll(): Int {
        if (!LsplantBridge.isInitialized && !LsplantBridge.init()) return 0
        val hooks = drainPending()
        var count = 0
        for (hook in hooks) {
            if (install(hook)) count++
        }
        return count
    }

    /**
     * Installs a single [hook] immediately (does not touch the pending queue). Used by the
     * sandbox session to apply hooks one-by-one against a live target ClassLoader.
     */
    @Synchronized
    fun applySingle(hook: MethodHook): Boolean {
        if (!LsplantBridge.isInitialized && !LsplantBridge.init()) return false
        return install(hook)
    }

    private fun install(hook: MethodHook): Boolean {
        val hooker = LambdaHooker(hook.replacement)
        // Locate the callback method on LambdaHooker; lsplant calls it with the packed args.
        val callback = LambdaHooker::class.java.getDeclaredMethod("callback", Array<Any?>::class.java)
        callback.isAccessible = true
        val backup = LsplantBridge.hook(hook.target, hooker, callback)
        if (backup == null) return false
        hooker.backup = backup
        installed[hook.target] = hooker
        return true
    }

    @Synchronized
    fun uninstall(target: Method): Boolean {
        val removed = installed.remove(target) ?: return false
        return LsplantBridge.unhook(target).also { removed.backup = null }
    }

    @Synchronized
    fun installedTargets(): Set<Method> = installed.keys.toSet()

    @Synchronized
    fun clear() {
        for (target in installed.keys.toList()) {
            LsplantBridge.unhook(target)
        }
        installed.clear()
        pending.clear()
    }
}

/**
 * Adapter from a Kotlin lambda `(receiver, args, original) -> Any?` to lsplant's
 * `Object callback(Object[] args)` contract.
 *
 * lsplant packs every argument into a single array: `args[0]` is the receiver for instance
 * methods (and is *absent* for statics — the array is purely parameters, not receiver-prefixed).
 * We unpack accordingly.
 */
private class LambdaHooker(
    private val replacement: (receiver: Any?, args: Array<Any?>, original: () -> Any?) -> Any?
) : Hooker() {
    @Suppress("unused") // called by lsplant via reflection
    fun callback(args: Array<Any?>): Any? {
        // The target's static-ness decides whether args[0] is the receiver.
        val target = backup
        val isStatic = target != null &&
            java.lang.reflect.Modifier.isStatic(target.modifiers)
        val receiver: Any?
        val params: Array<Any?>
        if (isStatic) {
            receiver = null
            params = args
        } else {
            receiver = args.firstOrNull()
            params = if (args.isEmpty()) emptyArray() else args.copyOfRange(1, args.size)
        }
        val original: () -> Any? = {
            // Invoke the backup with the same packed shape lsplant expects.
            val packed = if (isStatic) params else arrayOf(receiver, *params)
            backup?.invoke(receiver, *packed)
        }
        return replacement(receiver, params, original)
    }
}
