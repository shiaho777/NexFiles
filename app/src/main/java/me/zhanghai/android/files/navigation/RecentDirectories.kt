/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import java8.nio.file.Path
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * Tracks the directories the user visits, kept most-recent-first and capped at [MAX_ENTRIES].
 * Recording is idempotent (re-visiting a directory just bumps its timestamp) and the root path is
 * excluded — it's already reachable via the storage entry, so listing it adds noise.
 */
object RecentDirectories {

    const val MAX_ENTRIES = 8

    /** Current recent list, newest first. */
    val list: List<RecentDirectory>
        get() = Settings.RECENT_DIRECTORIES.valueCompat

    /**
     * Records a visit to [path], moving it to the front (or inserting it) and trimming to the cap.
     * No-op for the filesystem root or for null/empty paths.
     */
    fun record(path: Path) {
        val name = path.fileName
        if (name == null || name.toString().isEmpty()) return
        val now = System.currentTimeMillis()
        val current = Settings.RECENT_DIRECTORIES.valueCompat.toMutableList()
        // Drop any existing entry for this exact path so we don't duplicate when bumping.
        current.removeAll { it.path == path }
        current.add(0, RecentDirectory(path, now))
        // Trim to the cap, keeping the most recent.
        val trimmed = if (current.size > MAX_ENTRIES) current.subList(0, MAX_ENTRIES).toList()
                      else current
        Settings.RECENT_DIRECTORIES.putValue(trimmed)
    }

    /** Removes a single entry by path. */
    fun remove(path: Path) {
        val current = Settings.RECENT_DIRECTORIES.valueCompat
        val updated = current.filterNot { it.path == path }
        if (updated.size != current.size) {
            Settings.RECENT_DIRECTORIES.putValue(updated)
        }
    }

    /** Clears all recent entries. */
    fun clear() {
        Settings.RECENT_DIRECTORIES.putValue(emptyList())
    }
}
