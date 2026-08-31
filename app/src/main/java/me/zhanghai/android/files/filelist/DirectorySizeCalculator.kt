/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
import java8.nio.file.FileVisitOption
import java8.nio.file.FileVisitResult
import java8.nio.file.FileVisitor
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.file.FileSize
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.size
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Computes and caches the recursive size of directories shown in a file list, so folders display
 * their true disk usage instead of just the directory entry's own size.
 *
 * Sizes are computed lazily and off the main thread: when the visible file list changes we submit
 * the directory paths to the shared pool, walk each subtree summing regular-file sizes, and publish
 * the results incrementally. Re-visiting a directory is cheap if it's already cached; the cache is
 * cleared on reload so stale values don't survive a refresh.
 *
 * Symbolic links are not followed (we use NOFOLLOW_LINKS), matching how the file list itself treats
 * links — a symlink's size counts as the link entry, not its target's tree.
 */
object DirectorySizeCalculator {

    // Size walks traverse entire subtrees and can run long; a dedicated single thread keeps them
    // off AsyncTask's shared pool (which the file list itself uses) and serializes walks so a
    // huge directory can't starve other pending sizes of CPU.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DirectorySizeCalculator").apply { isDaemon = true }
    }

    private val sizesMutable = mutableMapOf<Path, FileSize>()
    private val liveData = MutableLiveData<Map<Path, FileSize>>(sizesMutable.toMap())
    /** Read-only observable of the current size cache; observers get the full map on each update. */
    val sizes: LiveData<Map<Path, FileSize>> = liveData

    private val pending = mutableMapOf<Path, Future<*>>()

    /**
     * Requests size computation for every directory in [directories], skipping any already cached
     * or currently computing. Results arrive asynchronously via [sizes].
     */
    fun requestSizes(directories: List<Path>) {
        synchronized(sizesMutable) {
            for (path in directories) {
                if (sizesMutable.containsKey(path) || pending.containsKey(path)) continue
                val future = executor.submit<Unit> {
                    val size = computeSize(path)
                    // A cancelled walk must not publish; the thread's interrupt flag is the
                    // cancellation signal from clear().
                    if (!Thread.currentThread().isInterrupted) {
                        synchronized(sizesMutable) {
                            sizesMutable[path] = size
                            pending.remove(path)
                            liveData.postValue(sizesMutable.toMap())
                        }
                    }
                }
                pending[path] = future
            }
        }
    }

    /** Returns the cached size for [path], or null if not yet computed. */
    fun getCachedSize(path: Path): FileSize? = synchronized(sizesMutable) { sizesMutable[path] }

    /** Clears all cached sizes and cancels pending computations; call on list reload. */
    fun clear() {
        synchronized(sizesMutable) {
            pending.values.forEach { it.cancel(true) }
            pending.clear()
            if (sizesMutable.isNotEmpty()) {
                sizesMutable.clear()
                liveData.postValue(emptyMap())
            }
        }
    }

    private fun computeSize(directory: Path): FileSize {
        var total = 0L
        try {
            // retrofile's FileVisitOption only has FOLLOW_LINKS; an empty option set means
            // symlinks are not followed, which is what we want here.
            Files.walkFileTree(
                directory, emptySet(), Int.MAX_VALUE,
                object : FileVisitor<Path> {
                    override fun preVisitDirectory(
                        dir: Path, attrs: BasicFileAttributes
                    ): FileVisitResult = if (Thread.currentThread().isInterrupted) {
                        // Bail out of the whole walk on cancellation instead of draining the
                        // remaining subtree.
                        FileVisitResult.TERMINATE
                    } else {
                        FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path, attrs: BasicFileAttributes
                    ): FileVisitResult {
                        if (Thread.currentThread().isInterrupted) {
                            return FileVisitResult.TERMINATE
                        }
                        if (attrs.isRegularFile) {
                            total += attrs.size()
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path, exc: IOException
                    ): FileVisitResult = FileVisitResult.CONTINUE

                    override fun postVisitDirectory(
                        dir: Path, exc: IOException?
                    ): FileVisitResult = FileVisitResult.CONTINUE
                }
            )
        } catch (e: Exception) {
            // Permissions or IO issues — report what we have rather than failing the whole cache.
        }
        return FileSize(total)
    }
}
