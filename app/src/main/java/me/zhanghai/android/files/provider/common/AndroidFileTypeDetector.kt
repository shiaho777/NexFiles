/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.spi.FileTypeDetector
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.forSpecialPosixFileType
import me.zhanghai.android.files.file.guessFromFileName
import me.zhanghai.android.files.file.guessFromPath
import java.io.IOException

object AndroidFileTypeDetector : FileTypeDetector() {
    @Throws(IOException::class)
    override fun probeContentType(path: Path): String {
        val attributes = path.readAttributes(BasicFileAttributes::class.java)
        return getMimeType(path, attributes)
    }

    fun getMimeType(path: Path, attributes: BasicFileAttributes): String {
        MimeType.forSpecialPosixFileType(attributes.posixFileType)?.let { return it.value }
        if (attributes.isDirectory) {
            return MimeType.DIRECTORY.value
        }
        if (attributes is ContentProviderFileAttributes) {
            attributes.mimeType()?.let { return it }
        }
        return MimeType.guessFromPath(path.toString()).value
    }

    /**
     * Same as [getMimeType] but takes the already-decoded file name. Extension guessing only
     * needs the name, so this skips the full-path decode (`toString()` joins and decodes every
     * segment) that runs once per listed entry — the hottest non-syscall cost of a directory
     * load.
     */
    fun getMimeType(path: Path, fileName: String, attributes: BasicFileAttributes): String {
        MimeType.forSpecialPosixFileType(attributes.posixFileType)?.let { return it.value }
        if (attributes.isDirectory) {
            return MimeType.DIRECTORY.value
        }
        if (attributes is ContentProviderFileAttributes) {
            attributes.mimeType()?.let { return it }
        }
        return MimeType.guessFromFileName(fileName).value
    }
}
