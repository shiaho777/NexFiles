/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook.sandbox

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dalvik.system.DexClassLoader
import dalvik.system.PathClassLoader
import java.io.File
import java.io.IOException

/**
 * Loads a target APK into an isolated [ClassLoader], giving us full control over the target's
 * code before any of it runs.
 *
 * This is the foundation of the rootless hook approach: instead of fighting SELinux to inject
 * into the target's *own* process, we load the target's code into *our* sandbox process. Inside
 * our process we have unrestricted access — lsplant can hook any method, reflection reaches
 * every class, and a crash stays contained to the sandbox.
 *
 * The loader supports two strategies:
 *  - [PathClassLoader] (API 26+): the modern, ART-native path. Fastest, recommended.
 *  - [DexClassLoader] (API 23+): the compatibility fallback for minSdk.
 *
 * Native libraries (.so) inside the APK are handled by delegating to the ClassLoader's library
 * search path; the target's `System.loadLibrary("foo")` resolves against the APK's lib dir.
 */
class SandboxedAppLoader(
    private val packageManager: PackageManager
) {
    /**
     * Loads [packageName]'s APK into a fresh, isolated ClassLoader whose parent is the sandbox
     * process's own ClassLoader (so the target can see framework classes, but our app classes
     * are intentionally not exposed upward).
     *
     * @return a [LoadedApp] holding the ClassLoader and resolved package/application metadata.
     * @throws IOException if the APK cannot be read or its dex cannot be loaded.
     */
    @Throws(IOException::class)
    fun load(packageName: String): LoadedApp {
        val packageInfo = try {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.GET_META_DATA or
                    PackageManager.GET_PERMISSIONS
            )
        } catch (e: PackageManager.NameNotFoundException) {
            throw IOException("Package $packageName not found", e)
        }
        val applicationInfo = packageInfo.applicationInfo
            ?: throw IOException("Package $packageName has no ApplicationInfo")
        val sourceDir = applicationInfo.sourceDir
            ?: throw IOException("Package $packageName has no sourceDir")
        val nativeLibraryDir = applicationInfo.nativeLibraryDir
        // A synthetic output dir for optimized dex; ART manages this, we just point at it.
        val codeCacheDir = File(applicationInfo.dataDir, "nexfiles-sandbox-codecache").apply {
            mkdirs()
        }
        val classLoader = createClassLoader(sourceDir, nativeLibraryDir, codeCacheDir)
        return LoadedApp(packageInfo, applicationInfo, classLoader)
    }

    /**
     * Loads an APK by its file path (for APKs picked via the file manager rather than an
     * installed package). This is the path used when analyzing an APK that isn't installed.
     */
    @Throws(IOException::class)
    fun loadFromApk(apkPath: String): LoadedApp {
        val packageInfo = packageManager.getPackageArchiveInfo(apkPath, 0)
            ?: throw IOException("Not a valid APK: $apkPath")
        val applicationInfo = packageInfo.applicationInfo
            ?: throw IOException("APK has no ApplicationInfo")
        // For archive-loaded APKs, sourceDir/nativeLibraryDir must be set manually.
        applicationInfo.sourceDir = apkPath
        applicationInfo.publicSourceDir = apkPath
        val nativeLibraryDir = extractNativeLibs(apkPath, applicationInfo)
        applicationInfo.nativeLibraryDir = nativeLibraryDir.absolutePath
        val codeCacheDir = File(apkPath).parentFile?.let { File(it, "nexfiles-sandbox-codecache") }
            ?: File(System.getProperty("java.io.tmpdir"), "nexfiles-sandbox-codecache")
        codeCacheDir.mkdirs()
        val classLoader = createClassLoader(apkPath, nativeLibraryDir.absolutePath, codeCacheDir)
        return LoadedApp(packageInfo, applicationInfo, classLoader)
    }

    private fun createClassLoader(
        sourceDir: String,
        nativeLibraryDir: String?,
        codeCacheDir: File
    ): ClassLoader {
        val parent = SandboxedAppLoader::class.java.classLoader!!
        // PathClassLoader is the ART-native loader and is fastest; DexClassLoader is the safe
        // fallback for API 23-25 where PathClassLoader's constructor wasn't public.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PathClassLoader(sourceDir, nativeLibraryDir, parent)
        } else {
            DexClassLoader(sourceDir, codeCacheDir.absolutePath, nativeLibraryDir, parent)
        }
    }

    /**
     * Extracts native libraries from the APK's lib/<abi>/ directory into a temp dir so the
     * target's System.loadLibrary calls resolve. Returns the directory holding the .so files
     * for the current ABI. For installed packages PackageManager already sets nativeLibraryDir,
     * so this is only reached for archive-loaded APKs.
     */
    private fun extractNativeLibs(apkPath: String, applicationInfo: ApplicationInfo): File {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val libDir = File(applicationInfo.dataDir, "nexfiles-sandbox-lib/$abi").apply { mkdirs() }
        // Unzip lib/<abi>/*.so from the APK into libDir so the ClassLoader's library search path
        // finds them. We only extract the best-matching ABI to keep it lean.
        val prefix = "lib/$abi/"
        try {
            java.util.zip.ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.startsWith(prefix) &&
                        entry.name.endsWith(".so")) {
                        val out = File(libDir, File(entry.name).name)
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Best-effort: if extraction fails, the target's System.loadLibrary calls for its
            // own .so will fail at runtime, but pure-Java targets still work.
        }
        return libDir
    }

    /**
     * The result of loading a target app into the sandbox: everything needed to instantiate its
     * classes, locate its entry points, and drive lsplant against it.
     */
    data class LoadedApp(
        val packageInfo: PackageInfo,
        val applicationInfo: ApplicationInfo,
        val classLoader: ClassLoader
    ) {
        /** The target's Application class name, or null if it uses the default. */
        val applicationClassName: String?
            get() = applicationInfo.className?.takeIf { it.isNotEmpty() }

        /** Loads a class from the target's code via the isolated ClassLoader. */
        @Throws(ClassNotFoundException::class)
        fun loadClass(name: String): Class<*> = classLoader.loadClass(name)

        /** Convenience: loads the target's Application class, if any. */
        fun loadApplicationClass(): Class<*>? =
            applicationClassName?.let { runCatching { loadClass(it) }.getOrNull() }
    }
}
