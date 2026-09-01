/*
 * Copyright (c) NexFiles contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.net.Uri
import android.os.Parcel
import androidx.preference.PreferenceManager
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.appClassLoader
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.compat.readParcelableListCompat
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.provider.ftp.client.Mode
import me.zhanghai.android.files.provider.ftp.client.Protocol as FtpProtocol
import me.zhanghai.android.files.provider.sftp.client.Authentication as SftpAuthentication
import me.zhanghai.android.files.provider.sftp.client.PasswordAuthentication as SftpPasswordAuthentication
import me.zhanghai.android.files.provider.sftp.client.PublicKeyAuthentication as SftpPublicKeyAuthentication
import me.zhanghai.android.files.provider.sftp.client.Authority as SftpAuthority
import me.zhanghai.android.files.provider.smb.client.Authority as SmbAuthority
import me.zhanghai.android.files.provider.webdav.client.AccessTokenAuthentication as WebDavAccessTokenAuthentication
import me.zhanghai.android.files.provider.webdav.client.Authentication as WebDavAuthentication
import me.zhanghai.android.files.provider.webdav.client.Authority as WebDavAuthority
import me.zhanghai.android.files.provider.webdav.client.NoneAuthentication as WebDavNoneAuthentication
import me.zhanghai.android.files.provider.webdav.client.PasswordAuthentication as WebDavPasswordAuthentication
import me.zhanghai.android.files.provider.webdav.client.Protocol as WebDavProtocol
import me.zhanghai.android.files.provider.ftp.client.Authority as FtpAuthority
import me.zhanghai.android.files.storage.DeviceStorage
import me.zhanghai.android.files.storage.DocumentTree
import me.zhanghai.android.files.storage.ExternalStorageShortcut
import me.zhanghai.android.files.storage.FileSystemRoot
import me.zhanghai.android.files.storage.FtpServer
import me.zhanghai.android.files.storage.PrimaryStorageVolume
import me.zhanghai.android.files.storage.SftpServer
import me.zhanghai.android.files.storage.SmbServer
import me.zhanghai.android.files.storage.Storage
import me.zhanghai.android.files.storage.WebDavServer
import me.zhanghai.android.files.util.readParcelable
import me.zhanghai.android.files.util.use
import me.zhanghai.android.files.util.valueCompat
import org.json.JSONArray
import org.json.JSONObject
import java8.nio.file.Paths

/**
 * Serializes user data (bookmarks, server storages with their credentials, FTP/WebDAV server
 * settings and user-facing preferences) to and from a JSON document. Server credentials are stored
 * in plain text by the app already (see [Settings.ARCHIVE_PASSWORDS] documentation), so including
 * them keeps an export complete; the file must be treated as sensitive by the user.
 */
object DataExportImport {
    private const val FORMAT_APP = "nexfiles-data-export"
    private const val FORMAT_VERSION = 1

    val EXPORT_FILE_NAME = "nexfiles-data-export.json"

    // User-facing preferences worth carrying to a new device. Keys match the persisted
    // SharedPreferences keys; value encoding matches the SettingLiveData that reads each key.
    private val PREFERENCE_KEYS = listOf(
        R.string.pref_key_file_list_show_hidden_files,
        R.string.pref_key_file_list_view_type,
        R.string.pref_key_file_list_sort_options,
        R.string.pref_key_theme_color,
        R.string.pref_key_material_design_3,
        R.string.pref_key_night_mode,
        R.string.pref_key_black_night_mode,
        R.string.pref_key_file_list_animation,
        R.string.pref_key_file_list_dual_pane,
        R.string.pref_key_root_strategy,
        R.string.pref_key_recycle_bin_enabled,
        R.string.pref_key_archive_passwords,
        R.string.pref_key_ftp_server_anonymous_login,
        R.string.pref_key_ftp_server_username,
        R.string.pref_key_ftp_server_password,
        R.string.pref_key_ftp_server_port,
        R.string.pref_key_webdav_server_username,
        R.string.pref_key_webdav_server_password,
        R.string.pref_key_webdav_server_port
    )

