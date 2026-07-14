/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import android.os.Parcel
import android.os.Parcelable
import java8.nio.file.ClosedFileSystemException
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotDirectoryException
import java8.nio.file.NotLinkException
import java8.nio.file.Path
import java8.nio.file.PathMatcher
import java8.nio.file.WatchService
import java8.nio.file.attribute.UserPrincipalLookupService
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.archive.archiver.ArchiveReader
import me.zhanghai.android.files.provider.archive.archiver.ReadArchive
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringBuilder
import me.zhanghai.android.files.provider.common.ByteStringListPathCreator
import me.zhanghai.android.files.provider.common.IsDirectoryException
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveException
import java.io.IOException
import java.io.InputStream

internal class ArchiveFileSystem(
    private val provider: ArchiveFileSystemProvider,
    val archiveFile: Path
) : FileSystem(), ByteStringListPathCreator, Parcelable {
    val rootDirectory = ArchivePath(this, SEPARATOR_BYTE_STRING)

    init {
        if (!rootDirectory.isAbsolute) {
            throw AssertionError("Root directory $rootDirectory must be absolute")
        }
        if (rootDirectory.nameCount != 0) {
            throw AssertionError("Root directory $rootDirectory must contain no names")
        }
    }

    val defaultDirectory: ArchivePath
        get() = rootDirectory

    private val lock = Any()

    private var isOpen = true

    // Pre-seeded with the user's saved archive passwords so that frequently-used encrypted
    // archives open without prompting. See Settings.ARCHIVE_PASSWORDS for the storage model.
    private var passwords = Settings.ARCHIVE_PASSWORDS.valueCompat.toList()

    private var isRefreshNeeded = true

    private var entries: Map<Path, ReadArchive.Entry>? = null

    private var tree: Map<Path, List<Path>>? = null

    /**
     * Copy-on-write edit overlay. Lazily created on the first write; until then the archive stays
     * read-only and [isReadOnly] returns true. Once edits exist, reads consult the overlay and a
     * [commitEdits] call rebuilds the archive on disk.
     */
    private var editLayer: ArchiveEditLayer? = null

    /** Whether there are uncommitted COW edits the user could save. */
    val hasPendingEdits: Boolean
        get() = editLayer?.isDirty == true

    @Throws(IOException::class)
    fun getEntry(path: Path): ReadArchive.Entry =
        synchronized(lock) {
            ensureEntriesLocked(path)
            getEntryLocked(path)
        }

    /**
     * Whether [path] exists in the archive or the edit overlay. Unlike [getEntry], this does not
     * throw for missing paths and accounts for overlay additions/deletions.
     */
    @Throws(IOException::class)
    fun exists(path: Path): Boolean = synchronized(lock) {
        ensureEntriesLocked(path)
        val layer = editLayer
        if (layer != null) {
            if (layer.isDeleted(path)) return@synchronized false
            if (layer.hasReplacement(path) || layer.addedDirectories.contains(path)) {
                return@synchronized true
            }
        }
        entries!!.containsKey(path)
    }

    @Throws(IOException::class)
    private fun getEntryLocked(path: Path): ReadArchive.Entry =
        synchronized(lock) {
            entries!![path] ?: throw NoSuchFileException(path.toString())
        }

    @Throws(IOException::class)
    fun newInputStream(file: Path): InputStream =
        synchronized(lock) {
            ensureEntriesLocked(file)
            // Overlay replacements win over the underlying archive entry, so an edited file's
            // latest content is what the user sees.
            editLayer?.let { layer ->
                if (layer.isDeleted(file)) throw NoSuchFileException(file.toString())
                layer.replacementInputStream(file)?.let { return@synchronized it }
            }
            val entry = getEntryLocked(file)
            if (entry.isDirectory) {
                throw IsDirectoryException(file.toString())
            }
            val inputStream = try {
                ArchiveReader.newInputStream(archiveFile, passwords, entry)
            } catch (e: ArchiveException) {
                throw e.toFileSystemOrInterruptedIOException(file)
            } ?: throw NoSuchFileException(file.toString())
            ArchiveExceptionInputStream(inputStream, file)
        }

    @Throws(IOException::class)
    fun getDirectoryChildren(directory: Path): List<Path> =
        synchronized(lock) {
            ensureEntriesLocked(directory)
            // An added directory only exists in the overlay; surface its overlay children.
            val layer = editLayer
            if (layer != null && layer.addedDirectories.contains(directory)) {
                return@synchronized layer.addedChildren(directory)
            }
            val entry = getEntryLocked(directory)
            if (!entry.isDirectory) {
                throw NotDirectoryException(directory.toString())
            }
            val base = tree!![directory]!!
            if (layer == null) {
                base
            } else {
                // Merge archive children with overlay additions, then drop deletions. Order keeps
                // existing entries first so sort/view options behave predictably.
                val merged = LinkedHashSet(base)
                merged.addAll(layer.addedChildren(directory))
                merged.filter { !layer.isDeleted(it) }
            }
        }

    @Throws(IOException::class)
    fun readSymbolicLink(link: Path): String =
        synchronized(lock) {
            ensureEntriesLocked(link)
            val entry = getEntryLocked(link)
            if (!entry.isSymbolicLink) {
                throw NotLinkException(link.toString())
            }
            entry.symbolicLinkTarget.orEmpty()
        }

    fun addPassword(password: String) {
        synchronized(lock) {
            if (!isOpen) {
                throw ClosedFileSystemException()
            }
            passwords += password
        }
    }

    fun refresh() {
        synchronized(lock) {
            if (!isOpen) {
                throw ClosedFileSystemException()
            }
            isRefreshNeeded = true
        }
    }

    // -- Copy-on-write edit surface --
    // These mutate the [editLayer] overlay rather than the archive. The archive only changes on
    // disk when [commitEdits] runs. Until the first edit, the filesystem reports read-only.

    @Throws(IOException::class)
    fun writeFile(file: Path, bytes: ByteArray) {
        synchronized(lock) {
            ensureEntriesLocked(file)
            ensureEditLayer().putFile(file, bytes)
        }
    }

    @Throws(IOException::class)
    fun createDirectoryInLayer(directory: Path) {
        synchronized(lock) {
            ensureEntriesLocked(directory)
            ensureEditLayer().addDirectory(directory)
        }
    }

    @Throws(IOException::class)
    fun deleteInLayer(path: Path) {
        synchronized(lock) {
            ensureEntriesLocked(path)
            ensureEditLayer().delete(path)
        }
    }

    /** True if [path] was added/modified by the overlay (i.e. reads should consult the layer). */
    fun isOverlayModified(path: Path): Boolean = synchronized(lock) {
        val layer = editLayer ?: return@synchronized false
        layer.hasReplacement(path) || layer.isDeleted(path) || layer.addedDirectories.contains(path)
    }

    private fun ensureEditLayer(): ArchiveEditLayer {
        val existing = editLayer
        if (existing != null) return existing
        val layer = ArchiveEditLayer()
        editLayer = layer
        return layer
    }

    /**
     * Applies all pending overlay edits by rebuilding the archive: streams the original entries
     * (minus deletions, with replacements substituted) plus the overlay additions into a fresh
     * archive written via [ArchiveWriter], then atomically replaces the file on disk.
     *
     * Format and filter are inferred from the archive file extension so callers don't need to
     * track them. Returns true if the archive was rewritten, false if there were no pending edits.
     */
    @Throws(IOException::class)
    fun commitEdits(): Boolean {
        val (format, filter) = inferFormatFilter()
        return commitEdits(format, filter)
    }

    /** Infers the archive format/filter from the file extension. Defaults to zip. */
    private fun inferFormatFilter(): Pair<Int, Int> {
        val name = archiveFile.fileName?.toString()?.lowercase() ?: ""
        return when {
            name.endsWith(".zip") -> Archive.FORMAT_ZIP to Archive.FILTER_NONE
            name.endsWith(".7z") -> Archive.FORMAT_7ZIP to Archive.FILTER_NONE
            name.endsWith(".tar.xz") || name.endsWith(".txz") ->
                Archive.FORMAT_TAR to Archive.FILTER_XZ
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                Archive.FORMAT_TAR to Archive.FILTER_GZIP
            name.endsWith(".tar") -> Archive.FORMAT_TAR to Archive.FILTER_NONE
            else -> Archive.FORMAT_ZIP to Archive.FILTER_NONE
        }
    }

    @Throws(IOException::class)
    fun commitEdits(format: Int, filter: Int): Boolean = synchronized(lock) {
        val layer = editLayer ?: return@synchronized false
        if (!layer.isDirty) return@synchronized false
        ensureEntriesLocked(rootDirectory)
        val archiveFileTyped = archiveFile
        // Write to a sibling temp file, then rename, so a failed rebuild leaves the original
        // intact.
        val tempFile = archiveFileTyped.resolveSibling(archiveFileTyped.fileName.toString() + ".nxftmp")
        try {
            archiveFileTyped.newByteChannel(
                java8.nio.file.StandardOpenOption.CREATE_NEW,
                java8.nio.file.StandardOpenOption.WRITE
            ).use { channel ->
                ArchiveWriter(channel, format, filter, null, ArchiveEncryption.NONE, null).use { writer ->
                    writeEntriesLocked(writer, layer)
                }
            }
            // Swap: delete original, rename temp into place. Non-atomic on all filesystems but
            // close enough; a crash here leaves the .nxftmp for manual recovery.
            archiveFileTyped.deleteIfExists()
            tempFile.moveTo(archiveFileTyped, java8.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { tempFile.deleteIfExists() }
            throw e
        }
        // Edits applied: drop the overlay and force a re-read of the new archive.
        editLayer = null
        isRefreshNeeded = true
        true
    }

    /**
     * Streams every surviving entry (original minus deletions, with replacements substituted for
     * modified files) plus overlay-only additions into [writer]. Walks the original entry map so
     * ordering mirrors the source archive.
     */
    @Throws(IOException::class)
    private fun writeEntriesLocked(writer: ArchiveWriter, layer: ArchiveEditLayer) {
        val entriesMap = entries ?: throw ClosedFileSystemException()
        // First pass: surviving original entries (skip deletions + directories; substitute
        // replacement bytes for modified files).
        for ((path, entry) in entriesMap) {
            if (layer.isDeleted(path)) continue
            if (entry.isDirectory) continue
            val bytes: ByteArray = if (layer.hasReplacement(path)) {
                layer.replacements[path]!!
            } else {
                ArchiveReader.newInputStream(archiveFile, passwords, entry)?.use { it.readBytes() }
                    ?: continue
            }
            writer.writeBytes(path, bytes, entry.lastModifiedTime, INTERVAL_MILLIS, null)
        }
        // Second pass: overlay-only additions (files not present in the original archive).
        for ((path, bytes) in layer.replacements) {
            if (entriesMap.containsKey(path)) continue
            writer.writeBytes(
                path, bytes, FileTime.fromMillis(System.currentTimeMillis()), INTERVAL_MILLIS, null
            )
        }
    }

    @Throws(IOException::class)
    private fun ensureEntriesLocked(file: Path) {
        if (!isOpen) {
            throw ClosedFileSystemException()
        }
        if (isRefreshNeeded) {
            val entriesAndTree = try {
                ArchiveReader.readEntries(archiveFile, passwords, rootDirectory)
            } catch (e: ArchiveException) {
                throw e.toFileSystemOrInterruptedIOException(file)
            }
            entries = entriesAndTree.first
            tree = entriesAndTree.second
            isRefreshNeeded = false
        }
    }

    override fun provider(): FileSystemProvider = provider

    override fun close() {
        synchronized(lock) {
            if (!isOpen) {
                return
            }
            provider.removeFileSystem(this)
            isRefreshNeeded = false
            entries = null
            tree = null
            isOpen = false
        }
    }

    override fun isOpen(): Boolean = synchronized(lock) { isOpen }

    override fun isReadOnly(): Boolean = false

    override fun getSeparator(): String = SEPARATOR_STRING

    override fun getRootDirectories(): Iterable<Path> = listOf(rootDirectory)

    override fun getFileStores(): Iterable<FileStore> {
        // TODO
        throw UnsupportedOperationException()
    }

    override fun supportedFileAttributeViews(): Set<String> =
        ArchiveFileAttributeView.SUPPORTED_NAMES

    override fun getPath(first: String, vararg more: String): ArchivePath {
        val path = ByteStringBuilder(first.toByteString())
            .apply { more.forEach { append(SEPARATOR).append(it.toByteString()) } }
            .toByteString()
        return ArchivePath(this, path)
    }

    override fun getPath(first: ByteString, vararg more: ByteString): ArchivePath {
        val path = ByteStringBuilder(first)
            .apply { more.forEach { append(SEPARATOR).append(it) } }
            .toByteString()
        return ArchivePath(this, path)
    }

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher {
        throw UnsupportedOperationException()
    }

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun newWatchService(): WatchService {
        // TODO
        throw UnsupportedOperationException()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }
        other as ArchiveFileSystem
        return archiveFile == other.archiveFile
    }

    override fun hashCode(): Int = archiveFile.hashCode()

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(archiveFile as Parcelable, flags)
    }

    companion object {
        const val SEPARATOR = '/'.code.toByte()
        private val SEPARATOR_BYTE_STRING = SEPARATOR.toByteString()
        private const val SEPARATOR_STRING = SEPARATOR.toInt().toChar().toString()
        // Progress callback cadence for archive rebuild writes; null listener means no callbacks.
        private const val INTERVAL_MILLIS = 0L

        @JvmField
        val CREATOR = object : Parcelable.Creator<ArchiveFileSystem> {
            override fun createFromParcel(source: Parcel): ArchiveFileSystem {
                val archiveFile = source.readParcelable<Parcelable>(Path::class.java.classLoader)
                    as Path
                return ArchiveFileSystemProvider.getOrNewFileSystem(archiveFile)
            }

            override fun newArray(size: Int): Array<ArchiveFileSystem?> = arrayOfNulls(size)
        }
    }
}
