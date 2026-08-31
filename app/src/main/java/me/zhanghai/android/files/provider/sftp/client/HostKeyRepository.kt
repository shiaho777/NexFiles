/*
 * Copyright (c) NexFiles contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import android.util.Base64
import me.zhanghai.android.files.app.application
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import net.schmizz.sshj.common.SecurityUtils

/**
 * Persists accepted SFTP host keys (OpenSSH known_hosts semantics, one line per host:port) in the
 * app's private storage. A connection whose host key differs from the stored one is rejected, so a
 * server key change or man-in-the-middle attack surfaces as an explicit error instead of being
 * silently accepted.
 */
object HostKeyRepository {
    private val file: File
        get() = File(application.filesDir, "sftp_known_hosts").also {
            if (!it.exists()) {
                it.parentFile?.mkdirs()
                try {
                    it.createNewFile()
                } catch (e: IOException) {
                    throw ClientException(e)
                }
            }
        }

    @Synchronized
    fun find(host: String, port: Int): String? =
        try {
            readEntries().find { it.host == host && it.port == port }?.key
        } catch (e: IOException) {
            null
        }

    @Synchronized
    fun write(host: String, port: Int, key: String) {
        try {
            val others = readEntries().filterNot { it.host == host && it.port == port }
            file.writeText(
                (others + KnownHostEntry(host, port, key)).joinToString("\n") { entry ->
                    "[${entry.host}]:${entry.port} ${entry.key}"
                } + "\n"
            )
        } catch (e: IOException) {
            throw ClientException(e)
        }
    }

    @Throws(IOException::class)
    private fun readEntries(): List<KnownHostEntry> =
        file.readLines().mapNotNull { line ->
            line.trim().takeIf { it.isNotEmpty() }?.let {
                val match = REGEX.matchEntire(it) ?: return@let null
                KnownHostEntry(
                    match.groupValues[1].removePrefix("[").removeSuffix("]"),
                    match.groupValues[2].toIntOrNull() ?: return@let null,
                    match.groupValues[3]
                )
            }
        }

    fun fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_PADDING or Base64.NO_WRAP)
            .trimEnd('=')
    }

    private data class KnownHostEntry(val host: String, val port: Int, val key: String)

    private val REGEX =
        Regex("""\[(.+?)]:(\d+)\s+(ssh-\S+|ecdsa-\S+) (?:[A-Za-z0-9+/=]+)""")

    init {
        checkNotNull(SecurityUtils.getSecurityProvider())
    }
}
