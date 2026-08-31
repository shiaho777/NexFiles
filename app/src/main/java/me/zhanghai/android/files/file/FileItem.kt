/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import android.os.Parcelable
import androidx.annotation.WorkerThread
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.filelist.fileNameCollator
import me.zhanghai.android.files.filelist.getCollationKeyForFileName
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import me.zhanghai.android.files.util.ParcelableParceler
import java.io.IOException
import java.text.CollationKey

@Parcelize
data class FileItem(
    val path: @WriteWith<ParcelableParceler> Path,
    val nameCollationKey: @WriteWith<ParcelableParceler> CollationKey,
    val attributesNoFollowLinks: @WriteWith<ParcelableParceler> BasicFileAttributes,
    val symbolicLinkTarget: String?,
    private val symbolicLinkTargetAttributes: @WriteWith<ParcelableParceler> BasicFileAttributes?,
    val isHidden: Boolean,
    val mimeType: MimeType
) : Parcelable {
    val attributes: BasicFileAttributes
        get() = symbolicLinkTargetAttributes ?: attributesNoFollowLinks

    val isSymbolicLinkBroken: Boolean
        get() {
            check(attributesNoFollowLinks.isSymbolicLink) { "Not a symbolic link" }
            return symbolicLinkTargetAttributes == null
        }
}

@WorkerThread
@Throws(IOException::class)
fun Path.loadFileItem(): FileItem = loadFileItem(name)

/**
 * @param fileName An already-decoded name for this path, letting callers with a cheap accessor
 * (directory streams, search traversal) skip the full-path decode inside mime detection.
 */
@WorkerThread
@Throws(IOException::class)
fun Path.loadFileItem(fileName: String): FileItem {
    val nameCollationKey = fileNameCollator.getCollationKeyForFileName(fileName)
    val attributes = readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    // Every provider's isHidden() is the dot-name convention except archive and content, and
    // archive never marks entries hidden (content paths carry no dot-hidden concept in their
    // listings either). Checking the name here avoids a provider dispatch + ByteString compare
    // per entry.
    val isHidden = fileName.startsWith(".")
    if (!attributes.isSymbolicLink) {
        val mimeType = AndroidFileTypeDetector.getMimeType(this, fileName, attributes).asMimeType()
        return FileItem(this, nameCollationKey, attributes, null, null, isHidden, mimeType)
    }
    val symbolicLinkTarget = readSymbolicLinkByteString().toString()
    val symbolicLinkTargetAttributes = try {
        readAttributes(BasicFileAttributes::class.java)
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
    val mimeType = AndroidFileTypeDetector.getMimeType(
        this, fileName, symbolicLinkTargetAttributes ?: attributes
    ).asMimeType()
    return FileItem(
        this, nameCollationKey, attributes, symbolicLinkTarget, symbolicLinkTargetAttributes,
        isHidden, mimeType
    )
}
