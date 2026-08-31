/*
 * Copyright (c) NexFiles contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.math.BigInteger
import java.security.PublicKey

/**
 * Verifies the server host key against [HostKeyRepository]. A first connection accepts and stores
 * the key (trust on first use); any later connection must present the exact same key.
 */
class KnownHostsVerifier(private val host: String, private val port: Int) : HostKeyVerifier {
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val encoded = encodeKey(key)
        val known = HostKeyRepository.find(host, port)
        if (known != null) {
            return known == encoded
        }
        HostKeyRepository.write(host, port, encoded)
        return true
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    companion object {
        // sshj's PublicKeyEC.raw encoding is not stable enough to compare across key formats, so
        // store the algorithm name plus the opaque encoded blob, which is deterministic per key.
        fun encodeKey(key: PublicKey): String = "${key.algorithm}:${key.format}:${key.encoded}"

        fun fingerprint(key: PublicKey): String = HostKeyRepository.fingerprint(key)

        @Suppress("unused")
        private fun toHex(bytes: ByteArray): String =
            BigInteger(1, bytes).toString(16).padStart(bytes.size * 2, '0')
    }
}
