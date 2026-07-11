/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import android.os.ParcelFileDescriptor
import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.hiddenapi.RestrictedHiddenApi
import java.io.FileDescriptor
import java.io.IOException
import java.lang.reflect.Field

/**
 * Attempts to extract a duplicated [ParcelFileDescriptor] from a [SeekableByteChannel].
 *
 * This is the fast path for remote file I/O: when the underlying channel ultimately wraps a
 * real file descriptor (true for local files, SAF documents opened via fd, and archive entries
 * backed by a real file), we hand the caller a duplicate fd over a single Binder transaction.
 * The caller then reads/writes through [android.system.Os.pread]/[Os.pwrite] with no further
 * IPC, instead of paying one Binder round-trip per read() call.
 *
 * Returns null when the channel has no fd backing (e.g. an FTP/SFTP channel that streams over
 * a socket, or any pure in-memory channel); callers must then fall back to the per-call IPC
 * path. Detection is done by walking the wrapper chain — the desugared
 * `java8.nio.channels.FileChannel`, our own [DelegateFileChannel], and the platform
 * `sun.nio.ch.FileChannelImpl` — looking for the first reachable `FileDescriptor` field.
 */
@Throws(IOException::class)
fun SeekableByteChannel.dupFileDescriptorOrNull(): ParcelFileDescriptor? {
    val fd = unwrapToFileDescriptor(this) ?: return null
    return try {
        // dup() returns an independent fd the caller owns; the channel's original fd is left
        // untouched and will be closed when the channel is closed.
        ParcelFileDescriptor.dup(fd)
    } catch (e: Exception) {
        null
    }
}

@OptIn(RestrictedHiddenApi::class)
private fun unwrapToFileDescriptor(root: Any): FileDescriptor? {
    // Walk the wrapper chain depth-first, unwrapping any field whose type is a channel-like
    // class, until we find a FileDescriptor. We cap the depth to stay bounded against cycles.
    return unwrapDepthFirst(root, 0)
}

private fun unwrapDepthFirst(node: Any, depth: Int): FileDescriptor? {
    if (depth > MAX_UNWRAP_DEPTH) return null
    // Look for a FileDescriptor field on this node first.
    forEachDeclaredField(node) { field ->
        if (field.type == FileDescriptor::class.java) {
            field.isAccessible = true
            val fd = runCatching { field.get(node) as? FileDescriptor }.getOrNull()
            if (fd != null) return fd
        }
    }
    // Then recurse into channel-shaped wrapper fields.
    forEachDeclaredField(node) { field ->
        if (!isChannelWrapperField(field)) return@forEachDeclaredField
        field.isAccessible = true
        val child = runCatching { field.get(node) }.getOrNull() ?: return@forEachDeclaredField
        if (child != null) {
            unwrapDepthFirst(child, depth + 1)?.let { return it }
        }
    }
    return null
}

private inline fun forEachDeclaredField(node: Any, block: (Field) -> Unit) {
    var klass: Class<*>? = node.javaClass
    while (klass != null && klass != Any::class.java) {
        for (field in klass.declaredFields) {
            block(field)
        }
        klass = klass.superclass
    }
}

private fun isChannelWrapperField(field: Field): Boolean {
    if (field.type.isPrimitive) return false
    if (field.type == FileDescriptor::class.java) return false
    if (field.type == String::class.java) return false
    if (field.type.isArray) return false
    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return false
    val typeName = field.type.name
    // Only descend into fields that look like channel wrappers; this keeps the walk focused
    // and avoids following unrelated bookkeeping fields.
    return typeName.contains("Channel") || typeName.contains("ByteChannel")
}

private const val MAX_UNWRAP_DEPTH = 10
