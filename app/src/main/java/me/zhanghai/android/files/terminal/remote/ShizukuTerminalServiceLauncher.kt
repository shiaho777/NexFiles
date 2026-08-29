/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.terminal.IRemoteTerminalService
import me.zhanghai.android.files.terminal.TerminalNative
import me.zhanghai.android.files.terminal.TerminalServiceUnavailableException
import me.zhanghai.android.files.util.lazyReflectedMethod
import rikka.shizuku.Shizuku

/**
 * Binds a [TerminalServiceInterface] inside a Shizuku-started process at **shell uid (2000)** —
 * not root. This is the project's first shell-uid Shizuku path (the existing SuiFileServiceLauncher
 * only runs when Sui/root is present). Shell uid is what lets us exec a bundled proot binary,
 * because the W^X restriction that blocks exec from app-writable dirs does not apply to a
 * shell-uid process.
 *
 * The launcher lives in the app process; [ShizukuTerminalServiceInterface] (the @Keep class below)
 * is what Shizuku instantiates remotely.
 */
object ShizukuTerminalServiceLauncher {
    private const val LOG_TAG = "ShizukuTerminal"
    const val TIMEOUT_MILLIS = 15 * 1000L

    /**
     * True when Shizuku itself is installed and its binder is alive. Distinct from
     * [isPermissionGranted]: a user may have Shizuku running but not yet authorised us.
     */
    val isShizukuAvailable: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }

    val isPermissionGranted: Boolean
        get() = if (isShizukuAvailable) {
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Throwable) {
                false
            }
        } else false

    /**
     * Requests Shizuku permission if missing. Returns true if permission is granted afterwards.
     * Must be called from a place with access to an Activity/Context for the permission dialog.
     */
    fun requestPermission(): Boolean {
        if (!isShizukuAvailable) return false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
        return runCatching {
            runBlocking<Boolean> {
                val granted = suspendCancellableCoroutine<Boolean> { cont ->
                    val listener = object : Shizuku.OnRequestPermissionResultListener {
                        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                            if (cont.isActive) {
                                cont.resume(grantResult == PackageManager.PERMISSION_GRANTED, onCancellation = null)
                            }
                        }
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    Shizuku.requestPermission(listener.hashCode())
                    cont.invokeOnCancellation {
                        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                    }
                }
                granted
            }
        }.getOrDefault(false)
    }

    /**
     * Binds the remote terminal service and returns its interface. Throws
     * [TerminalServiceUnavailableException] if Shizuku is missing or permission is denied.
     */
    @Throws(TerminalServiceUnavailableException::class)
    fun launchService(): IRemoteTerminalService {
        if (!isShizukuAvailable) {
            throw TerminalServiceUnavailableException("Shizuku is not running")
        }
        if (!isPermissionGranted) {
            throw TerminalServiceUnavailableException("Shizuku permission not granted")
        }
        return try {
            runBlocking {
                withTimeout(TIMEOUT_MILLIS) {
                    suspendCancellableCoroutine<IRemoteTerminalService> { continuation ->
                        val serviceArgs = Shizuku.UserServiceArgs(
                            ComponentName(
                                BuildConfig.APPLICATION_ID,
                                ShizukuTerminalServiceInterface::class.java.name
                            )
                        )
                            .debuggable(BuildConfig.DEBUG)
                            .daemon(false)
                            .processNameSuffix("terminal")
                            .version(BuildConfig.VERSION_CODE)
                        val connection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                                val iface = IRemoteTerminalService.Stub.asInterface(service)
                                if (continuation.isActive) continuation.resume(iface, onCancellation = null)
                            }
                            override fun onServiceDisconnected(name: ComponentName) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        TerminalServiceUnavailableException(
                                            "Terminal service disconnected"
                                        )
                                    )
                                }
                            }
                            override fun onBindingDied(name: ComponentName) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        TerminalServiceUnavailableException(
                                            "Terminal binding died"
                                        )
                                    )
                                }
                            }
                            override fun onNullBinding(name: ComponentName) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        TerminalServiceUnavailableException(
                                            "Terminal binding is null"
                                        )
                                    )
                                }
                            }
                        }
                        Shizuku.bindUserService(serviceArgs, connection)
                        continuation.invokeOnCancellation {
                            Shizuku.unbindUserService(serviceArgs, connection, true)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw TerminalServiceUnavailableException(e)
        } catch (e: InterruptedException) {
            throw TerminalServiceUnavailableException(e)
        }
    }
}

/**
 * The class Shizuku instantiates inside the remote (shell-uid) process. Its [init] block bootstraps
 * a usable [Context] via the same reflective trick as [RootFileService.main], then loads the
 * native terminal library. The class must be public, @Keep (survive R8), and have a no-arg
 * constructor — all of which it does by extending [TerminalServiceInterface] directly.
 */
@Keep
@RequiresApi(Build.VERSION_CODES.M)
class ShizukuTerminalServiceInterface : TerminalServiceInterface() {
    init {
        bootstrap()
    }

    private fun bootstrap() {
        Log.i("ShizukuTerminal", "Bootstrapping terminal service (uid=${Process.myUid()})")
        // A real Context lets us read nativeLibraryDir (for proot) and filesDir (for rootfs).
        terminalContext = createPackageContext(BuildConfig.APPLICATION_ID)
        // Load the PTY native library from the app's nativeLibraryDir.
        System.loadLibrary("terminal")
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun createPackageContext(packageName: String): Context {
        val currentActivityThread = currentActivityThreadMethod.invoke(null)
        val systemContext = getSystemContextMethod.invoke(currentActivityThread) as Context
        return systemContext.createPackageContext(
            packageName, Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
        )
    }

    private val currentActivityThreadMethod by lazyReflectedMethod(
        "android.app.ActivityThread", "currentActivityThread"
    )
    private val getSystemContextMethod by lazyReflectedMethod(
        "android.app.ActivityThread", "getSystemContext"
    )
}

/** Context of the remote terminal process, set during bootstrap. Null in the app process. */
@SuppressLint("StaticFieldLeak")
var terminalContext: Context? = null
    private set
