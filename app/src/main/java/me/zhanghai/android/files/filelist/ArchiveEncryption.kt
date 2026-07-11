/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.annotation.StringRes
import me.zhanghai.android.files.R

/**
 * Encryption strategy offered when creating an archive. The previous implementation only ever used
 * traditional ZipCrypto, which is weak enough to be cracked in minutes; we now default new
 * password-protected archives to AES-256 (provided by libarchive via its bundled mbedTLS).
 *
 * Whether a given format supports encryption at all is decided at the dialog layer; this enum only
 * describes *how* to encrypt when encryption is requested.
 */
enum class ArchiveEncryption(@StringRes val titleRes: Int) {
    NONE(R.string.file_create_archive_encryption_none),
    AES256(R.string.file_create_archive_encryption_aes256);

    val isEnabled: Boolean
        get() = this != NONE
}
