/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook

import java.lang.reflect.Method

/**
 * Kotlin facade over the native nexhook bridge, which in turn drives lsplant.
 *
 * Lifecycle:
 *  1. [System.loadLibrary]'s `nexhook` runs `JNI_OnLoad` (logs "will init on demand").
 *  2. [init] is called, which invokes `lsplant::Init`. Inside the native bridge, lsplant's
 *     inline-hooker is wired to ShadowHook (ByteDance's production inline-hook library), and
 *     the ART symbol resolver walks libart.so's ELF tables. No manual backend wiring is needed.
 *  3. Only after `init` returns true are [hook]/[unhook]/[isHooked] usable.
 *
 * The hook callback contract is dictated by lsplant: the callback method must have signature
 * `Object callback(Object[] args)`, where `args[0]` is the receiver for non-static methods
 * (and is absent for statics). [Hooker] implements that contract.
 */
object LsplantBridge {
    init {
        System.loadLibrary("nexhook")
    }

    /** True once `lsplant::Init` has succeeded in this process. */
    val isInitialized: Boolean
        get() = nativeIsInitialized()

    /**
     * Initializes lsplant. The inline-hook backend (ShadowHook) is built into libnexhook.so and
     * activates automatically inside Init. Returns false (and logs) only if lsplant's own
     * initialization fails (e.g. ART symbol resolution can't find required internals).
     */
    fun init(): Boolean = nativeInit()

    /**
     * Installs a hook on [targetMethod]. When the target is invoked, [callback] runs instead,
     * receiving the arguments (receiver first for non-statics) and returning whatever it returns.
     *
     * @return the backup method, callable via reflection to invoke the original implementation,
     *         or null if the hook could not be installed.
     */
    fun hook(
        targetMethod: Method,
        hooker: Hooker,
        callback: Method
    ): Method? = nativeHook(targetMethod, hooker, callback)

    /** Removes a previously-installed hook on [targetMethod]. */
    fun unhook(targetMethod: Method): Boolean = nativeUnhook(targetMethod)

    /** Whether [method] is currently hooked by lsplant. */
    fun isHooked(method: Method): Boolean = nativeIsHooked(method)

    /** Deoptimizes [method] so an inlined callee's hook is actually reached. */
    fun deoptimize(method: Method): Boolean = nativeDeoptimize(method)

    @JvmStatic private external fun nativeInit(): Boolean
    @JvmStatic private external fun nativeIsInitialized(): Boolean
    @JvmStatic private external fun nativeHook(
        targetMethod: Method, hooker: Hooker, callback: Method
    ): Method?
    @JvmStatic private external fun nativeUnhook(targetMethod: Method): Boolean
    @JvmStatic private external fun nativeIsHooked(method: Method): Boolean
    @JvmStatic private external fun nativeDeoptimize(method: Method): Boolean
}

/**
 * Carrier object for a hook's context. lsplant requires the callback to be an instance method of
 * a class that also holds any state the hook needs; [backup] is filled in by the bridge after
 * `lsplant::Hook` returns so the callback can invoke the original implementation.
 *
 * Subclass this and expose a `Object callback(Object[] args)` method, then pass the subclass
 * instance and that method to [LsplantBridge.hook].
 */
abstract class Hooker {
    /** The original method, settable so the callback can call through to it. */
    @Volatile
    var backup: Method? = null
}
