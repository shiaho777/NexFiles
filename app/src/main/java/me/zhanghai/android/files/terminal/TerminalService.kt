/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.os.IBinder
import android.os.RemoteException
import me.zhanghai.android.files.provider.remote.ParcelableException
import me.zhanghai.android.files.provider.remote.ParcelableObject
import me.zhanghai.android.files.terminal.remote.ShizukuTerminalServiceLauncher
import java.io.IOException

/**
 * Entry point for the terminal feature on the app side. Bridges [TerminalSession]s (the per-PTY
 * facade) to the Shizuku-hosted [IRemoteTerminalService]. Caches the remote binder and rebinds
 * lazily if it dies, mirroring the RemoteInterface<T> pattern but without the file-system-specific
 * exception type.
 */
object TerminalService {

    @Volatile
    private var remoteService: IBinder? = null
    private val deathRecipient = IBinder.DeathRecipient { remoteService = null }
    private val lock = Any()

    /** Whether Shizuku is installed, running, and we hold its permission. */
    val isAvailable: Boolean
        get() = ShizukuTerminalServiceLauncher.isShizukuAvailable &&
            ShizukuTerminalServiceLauncher.isPermissionGranted

    /** True iff Shizuku is installed and its binder is up (permission may still be missing). */
    val isShizukuInstalled: Boolean
        get() = ShizukuTerminalServiceLauncher.isShizukuAvailable

    /** Prompts for Shizuku permission if needed; returns true if granted afterwards. */
    fun ensurePermission(): Boolean = ShizukuTerminalServiceLauncher.requestPermission()

    @Throws(IOException::class)
    private fun getService(): IRemoteTerminalService {
        synchronized(lock) {
            remoteService?.let { binder ->
                // Still alive? If linkToDeath fired, remoteService was nulled; re-check.
                return IRemoteTerminalService.Stub.asInterface(binder)
            }
            val service = try {
                ShizukuTerminalServiceLauncher.launchService()
            } catch (e: TerminalServiceUnavailableException) {
                throw e
            }
            try {
                service.asBinder().linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                // Remote already dead — fall through to a retry on next call.
            }
            remoteService = service.asBinder()
            return service
        }
    }

    /**
     * Creates a new terminal session per [config]. Throws [TerminalServiceUnavailableException] if
     * Shizuku isn't ready, or [IOException] on IPC/PTY failure.
     */
    @Throws(IOException::class)
    fun createSession(config: TerminalConfig): TerminalSession {
        val service = getService()
        // The output callback is independent of the remote PTY handle (it only forwards into a
        // channel), so we build it first, hand it to the remote at creation time, then wrap the
        // returned binder together with the callback into a session.
        val sessionBuilder = TerminalSession.Builder(config.rows, config.cols)
        val exception = ParcelableException()
        val remotePty = try {
            service.createPty(ParcelableObject(config), sessionBuilder.outputCallback, exception)
        } catch (e: RemoteException) {
            throw IOException(e)
        }
        exception.value?.let { throw it } ?: throw IOException("createPty returned null")
        return sessionBuilder.build(remotePty)
    }
}
