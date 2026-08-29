/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.BackgroundActivityStarter
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.util.showToast

class PackageInstallerStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    // A receiver isn't in the foreground, so a plain startActivity is silently
                    // dropped on Android 10+ when we're backgrounded; let the starter fall back
                    // to a notification whose tap is a real user interaction.
                    BackgroundActivityStarter.startActivity(
                        confirmIntent,
                        context.getString(R.string.file_install_confirm_required_title),
                        context.getString(R.string.file_install_confirm_required_text),
                        context
                    )
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                context.showToast(context.getString(R.string.file_install_split_success))
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: context.getString(R.string.file_install_split_failed)
                context.showToast(message)
            }
        }
    }

    companion object {
        fun createIntent(): Intent =
            Intent(application, PackageInstallerStatusReceiver::class.java)
    }
}
