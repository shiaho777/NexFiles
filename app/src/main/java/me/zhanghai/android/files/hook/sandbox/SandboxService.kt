/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook.sandbox

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import me.zhanghai.android.files.hook.HookEngine
import java.lang.reflect.Method

/**
 * The sandbox process.
 *
 * Declared in the manifest with `android:process=":sandbox"`, this service hosts the isolated
 * ClassLoader for a target app and drives lsplant against it. Because the target's code runs
 * inside *this* process, lsplant has full ArtMethod access — no root, no ptrace, no SELinux
 * fight. The hook works because we control the address space the target's bytecode executes in.
 *
 * Lifecycle:
 *  - Main process binds this service, receives an [ISandboxService] stub.
 *  - Client calls loadPackage/loadApk → [SandboxedAppLoader] builds the isolated ClassLoader.
 *  - Client calls initHookEngine → lsplant initializes in this process.
 *  - Client calls hookMethod for each target → [SandboxedHookSession] applies them.
 *  - Client calls startTargetApplication → the target's Application.onCreate runs under hooks.
 *  - Client calls destroy → everything is unhooked and the session is torn down.
 *
 * A crash in the target's code crashes only this process; the main app survives.
 */
class SandboxService : Service() {
    private var loader: SandboxedAppLoader? = null
    private var session: SandboxedHookSession? = null
    private var activityLauncher: SandboxedActivityLauncher? = null

    // Bridges HookLogDispatcher (in-process) to the IHookLogListener (over Binder to the UI).
    private val logForwarder = object : HookLogDispatcher.Listener {
        @Volatile var target: IHookLogListener? = null
        override fun onHookLog(timestamp: Long, level: String, tag: String, message: String) {
            runCatching { target?.onHookLog(timestamp, level, tag, message) }
        }
    }

