/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.channels.SeekableByteChannel
import java8.nio.charset.StandardCharsets
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.filelist.ArchiveEncryption
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.common.toInt
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

class WriteArchive @Throws(ArchiveException::class) constructor(
    channel: SeekableByteChannel,
    format: Int,
    filter: Int,
    password: String?,
    encryption: ArchiveEncryption,
    compressionLevel: Int?
) : Closeable {
    private val archive = Archive.writeNew()

    init {
        var successful = false
        try {
            Archive.writeSetBytesPerBlock(archive, DEFAULT_BUFFER_SIZE)
            Archive.writeSetBytesInLastBlock(archive, 1)
            Archive.writeSetFormat(archive, format)
            Archive.writeAddFilter(archive, filter)
            // Compression level applies to the filter (deflate / xz / zstd ...). libarchive accepts
            // "compression-level" as a filter option; a null level leaves libarchive's default.
            if (compressionLevel != null) {
                Archive.writeSetFilterOption(
                    archive, null, "compression-level".toByteArray(),
                    compressionLevel.toString().toByteArray()
                )
            }
            if (password != null && encryption.isEnabled) {
                require(format == Archive.FORMAT_ZIP)
                Archive.writeSetPassphrase(archive, password.toByteArray())
                // Prefer AES-256 over the legacy ZipCrypto: the latter is trivially breakable and
                // offers no real protection. libarchive needs to be built with a crypto provider
                // (mbedTLS here) for AES to be available on the zip format.
                Archive.writeSetFormatOption(
                    archive, null, "encryption".toByteArray(),
                    if (encryption == ArchiveEncryption.AES256) "aes256".toByteArray()
                    else "zipcrypt".toByteArray()
                )
            }
            Archive.writeOpen(
                archive, null, null, { _, _, buffer -> channel.write(buffer) }, null
            )
            successful = true
        } finally {
            if (!successful) {
                close()
            }
        }
    }

    @Throws(ArchiveException::class)
    fun writeEntry(entry: Entry) {
        Archive.writeHeader(archive, entry.entry)
    }

    @Throws(ArchiveException::class)
    fun newDataOutputStream(): OutputStream = DataOutputStream()

    @Throws(ArchiveException::class)
    override fun close() {
        Archive.writeFree(archive)
    }

    inner class Entry(
        name: String,
        lastModifiedTime: FileTime?,
        lastAccessTime: FileTime?,
        creationTime: FileTime?,
        type: PosixFileType,
        size: Long,
        owner: PosixUser?,
        group: PosixGroup?,
        mode: Set<PosixFileModeBit>,
        symbolicLinkTarget: String?
    ) : Closeable {
        internal val entry = ArchiveEntry.new2(archive)

        init {
            Archive.setCharset(archive, StandardCharsets.UTF_8.name().toByteArray())
            ArchiveEntry.setPathname(entry, name.toByteArray())
            if (lastModifiedTime != null) {
                val lastModifiedTimeInstant = lastModifiedTime.toInstant()
                ArchiveEntry.setMtime(
                    entry, lastModifiedTimeInstant.epochSecond,
                    lastModifiedTimeInstant.nano.toLong()
                )
            }
            if (lastAccessTime != null) {
                val lastAccessTimeInstant = lastAccessTime.toInstant()
                ArchiveEntry.setAtime(
                    entry, lastAccessTimeInstant.epochSecond, lastAccessTimeInstant.nano.toLong()
                )
            }
            if (creationTime != null) {
                val creationTimeInstant = creationTime.toInstant()
                ArchiveEntry.setBirthtime(
                    entry, creationTimeInstant.epochSecond, creationTimeInstant.nano.toLong()
                )
            }
            ArchiveEntry.setFiletype(entry, type.mode)
            ArchiveEntry.setSize(entry, size)
            if (owner != null) {
                ArchiveEntry.setUid(entry, owner.id.toLong())
                val ownerName = owner.name
                if (ownerName != null) {
                    ArchiveEntry.setUname(entry, ownerName.toByteArray())
                }
            }
            if (group != null) {
                ArchiveEntry.setGid(entry, group.id.toLong())
                val groupName = group.name
                if (groupName != null) {
                    ArchiveEntry.setGname(entry, groupName.toByteArray())
                }
            }
            ArchiveEntry.setPerm(entry, mode.toInt())
            if (symbolicLinkTarget != null) {
                ArchiveEntry.setSymlink(entry, symbolicLinkTarget.toByteArray())
            }
        }

        override fun close() {
            ArchiveEntry.free(entry)
        }
    }

    private inner class DataOutputStream : OutputStream() {
        private val oneByteBuffer = ByteBuffer.allocateDirect(1)

        @Throws(IOException::class)
        override fun write(b: Int) {
            oneByteBuffer.clear()
            oneByteBuffer.put(b.toByte())
            Archive.writeData(archive, oneByteBuffer)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray, off: Int, len: Int) {
            val buffer = ByteBuffer.wrap(b, off, len)
            while (buffer.hasRemaining()) {
                Archive.writeData(archive, buffer)
            }
        }
    }
}
