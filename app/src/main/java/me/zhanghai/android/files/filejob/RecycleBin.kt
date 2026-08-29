/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only recycle bin. When enabled (see [Settings.RECYCLE_BIN_ENABLED]), [DeleteFileJob] moves
 * local files here instead of unlinking them, so users can recover from a mistaken delete.
 *
 * Scope is deliberately limited to the local filesystem: cross-provider "move to trash" would mean
 * copy-then-delete for remote/archive paths, which is slow and failure-prone, so those still get
 * deleted permanently. This matches what users actually expect from a recycle bin.
 *
 * Layout under the app's private files dir: `<filesDir>/.nexfiles_trash/<timestamp>__<name>`. The
 * timestamp prefix disambiguates same-named deletions and sorts entries by deletion time.
 */
object RecycleBin {
    private val TRASH_DIR: Path by lazy {
        Paths.get(application.filesDir.path, ".nexfiles_trash").also {
            try {
                Files.createDirectories(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Whether moving [path] to the recycle bin is appropriate: the feature must be on and the path
     * must live on the local filesystem (the only place a cheap, atomic move is guaranteed).
     */
    fun shouldRecycle(path: Path): Boolean {
        if (!Settings.RECYCLE_BIN_ENABLED.valueCompat) {
            return false
        }
        return path.isLinuxPath
    }

    /**
     * Computes the destination path inside the recycle bin for [source], creating any collision
     * suffix needed to avoid overwriting a previous entry with the same name.
     */
    fun targetPath(source: Path): Path {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseName = source.fileName?.toString() ?: "unnamed"
        var candidate = TRASH_DIR.resolve("${timestamp}__$baseName")
        var suffix = 1
        while (Files.exists(candidate)) {
            candidate = TRASH_DIR.resolve("${timestamp}__$baseName ($suffix)")
            suffix++
        }
        return candidate
    }

    /**
     * Moves [source] into the recycle bin via a filesystem move. Returns true on success; on any
     * failure the caller should fall back to a permanent delete so the original operation still
     * completes.
     */
    fun recycle(source: Path): Boolean {
        return try {
            val target = targetPath(source)
            Files.move(source, target)
            // Stash the original parent path in a sidecar file so a future "restore" can put the
            // entry back where it came from.
            val parentKey = source.parent?.toString() ?: ""
            Files.write(Paths.get("${target.toString()}$ORIGIN_SUFFIX"), parentKey.toByteArray())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Moves [entry] back to its original parent directory. Returns the restored path on success,
     * null if the origin is unknown or the move fails (e.g. original parent gone). On a name
     * collision at the destination we append a " (restored N)" suffix rather than overwriting.
     */
    fun restore(entry: RecycleEntry): Path? {
        val originalParent = entry.originalParent ?: return null
        val parentPath = try {
            Paths.get(originalParent)
        } catch (e: Exception) {
            return null
        }
        if (!Files.isDirectory(parentPath)) {
            return null
        }
        // Recover the original file name by stripping the "<timestamp>__" prefix we added.
        val originalName = entry.name.substringAfter("__", entry.name)
        var destination = parentPath.resolve(originalName)
        var suffix = 1
        while (Files.exists(destination)) {
            destination = parentPath.resolve("$originalName (restored $suffix)")
            suffix++
        }
        return try {
            Files.move(entry.path, destination)
            val originPath = Paths.get("${entry.path.toString()}$ORIGIN_SUFFIX")
            Files.deleteIfExists(originPath)
            destination
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Permanently removes a single [entry] from the recycle bin. Returns true on success.
     */
    fun deleteEntry(entry: RecycleEntry): Boolean {
        return try {
            deleteRecursively(entry.path)
            val originPath = Paths.get("${entry.path.toString()}$ORIGIN_SUFFIX")
            Files.deleteIfExists(originPath)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Lists entries currently in the recycle bin, newest first. */
    fun listEntries(): List<RecycleEntry> {
        if (!Files.exists(TRASH_DIR)) {
            return emptyList()
        }
        val entries = mutableListOf<RecycleEntry>()
        Files.newDirectoryStream(TRASH_DIR).use { stream ->
            for (path in stream) {
                val name = path.fileName?.toString() ?: continue
                if (name.endsWith(".origin")) {
                    continue
                }
                val attributes = try {
                    path.readAttributes(BasicFileAttributes::class.java)
                } catch (e: Exception) {
                    continue
                }
                val originalParent = readOriginParent(path)
                entries.add(RecycleEntry(path, name, originalParent, attributes))
            }
        }
        return entries.sortedByDescending { it.attributes.lastModifiedTime().toMillis() }
    }

    private fun readOriginParent(trashPath: Path): String? {
        val originPath = Paths.get("${trashPath.toString()}$ORIGIN_SUFFIX")
        return try {
            String(Files.readAllBytes(originPath))
        } catch (e: Exception) {
            null
        }
    }

    /** Empties the recycle bin. Returns false if any entry could not be removed. */
    fun empty(): Boolean {
        var allSuccessful = true
        for (entry in listEntries()) {
            try {
                deleteRecursively(entry.path)
                val originPath = Paths.get("${entry.path.toString()}$ORIGIN_SUFFIX")
                Files.deleteIfExists(originPath)
            } catch (e: Exception) {
                allSuccessful = false
            }
        }
        return allSuccessful
    }

    private fun deleteRecursively(path: Path) {
        Files.walkFileTree(path, object : java8.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): java8.nio.file.FileVisitResult {
                Files.delete(file)
                return java8.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java8.nio.file.FileVisitResult {
                Files.delete(dir)
                return java8.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private const val ORIGIN_SUFFIX = ".origin"
}

data class RecycleEntry(
    val path: Path,
    val name: String,
    val originalParent: String?,
    val attributes: BasicFileAttributes
)
