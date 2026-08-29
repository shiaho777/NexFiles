/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.appcompat.widget.Toolbar
import java8.nio.file.Path

interface FileListFragmentHost {
    val hasSw600Dp: Boolean

    val isLandscape: Boolean

    val isDualPane: Boolean

    fun setSupportToolbar(toolbar: Toolbar)

    fun invalidateOptionsMenu()

    fun setTitle(title: CharSequence)

    fun finish()

    fun openDrawer()

    fun closeDrawer()

    fun isDrawerOpen(): Boolean

    fun isPersistentDrawerOpen(): Boolean

    fun requestActivePane(pane: FileListFragment)

    fun swapPanes()

    fun openInOtherPane(path: Path)
}
