/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Time
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/* @see com.android.documentsui.base.Shared#formatTime(Context, long) */
@Suppress("DEPRECATION")
fun Instant.formatShort(context: Context): String {
    val time = toEpochMilli()
    // DateUtils.getRelativeTimeSpanString-style fast path: every file list item formats its
    // modification time, and each call allocated two Time objects and zone-looked-up both of
    // them. The year/day comparison only needs today's yday/year, so compute those once per
    // local day and reuse.
    val (todayYear, todayYearDay) = todayYearAndDay()
    val then = Time().apply { set(time) }
    val flags = DateUtils.FORMAT_NO_NOON or DateUtils.FORMAT_NO_MIDNIGHT or
        DateUtils.FORMAT_ABBREV_ALL or when {
            then.year != todayYear -> DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_DATE
            then.yearDay != todayYearDay -> DateUtils.FORMAT_SHOW_DATE
            else -> DateUtils.FORMAT_SHOW_TIME
        }
    return DateUtils.formatDateTime(context, time, flags)
}

/** Returns (year, yearDay) for "now", recomputed at most once per local day. */
private fun todayYearAndDay(): Pair<Int, Int> {
    val today = java.time.LocalDate.now()
    val key = today.toEpochDay()
    val cached = todayYearAndDayCache
    if (cached != null && cached.first == key) {
        return cached.second to cached.third
    }
    val computed = Triple(key, today.year, today.dayOfYear - 1)
    todayYearAndDayCache = computed
    return computed.second to computed.third
}

@Volatile
private var todayYearAndDayCache: Triple<Long, Int, Int>? = null

fun Instant.formatLong(): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(this)
