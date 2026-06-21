/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.file.MimeType

/**
 * Editable state of the search filter panel, kept separate from [SearchOptions] (the committed
 * snapshot that drives the traversal). The fragment binds the panel UI to an instance of this and,
 * on apply, folds it together with the query into a [SearchOptions] via
 * [FileListViewModel.buildOptions].
 *
 * All fields default to "no constraint", so an untouched panel produces a pure name search that
 * behaves exactly as before the redesign.
 */
@Parcelize
data class SearchFilterOptions(
    val isRecursive: Boolean = true,
    val isRegex: Boolean = false,
    val mimeType: MimeType? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val startTime: Long? = null,
    val endTime: Long? = null
) : Parcelable {
    /**
     * Whether any filter beyond the name pattern is set. Used by the UI to badge the filter button
     * and to decide whether a query-only search needs the full attribute machinery at all.
     */
    val hasAttributeFilters: Boolean
        get() = mimeType != null || minSize != null || maxSize != null ||
            startTime != null || endTime != null || !isRecursive

    companion object {
        val DEFAULT = SearchFilterOptions()
    }
}