    private val binder = object : ISandboxService.Stub() {

        override fun loadPackage(packageName: String, exception: ParcelableException): Boolean {
            return runSandbox(exception) {
                ensureLoader()
                val loaded = loader!!.load(packageName)
                session = SandboxedHookSession(loaded)
                Log.i(TAG, "Loaded package $packageName into sandbox; ${loaded.applicationClassName ?: "(no Application class)"}")
            }
        }

        override fun loadApk(apkPath: String, exception: ParcelableException): Boolean {
            return runSandbox(exception) {
                ensureLoader()
                val loaded = loader!!.loadFromApk(apkPath)
                session = SandboxedHookSession(loaded)
                Log.i(TAG, "Loaded APK $apkPath into sandbox")
            }
        }

        override fun initHookEngine(exception: ParcelableException): Boolean {
            return runSandbox(exception) {
                val s = session ?: error("No app loaded; call loadPackage/loadApk first")
                val ok = s.ensureInitialized()
                if (!ok) error("lsplant::Init failed (inline-hook backend available?)")
            }
        }

        override fun hookMethod(
            className: String,
            methodName: String,
            paramTypeNames: Array<String>,
            ruleId: Int,
            ruleArg: String
        ): HookResult {
            val s = session ?: return HookResult(false, null, "No app loaded")
            val rule = HookRule.fromId(ruleId)
                ?: return HookResult(false, null, "Unknown rule id $ruleId")
            val display = "${className}.${methodName}(${paramTypeNames.joinToString(",")})"
            return try {
                val loaded = s.loadedApp
                val clazz = loaded.loadClass(className)
                // Resolve parameter types through the target's ClassLoader so app-defined types
                // (e.g. com.foo.Model) resolve against the isolated loader, not ours.
                val paramTypes = paramTypeNames.map { resolveType(it, loaded.classLoader) }
                    .toTypedArray()
                val method = findMethod(clazz, methodName, paramTypes)
                method.isAccessible = true
                val replacement = hookReplacementFor(rule, ruleArg, method)
                val ok = s.hookMethod(method, replacement)
                HookResult(ok, display, if (ok) null else "lsplant::Hook returned null")
            } catch (e: Exception) {
                Log.w(TAG, "hookMethod failed for $display", e)
                HookResult(false, display, e.message)
            }
        }

        override fun unhookMethod(
            className: String,
            methodName: String,
            paramTypeNames: Array<String>
        ): Boolean {
            val s = session ?: return false
            return try {
                val loaded = s.loadedApp
                val clazz = loaded.loadClass(className)
                val paramTypes = paramTypeNames.map { resolveType(it, loaded.classLoader) }
                    .toTypedArray()
                val method = findMethod(clazz, methodName, paramTypes)
                HookEngine.uninstall(method)
            } catch (e: Exception) {
                false
            }
        }

        override fun listHookedMethods(): List<String> =
            session?.hookedMethods()?.map { "${it.declaringClass.name}.${it.name}" } ?: emptyList()

        override fun listClassMethods(className: String): List<String> {
            val s = session ?: return emptyList()
            return try {
                val clazz = s.loadedApp.loadClass(className)
                clazz.declaredMethods.map { method ->
                    val params = method.parameterTypes.joinToString(",") { it.name }
                    val isStatic = java.lang.reflect.Modifier.isStatic(method.modifiers)
                    "${method.name}|$params|${method.returnType.name}|$isStatic"
                }.sorted()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun searchClasses(query: String, limit: Int): List<String> {
            val s = session ?: return emptyList()
            val loader = s.loadedApp.classLoader
            // We can't enumerate all classes in a ClassLoader directly (no listClasses API), so
            // we parse the target's dex with dexlib2 to get the full class list, then filter.
            // This is the same library our DEX editor uses.
            return try {
                val classes = listTargetClassNames(s.loadedApp)
                val lowerQuery = query.lowercase()
                classes.asSequence()
                    .filter { it.lowercase().contains(lowerQuery) }
                    .take(if (limit > 0) limit else 100)
                    .toList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun startTargetApplication(exception: ParcelableException): Boolean {
            return runSandbox(exception) {
                val s = session ?: error("No app loaded")
                val appClass = s.loadedApp.applicationClassName
                if (appClass != null) {
                    // Instantiate and call onCreate on the target's Application class, so its
                    // static initializers and SDK init code run under our hooks.
                    val clazz = s.loadedApp.loadClass(appClass)
                    val app = clazz.getDeclaredConstructor().newInstance()
                    val onCreate = clazz.getMethod("onCreate")
                    onCreate.isAccessible = true
                    onCreate.invoke(app)
                    Log.i(TAG, "Target Application.onCreate invoked: $appClass")
                } else {
                    Log.i(TAG, "Target has no Application class; skipping")
                }
                // Install the sandboxed Instrumentation so subsequent Activity launches use the
                // target's ClassLoader and resources, then start the main launcher Activity.
                val launcher = SandboxedActivityLauncher(s.loadedApp, this@SandboxService)
                launcher.install()
                activityLauncher = launcher
                val launched = launcher.startMainActivity()
                if (!launched) {
                    Log.w(TAG, "Main Activity launch dispatched but may not have a launcher intent")
                }
            }
        }

        override fun destroy() {
            activityLauncher?.uninstall()
            activityLauncher = null
            session?.close()
            session = null
            loader = null
            HookLogDispatcher.removeListener(logForwarder)
            Log.i(TAG, "Sandbox session destroyed")
        }

        override fun setHookLogListener(listener: IHookLogListener?) {
            if (listener == null) {
                HookLogDispatcher.removeListener(logForwarder)
            } else {
                HookLogDispatcher.addListener(logForwarder)
                logForwarder.target = listener
            }
        }

        private fun ensureLoader() {
            if (loader == null) {
                loader = SandboxedAppLoader(packageManager)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        activityLauncher?.uninstall()
        activityLauncher = null
        session?.close()
        session = null
        loader = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SandboxService"

        /**
         * Enumerates all class type descriptors in the loaded target's APK by parsing its DEX
         * with dexlib2. Android's ClassLoader has no "list all classes" API, so this is the only
         * way to populate the class-search box without trial-and-error Class.forName calls.
         */
        private fun listTargetClassNames(
            loadedApp: SandboxedAppLoader.LoadedApp
        ): List<String> {
            val sourceDir = loadedApp.applicationInfo.sourceDir ?: return emptyList()
            val dexFile = java.io.File(sourceDir).inputStream().use {
                val bytes = it.readBytes()
                val version = try {
                    String(bytes, 4, 3, Charsets.US_ASCII).trim().toInt()
                } catch (e: Exception) { 35 }
                org.jf.dexlib2.dexbacked.DexBackedDexFile(
                    org.jf.dexlib2.Opcodes.forDexVersion(version), bytes
                )
            }
            return dexFile.classes.map { it.type }
                // Convert Lcom/foo/Bar; → com.foo.Bar for display/search.
                .mapNotNull { descriptor ->
                    if (descriptor.startsWith('L') && descriptor.endsWith(';')) {
                        descriptor.substring(1, descriptor.length - 1).replace('/', '.')
                    } else null
                }
                .sorted()
        }
    }
}

/**
 * Runs [block] and reports success/failure through the [exception] out-parameter. Returns true
 * on success. This keeps the AIDL methods concise while giving the caller a clean error path.
 */
private inline fun runSandbox(exception: ParcelableException, block: () -> Unit): Boolean {
    return try {
        block()
        true
    } catch (e: Throwable) {
        Log.e("SandboxService", "operation failed", e)
        exception.className = e.javaClass.name
        exception.message = e.message
        false
    }
}

/**
 * Resolves a JVM type name (as returned by [Class.getName]) against [classLoader], including
 * primitives and arrays. Used to rebuild the parameter-type array for reflection lookups.
 */
private fun resolveType(name: String, classLoader: ClassLoader): Class<*> {
    // Primitives first; Class.forName can't handle them.
    PRIMITIVES[name]?.let { return it }
    return Class.forName(name, false, classLoader)
}

private val PRIMITIVES = mapOf(
    "boolean" to Boolean::class.javaPrimitiveType!!,
    "int" to Int::class.javaPrimitiveType!!,
    "long" to Long::class.javaPrimitiveType!!,
    "float" to Float::class.javaPrimitiveType!!,
    "double" to Double::class.javaPrimitiveType!!,
    "byte" to Byte::class.javaPrimitiveType!!,
    "short" to Short::class.javaPrimitiveType!!,
    "char" to Char::class.javaPrimitiveType!!,
    "void" to Void::class.javaPrimitiveType!!
)

/**
 * Finds a method by name and parameter types, searching the class hierarchy (declared methods
 * include inherited ones once we walk superclasses). This is more forgiving than
 * Class.getDeclaredMethod when the method is declared on a superclass.
 */
private fun findMethod(clazz: Class<*>, name: String, paramTypes: Array<Class<*>>): Method {
    var current: Class<*>? = clazz
    while (current != null) {
        try {
            return current.getDeclaredMethod(name, *paramTypes)
        } catch (e: NoSuchMethodException) {
            current = current.superclass
        }
    }
    // Last resort: search interfaces.
    for (iface in clazz.interfaces) {
        try {
            return iface.getMethod(name, *paramTypes)
        } catch (e: NoSuchMethodException) {
            continue
        }
    }
    throw NoSuchMethodException("${clazz.name}.$name(${paramTypes.joinToString(",") { it.name }})")
}
