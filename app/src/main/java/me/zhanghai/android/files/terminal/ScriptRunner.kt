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
 * Prepares executables (shell scripts and ELF binaries) for the Shizuku shell-uid PTY pipeline.
 *
 * The app's own uid cannot exec from its writable dirs (W^X) and cannot run anything off /sdcard
 * (noexec FUSE), but the shell-uid process spawned via Shizuku is free of the W^X restriction.
 *
 * Two execution shapes:
 * - [ScriptKind.SHELL_SCRIPT]: exec `/system/bin/sh <path>` (or `su -c` for root). sh interprets
 *   the file, so no exec bit is needed — only readability. Public-storage scripts run from their
 *   original path; anything else (app-private, SAF, remote) is copied to the app's external files
 *   dir first, which shell uid can read.
 * - [ScriptKind.ELF_BINARY]: exec the file itself. /storage and the app external dir are both
 *   noexec FUSE mounts, so the binary must land on /data/local/tmp (the one writable+executable
 *   location for shell uid, same capability `adb shell` uses). Staging there requires a shell-uid
 *   copy+chmod pass — see [buildElfStageArgv] / [buildElfArgv].
 */
object ScriptRunner {
    private val SCRIPT_EXTENSIONS = setOf("sh", "bash")
    private val SCRIPT_MIME_TYPES = setOf("application/x-sh", "text/x-shellscript")

    private const val SYSTEM_SH = "/system/bin/sh"
    private const val SCRIPT_STAGING_DIR = "scripts"
    private const val ELF_TMP_DIR = "/data/local/tmp"
    private const val ELF_TMP_PREFIX = "nexfiles_script_"
    private const val ELF_MAGIC_SIZE = 4

    enum class ScriptKind {
        SHELL_SCRIPT,
        ELF_BINARY
    }

    /** Whether tapping this file can offer a "run" action: a local shell script or ELF binary. */
    @JvmStatic
    fun isShellScript(file: FileItem): Boolean {
        if (file.attributes.isDirectory || !file.path.isLinuxPath) {
            return false
        }
        if (file.path.isArchivePath) {
            return false
        }
        if (file.extension.lowercase() in SCRIPT_EXTENSIONS ||
            file.mimeType.value.lowercase() in SCRIPT_MIME_TYPES
        ) {
            return true
        }
        // Disguised binaries ship with a .sh suffix; peek at the magic so the run action is
        // offered for them too. Reading 4 bytes is cheap even over the file provider.
        return try {
            pathKind(file.path) == ScriptKind.ELF_BINARY
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Detects the execution shape of [path]. The ELF magic wins over name/MIME so a disguised
     * binary is exec'd rather than fed to sh; a plain script keeps its kind.
     */
    @JvmStatic
    fun detectKind(path: Path): ScriptKind = try {
        pathKind(path)
    } catch (e: IOException) {
        e.printStackTrace()
        ScriptKind.SHELL_SCRIPT
    }

    @Throws(IOException::class)
    private fun pathKind(path: Path): ScriptKind {
        path.newInputStream().use { inputStream ->
            val magic = ByteArray(ELF_MAGIC_SIZE)
            var read = 0
            while (read < ELF_MAGIC_SIZE) {
                val n = inputStream.read(magic, read, ELF_MAGIC_SIZE - read)
                if (n == -1) break
                read += n
            }
            if (read == ELF_MAGIC_SIZE &&
                magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
            ) {
                return ScriptKind.ELF_BINARY
            }
        }
        return ScriptKind.SHELL_SCRIPT
    }

    /**
     * Returns a script path that the shell-uid process can read: the original path for scripts
     * already on public storage, otherwise a copy under the app's external files dir. Returns
     * null (and logs) when neither is possible. ELF binaries skip app-side staging — they must
     * reach /data/local/tmp via a shell-uid copy pass instead (see [buildElfStageArgv]).
     */
    @JvmStatic
    fun prepareRunnableScript(context: Context, path: Path, kind: ScriptKind): Path? =
        when (kind) {
            ScriptKind.ELF_BINARY -> if (isPublicStoragePath(path)) path else {
                // Stage into the readable external dir; the shell-uid pass moves it onto
                // /data/local/tmp where exec is allowed.
                stageToExternal(context, path)
            }
            ScriptKind.SHELL_SCRIPT -> if (isPublicStoragePath(path)) {
                path
            } else {
                stageToExternal(context, path)
            }
        }

    private fun stageToExternal(context: Context, path: Path): Path? = try {
        val stagingDir = stagingDir(context)
        stagingDir.mkdirs()
        val fileName = path.fileName?.toString() ?: return null
        val stagedFile = File(stagingDir, "${System.currentTimeMillis()}-$fileName")
        path.newInputStream().use { inputStream ->
            stagedFile.outputStream().use { outputStream ->
                // The staged file is small; progress reporting is pointless here.
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

    /** Removes copied scripts from the staging directory; call after a scripted run ends. */
    @JvmStatic
    fun cleanupStagedScripts(context: Context) {
        stagingDir(context).deleteRecursively()
    }

    /** The argv for a shell script: `su -c` escalates to uid 0 when requested. */
    @JvmStatic
    fun buildShellArgv(scriptPath: Path, useRoot: Boolean): List<String> =
        if (useRoot) {
            listOf("su", "-c", "$SYSTEM_SH $scriptPath")
        } else {
            listOf(SYSTEM_SH, scriptPath.toString())
        }

    /**
     * One-shot shell-uid pass that moves an ELF onto /data/local/tmp and marks it executable —
     * the only writable+executable location shell uid has (same as `adb shell` running binaries).
     */
    @JvmStatic
    fun buildElfStageArgv(sourcePath: Path): List<String> {
        val tmpPath = elfTmpPath()
        val source = sourcePath.toString().replace("'", "'\\''")
        return listOf(
            SYSTEM_SH, "-c",
            "cp '$source' '$tmpPath' && chmod 755 '$tmpPath'"
        )
    }

    /** The direct-exec argv for a staged ELF binary. */
    @JvmStatic
    fun buildElfArgv(): List<String> = listOf(elfTmpPath())

    /** Libraries shipped next to the binary resolve through LD_LIBRARY_PATH. */
    @JvmStatic
    fun buildElfEnvp(): List<String> = listOf("LD_LIBRARY_PATH=/system/lib64:/vendor/lib64")

    /** Best-effort removal of our /data/local/tmp artifacts. */
    @JvmStatic
    fun buildElfCleanupArgv(): List<String> =
        listOf(SYSTEM_SH, "-c", "rm -f $ELF_TMP_DIR/$ELF_TMP_PREFIX*")

    @JvmStatic
    fun elfTmpPath(): String = "$ELF_TMP_DIR/$ELF_TMP_PREFIX${System.currentTimeMillis()}"

    /** True when the path is ours to clean in /data/local/tmp. */
    @JvmStatic
    fun isElfTmpPath(path: String): Boolean =
        path.startsWith("$ELF_TMP_DIR/$ELF_TMP_PREFIX")

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