    fun export(): JSONObject {
        val root = JSONObject()
        root.put("app", FORMAT_APP)
        root.put("version", FORMAT_VERSION)

        root.put("bookmarkDirectories", JSONArray().apply {
            Settings.BOOKMARK_DIRECTORIES.valueCompat.forEach { bookmark ->
                put(
                    JSONObject()
                        .put("customName", bookmark.customName)
                        .put("path", bookmark.path.toString())
                )
            }
        })

        root.put("servers", JSONArray().apply {
            Settings.STORAGES.valueCompat.forEach { storage ->
                when (storage) {
                    is FtpServer -> put(exportFtpServer(storage))
                    is SftpServer -> put(exportSftpServer(storage))
                    is SmbServer -> put(exportSmbServer(storage))
                    is WebDavServer -> put(exportWebDavServer(storage))
                    // Device-local storages are machine-specific; skip them.
                    is FileSystemRoot, is PrimaryStorageVolume, is DeviceStorage,
                    is DocumentTree, is ExternalStorageShortcut -> Unit
                }
            }
        })

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
        root.put("preferences", JSONObject().apply {
            PREFERENCE_KEYS.forEach { keyRes ->
                val key = application.getString(keyRes)
                sharedPreferences.all[key]?.let { value ->
                    put(key, value)
                }
            }
        })
        return root
    }

    /**
     * Applies a parsed export to the current settings. Returns the number of imported entries
     * (bookmarks + servers). Throws [IllegalArgumentException] on a foreign or malformed document.
     */
    fun import(root: JSONObject): Int {
        check(root.optString("app") == FORMAT_APP) { "Not a NexFiles data export" }
        val version = root.optInt("version", -1)
        check(version in 1..FORMAT_VERSION) { "Unsupported export version: $version" }

        var importedCount = 0

        val bookmarks = root.optJSONArray("bookmarkDirectories")
        if (bookmarks != null) {
            val imported = (0 until bookmarks.length()).mapNotNull { index ->
                val bookmark = bookmarks.getJSONObject(index)
                val pathString = bookmark.optString("path")
                if (pathString.isEmpty()) return@mapNotNull null
                BookmarkDirectory(
                    bookmark.optString("customName").takeIf { it.isNotEmpty() },
                    Paths.get(pathString)
                )
            }
            if (imported.isNotEmpty()) {
                Settings.BOOKMARK_DIRECTORIES.putValue(
                    Settings.BOOKMARK_DIRECTORIES.valueCompat + imported
                )
                importedCount += imported.size
            }
        }

        val servers = root.optJSONArray("servers")
        if (servers != null) {
            val imported = (0 until servers.length()).mapNotNull { index ->
                importServer(servers.getJSONObject(index))
            }
            if (imported.isNotEmpty()) {
                // Keep the machine-specific default storages ahead of the imported servers.
                val defaults =
                    Settings.STORAGES.valueCompat.filterNot { it in imported }
                Settings.STORAGES.putValue(defaults + imported)
                importedCount += imported.size
            }
        }

        val preferences = root.optJSONObject("preferences")
        if (preferences != null) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
            val editor = sharedPreferences.edit()
            for (key in preferences.keys()) {
                when (val value = preferences.get(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                    is JSONArray -> editor.putStringSet(
                        key, (0 until value.length()).mapTo(mutableSetOf()) { value.getString(it) }
                    )
                }
            }
            editor.apply()
        }
        return importedCount
    }

    private fun exportFtpServer(server: FtpServer): JSONObject =
        JSONObject()
            .put("type", "ftp")
            .put("customName", server.customName)
            .put(
                "authority",
                JSONObject()
                    .put("protocol", server.authority.protocol.name)
                    .put("host", server.authority.host)
                    .put("port", server.authority.port)
                    .put("username", server.authority.username)
                    .put("mode", server.authority.mode.name)
                    .put("encoding", server.authority.encoding)
            )
            .put("password", server.password)
            .put("relativePath", server.relativePath)

    private fun exportSftpServer(server: SftpServer): JSONObject =
        JSONObject()
            .put("type", "sftp")
            .put("customName", server.customName)
            .put(
                "authority",
                JSONObject()
                    .put("host", server.authority.host)
                    .put("port", server.authority.port)
                    .put("username", server.authority.username)
            )
            .put(
                "authentication",
                when (val authentication = server.authentication) {
                    is SftpPasswordAuthentication ->
                        JSONObject().put("kind", "password").put("password", authentication.password)
                    is SftpPublicKeyAuthentication ->
                        JSONObject()
                            .put("kind", "publicKey")
                            .put("privateKey", authentication.privateKey)
                            .put("privateKeyPassword", authentication.privateKeyPassword)
                }
            )
            .put("relativePath", server.relativePath)

