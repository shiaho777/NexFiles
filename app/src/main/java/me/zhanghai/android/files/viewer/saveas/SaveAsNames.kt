/*
 * Copyright (c) NexFiles contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.saveas

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java8.nio.file.Path
import me.zhanghai.android.files.util.toSaveAsPathOrNull

/**
 * Resolves display names for shared content URIs and converts them back to the content paths the
 * file-job layer copies from.
 */
internal object SaveAsNames {
    fun forUri(context: Context, uri: Uri): String? = queryDisplayName(context, uri)

    fun toPath(context: Context, uri: Uri): Path? = uri.toSaveAsPathOrNull()

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            ).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        val name = cursor.getString(index)
                        if (!name.isNullOrEmpty()) {
                            return name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return uri.lastPathSegment
    }
}
