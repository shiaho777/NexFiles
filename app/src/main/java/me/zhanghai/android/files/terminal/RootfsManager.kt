/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.os.Build
import me.zhanghai.android.files.app.application
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Manages on-device rootfs tarballs for [TerminalDistro]: download (with progress + optional
 * SHA-256 verification), extraction into the proot root directory, and deletion. Everything lives
 * under the app's private files dir so we never need external storage permissions.
 *
 * The flow returned by [install] emits [InstallProgress] for UI binding; it completes when the
 * rootfs is ready or throws on any failure.
 */
object RootfsManager {

    /** Base directory for everything terminal-related under filesDir. */
    val baseDir: File = File(application.filesDir, "terminal").apply { mkdirs() }

    /** Where a given distro's extracted rootfs lives (the path proot mounts as `/`). */
    fun rootfsDir(distro: TerminalDistro): File = File(baseDir, "rootfs-${distro.id}")

    /** True once an extracted rootfs is present (a marker file, not a full validity check). */
    fun isInstalled(distro: TerminalDistro): Boolean =
        File(rootfsDir(distro), distro.initCommand).exists()

    /** Removes a distro's rootfs. Safe to call when not installed. */
    fun uninstall(distro: TerminalDistro): Boolean = rootfsDir(distro).deleteRecursively()

    /**
     * Downloads and extracts [distro]. Emits progress; completes when the rootfs is usable.
     * Cancellation propagates: a cancelled coroutine deletes the partial rootfs.
     */
    fun install(distro: TerminalDistro): Flow<InstallProgress> = flow {
        emit(InstallProgress.Connecting)
        val targetDir = rootfsDir(distro)
        // Download to a temp file first; only extract on a complete, verified download.
        val archiveFile = File(baseDir, "${distro.id}.tar.partial")
        try {
            downloadTo(distro, archiveFile) { downloaded, total ->
                emit(InstallProgress.Downloading(downloaded, total))
            }
            emit(InstallProgress.Extracting)
            extractTo(archiveFile, targetDir)
            // Only mark complete by leaving the marker file (initCommand) in place — extraction
            // already wrote it. Drop the archive to save space.
            archiveFile.delete()
            emit(InstallProgress.Done(targetDir))
        } catch (e: Throwable) {
            archiveFile.delete()
            // Clean up a half-extracted rootfs so the next attempt starts fresh.
            targetDir.deleteRecursively()
            throw e
        }
    }

    private suspend fun downloadTo(
        distro: TerminalDistro,
        target: File,
        onProgress: suspend (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val connection = (URL(distro.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NexFiles/${application.packageName}")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Download failed: HTTP $responseCode")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            val digest = if (distro.sha256.isNotEmpty()) MessageDigest.getInstance("SHA-256") else null
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            if (digest != null) {
                val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHex.equals(distro.sha256, ignoreCase = true)) {
                    throw IOException("Checksum mismatch (expected ${distro.sha256}, got $actualHex)")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun extractTo(archive: File, target: File) = withContext(Dispatchers.IO) {
        target.mkdirs()
        archive.inputStream().buffered().use { raw ->
            // Pick the decompressor by extension. .tar.gz -> gzip, .tar.xz -> xz; both feed a
            // tar stream. commons-compress auto-detection would also work but being explicit
            // avoids a dependency on its detector format registry.
            val decompressed = when {
                archive.name.endsWith(".gz") -> GzipCompressorInputStream(raw)
                archive.name.endsWith(".xz") -> XZCompressorInputStream(raw)
                else -> raw
            }
            TarArchiveInputStream(decompressed).use { tar ->
                var entry: TarArchiveEntry? = tar.nextTarEntry
                while (entry != null) {
                    coroutineContext.ensureActive()
                    extractEntry(entry, tar, target)
                    entry = tar.nextTarEntry
                }
            }
        }
    }

    private fun extractEntry(
        entry: TarArchiveEntry,
        tar: TarArchiveInputStream,
        target: File
    ) {
        // Guard against path traversal — an entry resolving outside target is skipped.
        val outFile = File(target, entry.name).canonicalFile
        if (!outFile.path.startsWith(target.canonicalFile.path)) {
            return
        }
        if (entry.isDirectory) {
            outFile.mkdirs()
            return
        }
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { out ->
            // Buffered copy via the tar stream's read() (which honours the entry size).
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = tar.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
            }
        }
        // Preserve the executable bit so /bin/sh, /usr/bin/env, etc. work.
        if (entry.mode and 0b001_001_001 != 0) {
            outFile.setExecutable(true, false)
        }
    }

    /**
     * Builds the proot argv for launching [distro] with the current terminal size, ready to pass
     * to [TerminalService.createSession]. The proot binary itself is expected at [prootBinaryPath]
     * (packaged by the app; see PROOT_BINARY_NAME).
     */
    fun prootArgv(
        distro: TerminalDistro,
        prootBinaryPath: String,
        rows: Int,
        cols: Int,
        extraBindings: List<String> = listOf("/sdcard"),
        initialCommand: String? = null
    ): List<String> {
        val argv = mutableListOf(
            prootBinaryPath,
            "--rootfs=${rootfsDir(distro).absolutePath}",
            "--root-id",            // Fake uid 0 so apt/dpkg don't refuse.
            "--link2symlink",       // Avoid hardlink errors across bind mounts.
            "--kill-on-exit",       // Reap the child if proot dies.
            "--term=${if (rows > 0) rows else 24},${if (cols > 0) cols else 80}",
            "/usr/bin/env",
            "TERM=xterm-256color",
            "PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME=/root",
            "LANG=C.UTF-8"
        )
        for (binding in extraBindings) {
            argv += "--bind=$binding"
        }
        argv += distro.initCommand
        if (initialCommand != null) {
            argv += "-c"
            argv += initialCommand
        }
        return argv
    }

    /**
     * Resolves the proot binary path for the current ABI. The binary is shipped as a jniLibs
     * entry (see PROOT_BINARY_NAME) so it lands in nativeLibraryDir, which is not app-writable
     * and thus exec'd freely even under W^X. Other ABIs are unsupported and surface as null.
     */
    fun prootBinaryPath(): String? {
        // We ship only arm64-v8a for now; other ABIs would need their own cross-compiled proot.
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) return null
        val nativeDir = application.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, PROOT_BINARY_NAME)
        return if (binary.exists() && binary.canExecute()) binary.absolutePath else null
    }

    /** Sealed progress type so the UI can render connecting/downloading/extracting distinctly. */
    sealed class InstallProgress {
        object Connecting : InstallProgress()
        data class Downloading(val downloaded: Long, val total: Long) : InstallProgress()
        object Extracting : InstallProgress()
        data class Done(val rootfsDir: File) : InstallProgress()
    }

    const val PROOT_BINARY_NAME = "libproot.so"
}
