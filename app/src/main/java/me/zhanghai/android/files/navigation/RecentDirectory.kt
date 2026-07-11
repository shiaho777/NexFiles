/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.os.Parcelable
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.util.ParcelableParceler

/**
 * A directory the user has navigated into recently, surfaced in the navigation drawer for one-tap
 * return. Unlike a [BookmarkDirectory] (which the user curates), recent entries are recorded
 * automatically as the user browses and age out past [RecentDirectories.MAX_ENTRIES].
 */
@Parcelize
data class RecentDirectory(
    val path: @WriteWith<ParcelableParceler> Path,
    val lastAccessed: Long
) : Parcelable {
    val name: String
        get() = path.name
}
