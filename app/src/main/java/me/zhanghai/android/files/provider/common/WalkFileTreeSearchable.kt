/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.DirectoryIteratorException
import java8.nio.file.FileVisitOption
import java8.nio.file.FileVisitResult
import java8.nio.file.FileVisitor
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import java.io.InterruptedIOException

object WalkFileTreeSearchable {
    @Throws(IOException::class)
    fun search(
        directory: Path,
        options: SearchOptions,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        val paths = mutableListOf<Path>()
        // We cannot use Files.find() or Files.walk() because it cannot ignore exceptions.
        walkFileTreeForSearch(directory, object : FileVisitor<Path> {
            private var lastProgressMillis = System.currentTimeMillis()

            @Throws(InterruptedIOException::class)
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult {
                visit(directory, attributes)
                throwIfInterrupted()
                return FileVisitResult.CONTINUE
            }

            @Throws(InterruptedIOException::class)
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                visit(file, attributes)
                throwIfInterrupted()
                return FileVisitResult.CONTINUE
            }

            @Throws(InterruptedIOException::class)
            override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult {
                if (exception is InterruptedIOException) {
                    throw exception
                }
                exception.printStackTrace()
                visit(file)
                throwIfInterrupted()
                return FileVisitResult.CONTINUE
            }

            @Throws(InterruptedIOException::class)
            override fun postVisitDirectory(
                directory: Path,
                exception: IOException?
            ): FileVisitResult {
                if (exception is InterruptedIOException) {
                    throw exception
                }
                exception?.printStackTrace()
                throwIfInterrupted()
                return FileVisitResult.CONTINUE
            }

            private fun visit(path: Path, attributes: BasicFileAttributes) {
                // Exclude the directory being searched.
                if (path == directory) {
                    return
                }
                val fileName = path.fileName
                val name = fileName?.toString() ?: return
                // Early pruning on name/size/time using only the attributes we already hold; the
                // mime-type filter is applied later against the precise value produced by
                // loadFileItem(), so we pass null here to skip it.
                if (options.matches(name, attributes, null)) {
                    paths.add(path)
                    flushIfNeeded()
                    return
                }
                // Content search (grep): if enabled and the name didn't match, read the file and
                // look for the query inside. Skipped for directories, oversize files, and anything
                // that isn't a plausible text file (judged by extension) to keep it affordable.
                if (options.searchContent && attributes.isRegularFile
                    && attributes.size() <= options.contentMaxSize
                    && looksLikeTextFile(name)
                    && contentContains(path, options)
                ) {
                    paths.add(path)
                    flushIfNeeded()
                }
            }

            // visitFileFailed path: we have no trustworthy attributes, so fall back to a name-only
            // check (size/time/type filters are skipped by passing null attributes semantics via
            // the name-only overload).
            private fun visit(path: Path) {
                if (path == directory) {
                    return
                }
                val fileName = path.fileName
                val name = fileName?.toString() ?: return
                if (options.matchesName(name)) {
                    paths.add(path)
                    flushIfNeeded()
                }
            }

            private fun flushIfNeeded() {
                if (paths.isNotEmpty()) {
                    val currentTimeMillis = System.currentTimeMillis()
                    if (currentTimeMillis >= lastProgressMillis + intervalMillis) {
                        listener(paths)
                        lastProgressMillis = currentTimeMillis
                        paths.clear()
                    }
                }
            }
        })
        if (paths.isNotEmpty()) {
            listener(paths)
        }
    }

    // This method traverses the first level first, before diving into child directories.
    // FileVisitResult returned from visitor may be ignored and always considered CONTINUE.
    @Throws(IOException::class)
    private fun walkFileTreeForSearch(start: Path, visitor: FileVisitor<in Path>): Path {
        val attributes = try {
            start.readAttributes(BasicFileAttributes::class.java)
        } catch (ignored: IOException) {
            try {
                start.readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: IOException) {
                visitor.visitFileFailed(start, e)
                return start
            }
        }
        if (!attributes.isDirectory) {
            visitor.visitFile(start, attributes)
            return start
        }
        val directoryStream = try {
            start.newDirectoryStream()
        } catch (e: IOException) {
            visitor.visitFileFailed(start, e)
            return start
        }
        val directories = mutableListOf<Path>()
        directoryStream.use {
            visitor.preVisitDirectory(start, attributes)
            try {
                for (path in directoryStream) {
                    val attributes = try {
                        path.readAttributes(BasicFileAttributes::class.java)
                    } catch (ignored: IOException) {
                        try {
                            path.readAttributes(
                                BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
                            )
                        } catch (e: IOException) {
                            visitor.visitFileFailed(path, e)
                            continue
                        }
                    }
                    visitor.visitFile(path, attributes)
                    // Check between entries too: a cancelled search shouldn't keep stat'ing
                    // the rest of this directory before diving into subdirectories.
                    throwIfInterrupted()
                    if (attributes.isDirectory) {
                        directories.add(path)
                    }
                }
            } catch (e: DirectoryIteratorException) {
                visitor.postVisitDirectory(start, e.cause)
                return start
            }
        }
        for (path in directories) {
            Files.walkFileTree(
                path, setOf(FileVisitOption.FOLLOW_LINKS), Int.MAX_VALUE,
                object : FileVisitor<Path> {
                    @Throws(InterruptedIOException::class)
                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes
                    ): FileVisitResult {
                        if (directory == path) {
                            return FileVisitResult.CONTINUE
                        }
                        return visitor.preVisitDirectory(directory, attributes)
                    }

                    @Throws(InterruptedIOException::class)
                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes
                    ): FileVisitResult {
                        if (file == path) {
                            return FileVisitResult.CONTINUE
                        }
                        return visitor.visitFile(file, attributes)
                    }

                    @Throws(InterruptedIOException::class)
                    override fun visitFileFailed(
                        file: Path,
                        exception: IOException
                    ): FileVisitResult {
                        if (file == path) {
                            // We are searching and ignoring errors, so just print it.
                            exception.printStackTrace()
                            return FileVisitResult.CONTINUE
                        }
                        return visitor.visitFileFailed(file, exception)
                    }

                    @Throws(InterruptedIOException::class)
                    override fun postVisitDirectory(
                        directory: Path,
                        exception: IOException?
                    ): FileVisitResult {
                        if (directory == path) {
                            // We are searching and ignoring errors, so just print it.
                            exception?.printStackTrace()
                            return FileVisitResult.CONTINUE
                        }
                        return visitor.postVisitDirectory(path, exception)
                    }
                }
            )
        }
        visitor.postVisitDirectory(start, null)
        return start
    }

    @Throws(InterruptedIOException::class)
    private fun throwIfInterrupted() {
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
    }

    /**
     * Cheap heuristic: search the file's contents only if its extension is one commonly holding
     * text. This avoids opening binaries (images, archives, .so) where a byte-level match would be
     * meaningless and wasteful. The list is intentionally short and conservative.
     */
    private fun looksLikeTextFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    /** Reads [path] as UTF-8 (best-effort) and returns true if the query matches its contents. */
    private fun contentContains(path: Path, options: SearchOptions): Boolean {
        return try {
            path.newInputStream().use { input ->
                // Read in chunks to honour the size cap and to allow early exit on first match; we
                // decode incrementally so a match spanning a chunk boundary is still caught by the
                // overlapping-window check below.
                val decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                var carried = ""
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.interrupted()) throw InterruptedIOException()
                    val read = input.read(buffer)
                    if (read == -1) break
                    // Wrap the raw bytes through the decoder so invalid UTF-8 doesn't crash us.
                    val bb = java.nio.ByteBuffer.wrap(buffer, 0, read)
                    val cb = decoder.decode(bb)
                    val chunk = carried + cb.toString()
                    if (options.matchesContentSubstring(chunk)) return true
                    // Carry the tail of the chunk into the next iteration so a match that straddles
                    // the boundary is still found. The carry length is bounded by the query length.
                    val carryLen = minOf(options.query.length, chunk.length)
                    carried = chunk.takeLast(carryLen)
                }
                // Final check including any leftover carried text.
                options.matchesContentSubstring(carried)
            }
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: Exception) {
            // Read failure, permission, encoding — skip the file rather than aborting the search.
            false
        }
    }

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "log", "json", "xml", "html", "htm", "css", "js", "ts", "java", "kt",
        "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cc", "sh", "bash", "zsh", "fish",
        "yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "gradle", "bat", "ps1",
        "sql", "csv", "tsv", "php", "pl", "lua", "vim", "diff", "patch", "svg", "rst", "tex"
    )
}
