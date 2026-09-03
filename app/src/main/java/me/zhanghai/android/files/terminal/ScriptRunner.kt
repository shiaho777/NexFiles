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
    private val KERNEL_MODULE_EXTENSIONS = setOf("ko", "kpm", "o")

    private const val SYSTEM_SH = "/system/bin/sh"
    private const val SCRIPT_STAGING_DIR = "scripts"
    private const val FAKE_BIN_DIR = "fakebin"
    private const val ELF_TMP_DIR = "/data/local/tmp"
    private const val ELF_TMP_PREFIX = "nexfiles_script_"
    private const val ELF_MAGIC_SIZE = 4
    private const val MAX_SCAN_BYTES = 512 * 1024

    // Kernel-level operations: these fail without real uid 0 regardless of any userland
    // disguise (proot, a fake id), because the kernel checks the caller's capability.
    private val KERNEL_PATTERNS = listOf(
        Regex("""\binsmod\b"""), Regex("""\brmmod\b"""), Regex("""\bmodprobe\b"""),
        Regex("""\bmount\b"""),
        Regex("""\bsetenforce\b"""), Regex("""\bgetenforce\b"""),
        Regex("""\breboot\b"""),
        Regex("""\bmkfs\b"""), Regex("""\bdd\s+.*of=/dev/"""),
        Regex("""\becho\s+[^>]*>\s*/dev/"""),
        Regex("""chmod\s+.*\s+/dev/"""),
        Regex("""\bwifi\b.*\bmac\b|\bmacchan""")
    )

    // A uid check alone (the classic gate at the top of root scripts) is spoofable: proot's
    // --root-id makes `id -u` report 0 inside the session, so scripts whose only root dependency
    // is this check run fine without root.
    private val ID_CHECK_PATTERN = Regex("""id\s+-u|EUID|\$\{UID\}|[$]UID""")

    enum class ScriptKind {
        SHELL_SCRIPT,
        ELF_BINARY
    }

    /**
     * How much a shell script depends on real root, judged by scanning its text. The levels
     * decide what the run dialog promises: NONE and ID_CHECK run anywhere; ID_CHECK can also be
     * satisfied by proot's --root-id; KERNEL requirements fail without real uid 0 no matter how
     * the process is dressed up.
     */
    enum class RootRequirement {
        NONE,
        ID_CHECK,
        KERNEL
    }

    /**
     * Scans a shell script for root dependencies. Only the script text is inspected (cheap, no
     * execution); a script can still hide a root requirement behind obfuscation, in which case
     * the run itself surfaces it.
     */
    @JvmStatic
    fun inspectRootRequirement(path: Path): RootRequirement {
        val text = try {
            path.newInputStream().use { inputStream ->
                // Scripts needing analysis are small; cap to keep a pathological file cheap.
                val bytes = inputStream.readBytes()
                String(bytes, 0, bytes.size.coerceAtMost(MAX_SCAN_BYTES), Charsets.UTF_8)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return RootRequirement.NONE
        }
        var requirement = RootRequirement.NONE
        for (pattern in KERNEL_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                return RootRequirement.KERNEL
            }
        }
        if (ID_CHECK_PATTERN.containsMatchIn(text)) {
            requirement = RootRequirement.ID_CHECK
        }
        return requirement
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
     * Whether the long-press menu should offer the kernel-module viewer: a local .ko/.kpm/.o
     * by extension, or any local file that sniffs as ELF (relocatable or not — the viewer only
     * reads structure, so misroutes are harmless).
     */
    @JvmStatic
    fun isKernelModule(file: FileItem): Boolean {
        if (file.attributes.isDirectory || !file.path.isLinuxPath || file.path.isArchivePath) {
            return false
        }
        if (file.extension.lowercase() in KERNEL_MODULE_EXTENSIONS) {
            return true
        }
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
     * Writes a fake `id` into the app's external staging dir. Scripts gated by a bare
     * `id -u`/`$UID` check run fine when that check sees 0 — verified on-device (API29):
     * a PATH-injected fake id makes the classic root gate report `uid=0`. Real root-only
     * operations (insmod & friends) still fail at the kernel; this only satisfies the gate.
     */
    @JvmStatic
    fun prepareFakeIdBin(context: Context): Path? = try {
        val fakeBinDir = File(stagingDir(context), FAKE_BIN_DIR)
        fakeBinDir.mkdirs()
        val fakeId = File(fakeBinDir, "id")
        fakeId.writeText(
            """
            #!/system/bin/sh
            case "${'$'}*" in
              *-u*) echo 0 ;;
              *) echo "uid=0(root) gid=0(root) groups=0(root)" ;;
            esac
            """.trimIndent()
        )
        fakeId.setReadable(true, false)
        fakeId.setExecutable(true, false)
        java8.nio.file.Paths.get(fakeId.absolutePath)
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }

    /**
     * The argv + envp pair for a script run with the fake id on PATH: the script's `id -u`
     * gate sees uid 0 and proceeds. Everything else runs as the real (shell) user.
     */
    @JvmStatic
    fun buildSpoofedShellArgv(scriptPath: Path): List<String> =
        listOf(SYSTEM_SH, scriptPath.toString())

    @JvmStatic
    fun buildSpoofedEnvp(fakeIdDir: Path): List<String> = listOf(
        "PATH=$fakeIdDir:/product/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/sbin"
    )

    /** Removes the fake id binary; call when the scripted run ends. */
    @JvmStatic
    fun cleanupFakeIdBin(context: Context) {
        File(stagingDir(context), FAKE_BIN_DIR).deleteRecursively()
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
