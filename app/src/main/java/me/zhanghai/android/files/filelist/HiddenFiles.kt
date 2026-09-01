/*
 * Copyright (c) NexFiles contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.newInputStream

/**
 * Per-directory cache of names listed in a `.hidden` file (the Nautilus/GIO convention: a plain
 * text file in the directory, one name per line, whose entries are hidden in file listings). The
 * file itself is also treated as hidden. Names are re-read on every directory load so edits to the
 * file show up on refresh.
 *
 * @see <a href="https://help.gnome.org/users/gthumb/unstable/gthumb-hidden-files.html">GIO .hidden</a>
 */
object HiddenFiles {
    /**
     * Returns the names to hide for [directory], or an empty set when the directory has no
     * `.hidden` file. A read failure is reported as "no hidden names" — the listing must not break
     * because of an unreadable metadata file.
     */
    @JvmStatic
    fun namesFor(directory: Path): Set<String> = try {
        readNames(directory)
    } catch (e: Exception) {
        e.printStackTrace()
        emptySet()
    }

    private fun readNames(directory: Path): Set<String> {
        val hiddenFile = directory.resolve(DOT_HIDDEN)
        if (!hiddenFile.exists()) {
            return emptySet()
        }
        return try {
            hiddenFile.newInputStream().use { inputStream ->
                inputStream.readBytes().toString(Charsets.UTF_8)
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            } + DOT_HIDDEN
        } catch (e: Exception) {
            e.printStackTrace()
            emptySet()
        }
    }

    private const val DOT_HIDDEN = ".hidden"
}
