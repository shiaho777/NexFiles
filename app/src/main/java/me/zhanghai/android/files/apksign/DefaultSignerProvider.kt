/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksign

import android.content.Context
import me.zhanghai.android.files.R
import java.io.FileInputStream
import java.io.IOException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Loads the signing key+certificate used to (re)sign APKs.
 *
 * Two sources, checked in priority order:
 *  1. **User-imported keystore** — a path the user picked via the settings screen, stored as an
 *     absolute filesystem path. Used when the user needs a specific signing identity (e.g. to
 *     install as an update to an existing app).
 *  2. **Built-in default** — a fixed RSA-2048 key bundled in `res/raw/nexfiles_default_keystore.p12`.
 *     This is the zero-config path: every NexFiles install ships with it, so "sign" works out of
 *     the box the way MT's default key does.
 *
 * The keystore format is PKCS#12 (`.p12`/`.keystore`/`.jks`); Android's JCA KeyStore SPI handles
 * both PKCS12 and JKS, so users can import either.
 *
 * The default key's password is `nexfiles` — it is not secret, it exists only so the key can be
 * embedded in the APK without a separate licensing concern. Anyone extracting it gets a key that
 * every NexFiles user also has, which is the same trust model as Android's debug key.
 */
object DefaultSignerProvider {

    private const val DEFAULT_KEYSTORE_PASSWORD = "nexfiles"
    private const val DEFAULT_KEY_ALIAS = "nexfiles"
    private const val DEFAULT_KEY_PASSWORD = "nexfiles"

    /**
     * Loads the [ApkSignerConfig] from the built-in default keystore, with the given scheme
     * toggles. This never returns null: the default keystore is always present in the APK.
     */
    fun loadDefault(context: Context, v1: Boolean, v2: Boolean, v3: Boolean): ApkSignerConfig {
        val keystore = loadDefaultKeystore(context)
        return configFromKeystore(keystore, DEFAULT_KEY_ALIAS, DEFAULT_KEYSTORE_PASSWORD, v1, v2, v3)
    }

    /**
     * Loads an [ApkSignerConfig] from a user-imported keystore file at [path] with [password].
     * If [alias] is null, the first key entry in the keystore is used.
     */
    fun loadImported(
        path: String, password: String, alias: String?, keyPassword: String?,
        v1: Boolean, v2: Boolean, v3: Boolean
    ): ApkSignerConfig {
        val keystore = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(path).use { load(it, password.toCharArray()) }
        }
        val effectiveAlias = alias ?: keystore.aliases().nextElement()
        return configFromKeystore(
            keystore, effectiveAlias, password, v1, v2, v3,
            keyPassword ?: password
        )
    }

    private fun loadDefaultKeystore(context: Context): KeyStore {
        val keystore = KeyStore.getInstance("PKCS12")
        context.resources.openRawResource(R.raw.nexfiles_default_keystore).use { input ->
            keystore.load(input, DEFAULT_KEYSTORE_PASSWORD.toCharArray())
        }
        return keystore
    }

    private fun configFromKeystore(
        keystore: KeyStore,
        alias: String,
        storePassword: String,
        v1: Boolean,
        v2: Boolean,
        v3: Boolean,
        keyPassword: String = storePassword
    ): ApkSignerConfig {
        val key = keystore.getKey(alias, keyPassword.toCharArray()) as? PrivateKey
            ?: throw IOException("No private key for alias '$alias' in keystore")
        val certChain = keystore.getCertificateChain(alias)
            ?: throw IOException("No certificate chain for alias '$alias' in keystore")
        val certificates = certChain.map { it as X509Certificate }
        return ApkSignerConfig(alias, key, certificates, v1, v2, v3)
    }
}
