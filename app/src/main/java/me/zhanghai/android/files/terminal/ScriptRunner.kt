/*
 * Copyright (c) NexFiles contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.Context
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.filelist.extension
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import java.io.File
import java.io.IOException

/**
 * Prepares shell scripts for execution through the Shizuku shell-uid PTY pipeline.
 *
 * The app's own uid cannot exec from its writable dirs (W^X) and cannot run binaries off
 * /sdcard (noexec), but the shell-uid process spawned via Shizuku has neither restriction.
 * Running a script therefore means exec'ing `/system/bin/sh <path>` inside that process.
 *
 * The remaining wrinkle is readability: shell uid cannot read the app's private data directory
 * (mode 0600, app uid). Scripts living on public storage run from their original path; anything
 * else (app-private files, SAF, remote providers, inside archives) is first copied to
 * the app's external files dir, which shell uid can read.
 */
object ScriptRunner {
    private val SCRIPT_EXTENSIONS = setOf("sh", "bash")
    private val SCRIPT_MIME_TYPES = setOf("application/x-sh", "text/x-shellscript")

    private const val SYSTEM_SH = "/system/bin/sh"
    private const val SCRIPT_STAGING_DIR = "scripts"

    /** Whether tapping this file can offer a "run" action: a local shell script. */
    @JvmStatic
    fun isShellScript(file: FileItem): Boolean {
        if (file.attributes.isDirectory || !file.path.isLinuxPath) {
            return false
        }
        if (file.path.isArchivePath) {
            return false
        }
        return file.extension.lowercase() in SCRIPT_EXTENSIONS ||
            file.mimeType.value.lowercase() in SCRIPT_MIME_TYPES
    }

    /**
     * Returns a script path that the shell-uid process can read: the original path for scripts
     * already on public storage, otherwise a copy under the app's external files dir. Returns
     * null (and logs) when neither is possible.
     */
    @JvmStatic
    fun prepareRunnableScript(context: Context, path: Path): Path? {
        if (isPublicStoragePath(path)) {
            return path
        }
        return try {
            val stagingDir = stagingDir(context)
            stagingDir.mkdirs()
            val fileName = path.fileName?.toString() ?: return null
            val stagedFile = File(stagingDir, "${System.currentTimeMillis()}-$fileName")
            path.newInputStream().use { inputStream ->
                stagedFile.outputStream().use { outputStream ->
                    // The staged script is small; progress reporting is pointless here.
                    inputStream.copyTo(outputStream, 0, null)
                }
            }
            // The staged file only needs to be readable by shell uid, which the default
            // 0660-with-app-group on primary external storage already satisfies; make it
            // world-readable anyway for OEM-specific FUSE behaviors.
            stagedFile.setReadable(true, false)
            java8.nio.file.Paths.get(stagedFile.absolutePath)
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    /** Removes copied scripts from the staging directory; call after a scripted run ends. */
    @JvmStatic
    fun cleanupStagedScripts(context: Context) {
        stagingDir(context).deleteRecursively()
    }

    /** The argv executed inside the Shizuku shell-uid process. */
    @JvmStatic
    fun buildArgv(scriptPath: Path): List<String> = listOf(SYSTEM_SH, scriptPath.toString())

    /**
     * Public storage lives under /storage or its historical /sdcard alias; those trees are
     * readable by shell uid. App-private and other-provider paths are not.
     */
    private fun isPublicStoragePath(path: Path): Boolean {
        val pathString = path.toAbsolutePath().toString()
        return pathString.startsWith("/storage/") || pathString == "/storage" ||
            pathString.startsWith("/sdcard") || pathString == "/sdcard"
    }

    private fun stagingDir(context: Context): File {
        val externalDir =
            context.getExternalFilesDir(null) ?: throw IOException("External storage unavailable")
        return File(externalDir, SCRIPT_STAGING_DIR)
    }
}