    private fun exportSmbServer(server: SmbServer): JSONObject =
        JSONObject()
            .put("type", "smb")
            .put("customName", server.customName)
            .put(
                "authority",
                JSONObject()
                    .put("host", server.authority.host)
                    .put("port", server.authority.port)
                    .put("username", server.authority.username)
                    .put("domain", server.authority.domain)
            )
            .put("password", server.password)
            .put("relativePath", server.relativePath)

    private fun exportWebDavServer(server: WebDavServer): JSONObject =
        JSONObject()
            .put("type", "webdav")
            .put("customName", server.customName)
            .put(
                "authority",
                JSONObject()
                    .put("protocol", server.authority.protocol.name)
                    .put("host", server.authority.host)
                    .put("port", server.authority.port)
                    .put("username", server.authority.username)
            )
            .put(
                "authentication",
                when (val authentication = server.authentication) {
                    is WebDavNoneAuthentication ->
                        JSONObject().put("kind", "none")
                    is WebDavPasswordAuthentication ->
                        JSONObject().put("kind", "password").put("password", authentication.password)
                    is WebDavAccessTokenAuthentication ->
                        JSONObject().put("kind", "accessToken").put("accessToken", authentication.accessToken)
                }
            )
            .put("relativePath", server.relativePath)

    private fun importServer(server: JSONObject): Storage? {
        return try {
            when (server.getString("type")) {
                "ftp" -> {
                    val authority = server.getJSONObject("authority")
                    FtpServer(
                        null as Long?,
                        server.optString("customName").takeIf { it.isNotEmpty() },
                        FtpAuthority(
                            FtpProtocol.valueOf(authority.getString("protocol")),
                            authority.getString("host"),
                            authority.getInt("port"),
                            authority.getString("username"),
                            Mode.valueOf(authority.optString("mode", "PASSIVE")),
                            authority.optString("encoding", "UTF-8")
                        ),
                        server.optString("password"),
                        server.optString("relativePath")
                    )
                }
                "sftp" -> {
                    val authority = server.getJSONObject("authority")
                    val authentication = server.getJSONObject("authentication")
                    SftpServer(
                        null as Long?,
                        server.optString("customName").takeIf { it.isNotEmpty() },
                        SftpAuthority(
                            authority.getString("host"),
                            authority.getInt("port"),
                            authority.getString("username")
                        ),
                        when (authentication.getString("kind")) {
                            "publicKey" -> SftpPublicKeyAuthentication(
                                authentication.getString("privateKey"),
                                authentication.optString("privateKeyPassword").takeIf { it.isNotEmpty() }
                            )
                            else -> SftpPasswordAuthentication(
                                authentication.optString("password")
                            )
                        },
                        server.optString("relativePath")
                    )
                }
                "smb" -> {
                    val authority = server.getJSONObject("authority")
                    SmbServer(
                        null as Long?,
                        server.optString("customName").takeIf { it.isNotEmpty() },
                        SmbAuthority(
                            authority.getString("host"),
                            authority.getInt("port"),
                            authority.getString("username"),
                            authority.optString("domain").takeIf { it.isNotEmpty() }
                        ),
                        server.optString("password"),
                        server.optString("relativePath")
                    )
                }
                "webdav" -> {
                    val authority = server.getJSONObject("authority")
                    val authentication = server.getJSONObject("authentication")
                    WebDavServer(
                        null as Long?,
                        server.optString("customName").takeIf { it.isNotEmpty() },
                        WebDavAuthority(
                            WebDavProtocol.valueOf(authority.getString("protocol")),
                            authority.getString("host"),
                            authority.getInt("port"),
                            authority.getString("username")
                        ),
                        when (authentication.getString("kind")) {
                            "password" -> WebDavPasswordAuthentication(
                                authentication.optString("password")
                            )
                            "accessToken" -> WebDavAccessTokenAuthentication(
                                authentication.optString("accessToken")
                            )
                            else -> WebDavNoneAuthentication
                        },
                        server.optString("relativePath")
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Skip a malformed entry instead of failing the whole import.
            null
        }
    }
}
