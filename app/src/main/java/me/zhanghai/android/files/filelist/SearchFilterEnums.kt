/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.annotation.StringRes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType

/**
 * Bucketed filter options surfaced by [SearchFilterDialogFragment]. Keeping them as enums (rather
 * than free-form inputs) makes the panel simple and predictable: every choice maps to a fixed
 * [MimeType] or size/time window, and custom ranges are intentionally out of scope for the first
 * iteration.
 */

enum class SearchFilterType(@StringRes val titleRes: Int, val mimeType: MimeType?) {
    ANY(R.string.search_filter_type_any, null),
    IMAGE(R.string.search_filter_type_image, MimeType.IMAGE_ANY),
    AUDIO(R.string.search_filter_type_audio, "audio/*".asMimeType()),
    VIDEO(R.string.search_filter_type_video, "video/*".asMimeType()),
    TEXT(R.string.search_filter_type_text, "text/*".asMimeType()),
    APK(R.string.search_filter_type_apk, MimeType.APK)
}

enum class SearchFilterSize(
    @StringRes val titleRes: Int,
    val range: Pair<Long?, Long?>
) {
    ANY(R.string.search_filter_size_any, null to null),
    UNDER_1MB(R.string.search_filter_size_under_1mb, null to 1L * 1024 * 1024),
    BETWEEN_1MB_100MB(
        R.string.search_filter_size_1mb_100mb, 1L * 1024 * 1024 to 100L * 1024 * 1024
    ),
    OVER_100MB(R.string.search_filter_size_over_100mb, 100L * 1024 * 1024 to null)
}

enum class SearchFilterTime(@StringRes val titleRes: Int) {
    ANY(R.string.search_filter_time_any),
    TODAY(R.string.search_filter_time_today),
    WEEK(R.string.search_filter_time_week),
    MONTH(R.string.search_filter_time_month),
    YEAR(R.string.search_filter_time_year)
}
