/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java8.nio.file.StandardOpenOption
import me.zhanghai.android.files.provider.common.OpenOptions

/**
 * Validates open options for archive paths.
 *
 * WRITE, CREATE, CREATE_NEW, and TRUNCATE_EXISTING are **allowed** — they are handled by the
 * copy-on-write edit layer ([ArchiveEditByteChannel] → [ArchiveFileSystem.editLayer]). Writes are
 * staged in memory and committed to the archive only on [ArchiveFileSystem.commitEdits].
 *
 * Options that genuinely cannot be supported (APPEND, DELETE_ON_CLOSE, SYNC, DSYNC) still throw.
 */
internal fun OpenOptions.checkForArchive() {
    if (append) {
        throw UnsupportedOperationException(StandardOpenOption.APPEND.toString())
    }
    if (deleteOnClose) {
        throw UnsupportedOperationException(StandardOpenOption.DELETE_ON_CLOSE.toString())
    }
    if (sync) {
        throw UnsupportedOperationException(StandardOpenOption.SYNC.toString())
    }
    if (dsync) {
        throw UnsupportedOperationException(StandardOpenOption.DSYNC.toString())
    }
}
