/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook.sandbox

import android.app.Activity
import android.app.ActivityThread
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import me.zhanghai.android.files.hiddenapi.HiddenApi
import java.lang.reflect.Field

/**
 * Launches a target app's Activity inside the sandbox process, making its UI visible.
 *
 * This upgrades the sandbox from "analysis mode" (Application.onCreate only) to "run mode"
 * (actual Activity display). The challenge is that the framework's [ActivityThread] drives
 * Activity creation through its [Instrumentation], which by default uses the sandbox's own
 * (empty) ClassLoader and Resources. We need it to use the *target's* ClassLoader and the
 * target's APK resources instead.
 *
 * Approach: we replace the ActivityThread's Instrumentation with a [SandboxedInstrumentation]
 * that knows about the loaded target. When the framework asks it to create an Activity, it
 * loads the class from the target's ClassLoader and binds the target's resources. This is the
 * same technique VirtualApp/Atlas use, but distilled to its essence.
 *
 * Reflection into ActivityThread internals requires [HiddenApi.disableHiddenApiChecks], which
 * the app already calls at startup (and the root service re-affirms).
 */
class SandboxedActivityLauncher(
    private val loadedApp: SandboxedAppLoader.LoadedApp,
    private val context: Context
) {
    private var originalInstrumentation: Instrumentation? = null

    /**
     * Installs the sandboxed Instrumentation into the current ActivityThread, so subsequent
     * Activity launches use the target's ClassLoader. Idempotent — calling twice restores then
     * re-installs.
     */
    fun install(): Boolean {
        if (originalInstrumentation != null) return true
        HiddenApi.disableHiddenApiChecks()
        val thread = currentActivityThread() ?: run {
            Log.e(TAG, "ActivityThread.currentActivityThread() returned null")
            return false
        }
        val field = findInstrumentationField(thread) ?: run {
            Log.e(TAG, "Could not locate mInstrumentation field on ActivityThread")
            return false
        }
        field.isAccessible = true
        originalInstrumentation = field.get(thread) as? Instrumentation
        val sandboxed = SandboxedInstrumentation(loadedApp, originalInstrumentation!!)
        field.set(thread, sandboxed)
        Log.i(TAG, "Sandboxed Instrumentation installed")
        return true
    }

    /**
     * Restores the original Instrumentation. Call when tearing down the sandbox session.
     */
    fun uninstall() {
        val original = originalInstrumentation ?: return
        val thread = currentActivityThread() ?: return
        val field = findInstrumentationField(thread) ?: return
        field.isAccessible = true
        field.set(thread, original)
        originalInstrumentation = null
        Log.i(TAG, "Original Instrumentation restored")
    }

    /**
     * Starts the target's main launcher Activity. Returns true if the launch was dispatched
     * (the Activity appears asynchronously via the framework's normal lifecycle).
     */
    fun startMainActivity(): Boolean {
        val mainActivity = findMainActivity() ?: run {
            Log.e(TAG, "No launchable Activity found in target")
            return false
        }
        val intent = Intent().apply {
            component = ComponentName(loadedApp.packageInfo.packageName, mainActivity)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Log.i(TAG, "Launched target main Activity: $mainActivity")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Activity: $mainActivity", e)
            false
        }
    }

    /**
     * Finds the target's main launcher Activity name by reading its manifest's launcher Intent.
     */
    private fun findMainActivity(): String? {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(loadedApp.packageInfo.packageName)
        return intent?.component?.className
    }

    companion object {
        private const val TAG = "SandboxedActivityLaunch"

        private fun currentActivityThread(): Any? = try {
            val method = ActivityThread::class.java.getDeclaredMethod("currentActivityThread")
            method.isAccessible = true
            method.invoke(null)
        } catch (e: Exception) {
            Log.e(TAG, "currentActivityThread reflection failed", e)
            null
        }

        private fun findInstrumentationField(thread: Any): Field? {
            // The field is named mInstrumentation on all Android versions; we walk the hierarchy
            // in case a vendor subclass shadows it.
            var klass: Class<*>? = thread.javaClass
            while (klass != null) {
                try {
                    return klass.getDeclaredField("mInstrumentation")
                } catch (e: NoSuchFieldException) {
                    klass = klass.superclass
                }
            }
            return null
        }
    }
}

