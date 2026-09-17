/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java8.nio.file.FileVisitResult
import java8.nio.file.FileVisitor
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.file.FileSize
import java.io.IOException
import java.util.concurrent.Executors


/** Shared, bounded cache and worker pool; each pane owns its viewport demand independently. */
object DirectorySizeCalculator {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "DirectorySizeCalculator").apply { isDaemon = true }
    }
    private val scheduler = DirectorySizeScheduler<Path, FileSize>(
        executor = executor,
        postDelayed = { delay, block -> mainHandler.postDelayed({ block() }, delay) },
        compute = ::computeSize
    )

    /** Owned by one ViewModel. All UI-facing methods are called on the main thread. */
    class Owner {
        private val liveData = MutableLiveData<Map<Path, FileSize>>(emptyMap())
        val sizes: LiveData<Map<Path, FileSize>> = liveData

        init {
            scheduler.register(this) { liveData.value = it }
        }

        fun request(directories: List<Path>) = scheduler.request(this, directories)

        /** Release current demand on navigation/view teardown, retaining the bounded shared cache. */
        fun reset() = scheduler.reset(this)

        /** Refresh only the current subtree's cached sizes, never canceling another pane's work. */
        fun invalidate(directory: Path) = scheduler.reset(this) { it.startsWith(directory) }

        fun close() = scheduler.release(this)
    }

    /** Sum regular files without following symlinks. Unreadable entries retain best-effort totals. */
    private fun computeSize(
        directory: Path, cancellation: DirectorySizeScheduler.Cancellation
    ): FileSize {
        var total = 0L
        fun visitResult(): FileVisitResult = if (cancellation.isCancelled) {
            FileVisitResult.TERMINATE
        } else {
            FileVisitResult.CONTINUE
        }
        try {
            if (!cancellation.isCancelled) {
                Files.walkFileTree(
                    directory, emptySet(), Int.MAX_VALUE,
                    object : FileVisitor<Path> {
                        override fun preVisitDirectory(
                            dir: Path, attrs: BasicFileAttributes
                        ): FileVisitResult = visitResult()

                        override fun visitFile(
                            file: Path, attrs: BasicFileAttributes
                        ): FileVisitResult {
                            if (!cancellation.isCancelled && attrs.isRegularFile) {
                                total += attrs.size()
                            }
                            return visitResult()
                        }

                        override fun visitFileFailed(
                            file: Path, exc: IOException
                        ): FileVisitResult = visitResult()

                        override fun postVisitDirectory(
                            dir: Path, exc: IOException?
                        ): FileVisitResult = visitResult()
                    }
                )
            }
        } catch (_: Exception) {
            // Permissions or IO errors: preserve the existing best-effort behavior. The scheduler
            // rejects cancelled results even if the provider swallowed the thread interruption.
        }
        return FileSize(total)
    }
}
