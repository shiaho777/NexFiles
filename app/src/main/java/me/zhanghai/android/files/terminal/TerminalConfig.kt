/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Description of a terminal session to launch, parcelled across the Shizuku process boundary.
 *
 * [argv] is the program and its arguments — for a proot session this is `["proot", ...flags,
 * "/bin/sh"]`; for a plain shell smoke test it is `["/system/bin/sh"]`. [envp] may be null to
 * inherit the caller's environment, or an explicit array of `KEY=VALUE` strings. [rows]/[cols]
 * seed the initial winsize so the child's first TIOCGWINSZ is sane.
 */
@Parcelize
data class TerminalConfig(
    val argv: List<String>,
    val envp: List<String>?,
    val rows: Int = 24,
    val cols: Int = 80
) : Parcelable {
    companion object {
        // Bundle key for the byte[] payload in RemoteCallback output notifications.
        const val OUTPUT_KEY = "output"
    }
}