/**
 * An [Instrumentation] that creates Activities from the target app's ClassLoader and binds the
 * target's resources, while delegating all lifecycle callbacks to the real (framework) Instrumentation.
 *
 * We only override the two methods the framework calls during Activity creation:
 *  - [newActivity]: load the class from the target's isolated ClassLoader.
 *  - [callActivityOnCreate]: attach the target's resources before calling through.
 *
 * Everything else (lifecycle, input, profiling) passes through to [delegate] unchanged.
 */
class SandboxedInstrumentation(
    private val loadedApp: SandboxedAppLoader.LoadedApp,
    private val delegate: Instrumentation
) : Instrumentation() {

    private val targetResources: Resources by lazy { createTargetResources() }

    override fun newActivity(
        cl: ClassLoader?,
        className: String?,
        intent: Intent?
    ): Activity {
        // Load from the target's ClassLoader, ignoring the framework's (which is ours).
        val clazz = loadedApp.classLoader.loadClass(className)
        return clazz.newInstance() as Activity
    }

    override fun newActivity(
        clazz: Class<*>?,
        context: Context?,
        token: IBinder?,
        application: android.app.Application?,
        intent: Intent?,
        info: ActivityInfo?,
        title: CharSequence?,
        parent: Activity?,
        id: String?,
        nonConfigurationInstances: Any?
    ): Activity {
        val targetClass = loadedApp.classLoader.loadClass(clazz?.name ?: "")
        return targetClass.newInstance() as Activity
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        // Bind target resources before onCreate so the Activity sees its own drawables/strings.
        bindTargetResources(activity)
        delegate.callActivityOnCreate(activity, icicle)
    }

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: android.os.PersistableBundle?
    ) {
        bindTargetResources(activity)
        delegate.callActivityOnCreate(activity, icicle, persistentState)
    }

    /**
     * Creates a Resources instance backed by the target APK's AssetManager, so the Activity's
     * getResources() returns the target's drawables/strings/layouts rather than ours.
     */
    private fun createTargetResources(): Resources {
        val assets = AssetManager::class.java.newInstance()
        val addAssetPath = AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)
        addAssetPath.isAccessible = true
        addAssetPath.invoke(assets, loadedApp.applicationInfo.sourceDir)
        val metrics = android.util.DisplayMetrics().apply {.setToDefaults() }
        val config = android.content.res.Configuration()
        return Resources(assets, metrics, config)
    }

    /**
     * Overrides the Activity's resources with the target's via reflection. The framework sets
     * mBase (a ContextImpl) on the Activity before onCreate; we replace the resources on that
     * ContextImpl so every getResources() call inside the Activity resolves against the target.
     */
    private fun bindTargetResources(activity: Activity) {
        try {
            val contextImpl = activity.baseContext
            val resField = contextImpl.javaClass.superclass?.getDeclaredField("mResources")
                ?: contextImpl.javaClass.getDeclaredField("mResources")
            resField.isAccessible = true
            resField.set(contextImpl, targetResources)
        } catch (e: Exception) {
            Log.w("SandboxedInstrumentation", "Could not bind target resources", e)
        }
    }

    // The following methods delegate to the framework Instrumentation so lifecycle, input, and
    // profiling behave exactly as in a normal app launch. Without these overrides, the default
    // Instrumentation implementations would throw Stub! exceptions.
    override fun onCreate(arguments: android.os.Bundle?) = delegate.onCreate(arguments)
    override fun onStart() = delegate.onStart()
    override fun onException(obj: Any?, e: Throwable?) = delegate.onException(obj, e)
    override fun sendStatus(resultCode: Int, results: android.os.Bundle?) =
        delegate.sendStatus(resultCode, results)
    override fun finish(resultCode: Int, results: android.os.Bundle?) =
        delegate.finish(resultCode, results)
    override fun getComponentName(): ComponentName = delegate.componentName
}
