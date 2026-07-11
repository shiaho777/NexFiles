/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * In-memory copy-on-write overlay for an otherwise read-only [ArchiveFileSystem]. It records the
 * user's edits (added/replaced files, deleted entries, new directories) without touching the
 * underlying archive, so browsing and editing feel immediate. The accumulated changes are applied
 * all at once when [ArchiveFileSystem.commitEdits] rebuilds the archive.
 *
 * Reads against the archive consult this layer first: a path present in [replacements] returns the
 * in-memory bytes; a path in [deletions] is reported as missing; everything else falls through to
 * the real archive. Directory listings are synthesized by merging the archive's children with any
 * overlay additions under that directory, minus deletions.
 *
 * State is guarded by the owner's lock (ArchiveFileSystem.lock); this class is not thread-safe on
 * its own.
 */
class ArchiveEditLayer {

    /** Replaced or added file contents, keyed by archive-internal path. */
    internal val replacements: MutableMap<Path, ByteArray> = LinkedHashMap()

    /** Added directories (empty in an archive, but tracked so listings include them). */
    internal val addedDirectories: MutableSet<Path> = LinkedHashSet()

    /** Paths removed from the archive, or replaced-directories emptied. */
    internal val deletions: MutableSet<Path> = LinkedHashSet()

    /** True once any mutation has been recorded, i.e. a commit would change the archive. */
    val isDirty: Boolean
        get() = replacements.isNotEmpty() || addedDirectories.isNotEmpty() || deletions.isNotEmpty()

    /** Whether [path] is reported as existing by the overlay (i.e. an addition/replacement). */
    fun hasReplacement(path: Path): Boolean = replacements.containsKey(path)

    /** Whether [path] is reported as deleted by the overlay. */
    fun isDeleted(path: Path): Boolean = deletions.contains(path)

    /** Returns the overlay bytes for [path], or null if the path isn't replaced here. */
    fun replacementInputStream(path: Path): InputStream? =
        replacements[path]?.let { ByteArrayInputStream(it) }

    /** Records a file write (create or overwrite) into the overlay. */
    fun putFile(path: Path, bytes: ByteArray) {
        replacements[path] = bytes
        deletions.remove(path)
        addedDirectories.remove(path)
    }

    /** Records a new directory at [path]. */
    fun addDirectory(path: Path) {
        addedDirectories.add(path)
        deletions.remove(path)
        replacements.remove(path)
    }

    /** Marks [path] as deleted. */
    fun delete(path: Path) {
        deletions.add(path)
        replacements.remove(path)
        addedDirectories.remove(path)
    }

    /**
     * Returns overlay-added children directly under [directory] (one level deep), so a directory
     * listing can merge them with the archive's own children.
     */
    fun addedChildren(directory: Path): List<Path> {
        val prefix = directory.toString()
        val result = mutableListOf<Path>()
        fun consider(candidate: Path) {
            val parent = candidate.parent ?: return
            if (parent == directory && !deletions.contains(candidate)) {
                result.add(candidate)
            }
        }
        replacements.keys.forEach(::consider)
        addedDirectories.forEach(::consider)
        return result.distinct()
    }

    /** Clears all recorded changes; the overlay reverts to "no edits pending". */
    fun clear() {
        replacements.clear()
        addedDirectories.clear()
        deletions.clear()
    }
}
