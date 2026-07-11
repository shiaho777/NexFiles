/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import android.util.Log
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.hiddenapi.HiddenApi
import me.zhanghai.android.files.provider.FileSystemProviders
import me.zhanghai.android.files.provider.remote.RemoteFileService
import me.zhanghai.android.files.provider.remote.RemoteInterface
import me.zhanghai.android.files.util.lazyReflectedMethod

val isRunningAsRoot = Process.myUid() == 0

@SuppressLint("StaticFieldLeak")
lateinit var rootContext: Context private set

object RootFileService : RemoteFileService(
    RemoteInterface {
        if (SuiFileServiceLauncher.isSuiAvailable()) {
            SuiFileServiceLauncher.launchService()
        } else {
            LibSuFileServiceLauncher.launchService()
        }
    }
) {
    const val TIMEOUT_MILLIS = 15 * 1000L

    private val LOG_TAG = RootFileService::class.java.simpleName

    // Not actually restricted because there's no restriction when running as root.
    //@RestrictedHiddenApi
    private val activityThreadCurrentActivityThreadMethod by lazyReflectedMethod(
        "android.app.ActivityThread", "currentActivityThread"
    )
    //@RestrictedHiddenApi
    private val activityThreadGetSystemContextMethod by lazyReflectedMethod(
        "android.app.ActivityThread", "getSystemContext"
    )

    fun main() {
        Log.i(LOG_TAG, "Creating package context")
        rootContext = createPackageContext(BuildConfig.APPLICATION_ID)
        Log.i(LOG_TAG, "Installing file system providers")
        FileSystemProviders.install()
        FileSystemProviders.overflowWatchEvents = true
        // The fd fast path in RemoteSeekableByteChannel/RemoteInputStream reflects into
        // sun.nio.ch.FileChannelImpl.fd to extract a file descriptor for zero-round-trip
        // reads; ensure the non-SDK interface is reachable in this (root/shell) process too.
        HiddenApi.disableHiddenApiChecks()
    }

    private fun createPackageContext(packageName: String): Context {
        val activityThread = activityThreadCurrentActivityThreadMethod.invoke(null)
        val systemContext = activityThreadGetSystemContextMethod.invoke(activityThread) as Context
        return systemContext.createPackageContext(
            packageName, Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
        )
    }
}
