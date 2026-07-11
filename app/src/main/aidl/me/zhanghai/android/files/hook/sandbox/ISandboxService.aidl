/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook.sandbox;

import me.zhanghai.android.files.hook.sandbox.ParcelableException;
import me.zhanghai.android.files.hook.sandbox.HookResult;
import me.zhanghai.android.files.hook.sandbox.IHookLogListener;

/**
 * Binder interface to the sandbox process.
 *
 * The sandbox process (declared in the manifest with android:process=":sandbox") owns the
 * isolated ClassLoader for the target app and drives lsplant against it. The main app process
 * talks to it through this interface — loading an app, registering hooks, and asking it to
 * instantiate the target's entry points.
 *
 * Everything here executes in the sandbox process, where the target's code lives. No root, no
 * ptrace: the hook works because the target's bytecode is executing in our address space.
 */
interface ISandboxService {
    /**
     * Loads the target package into the sandbox. Must be called before any hook or launch.
     * Returns true on success; on failure the out-parameter exception holds the cause.
     */
    boolean loadPackage(String packageName, out ParcelableException exception);

    /**
     * Loads an APK by absolute file path (for APKs that aren't installed).
     */
    boolean loadApk(String apkPath, out ParcelableException exception);

    /**
     * Initializes lsplant in the sandbox process. Required before hooks can be applied.
     */
    boolean initHookEngine(out ParcelableException exception);

    /**
     * Registers a method hook against the loaded target. The method is located by its fully
     * qualified class name, method name, and JVM-erased parameter type descriptors (e.g.
     * "(Ljava/lang.String;I)" — matching Class.getName() output per parameter).
     *
     * The replacement logic is expressed as a built-in rule id (see HookRule) plus a string
     * argument, because Binder can't carry arbitrary lambdas. Custom replacements are added by
     * subclassing the sandbox service in a future iteration.
     */
    HookResult hookMethod(String className, String methodName, in String[] paramTypeNames,
                          int ruleId, String ruleArg);

    /** Removes a previously-installed hook. */
    boolean unhookMethod(String className, String methodName, in String[] paramTypeNames);

    /** Lists the methods currently hooked in this session. */
    List<String> listHookedMethods();

    /**
     * Lists the declared methods of [className] in the loaded target. Each entry is a
     * pipe-delimited record: "name|paramType1,paramType2|returnType|isStatic". Used by the hook
     * configuration UI to let the user pick a target method without typing signatures by hand.
     */
    List<String> listClassMethods(String className);

    /**
     * Lists the classes in the loaded target whose name contains [query] (case-insensitive).
     * Returns at most [limit] results. Used for the class-search box in the hook config UI.
     */
    List<String> searchClasses(String query, int limit);

    /**
     * Instantiates the target's Application class (if any) and calls its onCreate, so the
     * target's static initializers and content providers run as if it were launching. This is
     * the "analysis mode" entry point — the target's code runs under our hooks.
     */
    boolean startTargetApplication(out ParcelableException exception);

    /** Tears down the session: unhooks everything and drops the ClassLoader. */
    void destroy();

    /**
     * Registers a listener that receives hook activity (logged calls, hook errors) in real time.
     * Pass null to unregister. The listener's onHookLog is called from the sandbox process.
     */
    void setHookLogListener(IHookLogListener listener);
}
