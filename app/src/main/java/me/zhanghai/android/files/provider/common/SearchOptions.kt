/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import android.os.Parcelable
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.file.MimeType

/**
 * Decoupled, self-contained description of a file search. Carries both the name pattern and the
 * attribute-based filters (size / time / type), plus the matching strategy (wildcard or regex).
 *
 * The matching logic is implemented here as pure functions operating on the file name string and
 * the [BasicFileAttributes], rather than relying on [java8.nio.file.PathMatcher]: the latter
 * matches whole paths (not just names) and its behaviour is provider-dependent, which would make
 * search results inconsistent across the local / archive / remote file systems. A dedicated,
 * deterministic matcher keeps results uniform and unit-testable.
 *
 * A query without any wildcard character falls back to a case-insensitive substring match, which
 * preserves the behaviour users had before this redesign.
 */
@Parcelize
data class SearchOptions(
    val query: String,
    val isRegex: Boolean = false,
    val isRecursive: Boolean = true,
    val mimeType: MimeType? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val startTime: Long? = null,
    val endTime: Long? = null,
    /**
     * When true, files whose *contents* contain the query also match — a grep-style search. The
     * traversal layer reads candidate files (text, bounded by [contentMaxSize]) and looks for the
     * query inside. Name matches still match on their own; content search widens the result set.
     */
    val searchContent: Boolean = false,
    /** Files larger than this are skipped during content search, to bound the cost. */
    val contentMaxSize: Long = 2 * 1024 * 1024
) : Parcelable {
    // Regex/glob patterns are compiled once per options instance instead of once per visited
    // file; a traversal checks matches() against every entry, so the compiled form is hot.
    // Null means the plain substring strategy. Wildcard globs compile into anchored regexes;
    // globToRegex is total, so a compiled wildcard pattern is always valid.
    private val matchRegex: Regex? by lazy {
        when {
            isRegex -> createRegex(query)
            hasWildcards(query, isRegex) -> createRegex(globToRegex(query))
            else -> null
        }
    }
    /**
     * Tests the file name only. Returns `true` when [query] is empty (the name filter is then a
     * no-op and selection is driven purely by the attribute filters), which lets the UI offer
     * "list all images larger than 10MB" style queries.
     */
    fun matchesName(name: String): Boolean {
        if (query.isEmpty()) {
            return true
        }
        // The compiled matcher is shared across the whole traversal; a null regex means the
        // plain substring strategy (no wildcards, no regex in the query).
        val regex = matchRegex ?: return name.contains(query, ignoreCase = true)
        return try {
            regex.containsMatchIn(name)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Content-search matcher used by the traversal layer's grep pass: same matching strategy as
     * [matchesName] but against file contents. Returns true on any match within [text]. A wildcard
     * query is anchored to the whole text, so for content search wildcards fall back to substring
     * matching (grep semantics) — users wanting full-line glob matching can use regex instead.
     */
    fun matchesContentSubstring(text: String): Boolean {
        if (query.isEmpty()) return false
        val regex = matchRegex ?: return text.contains(query, ignoreCase = true)
        return try {
            regex.containsMatchIn(text)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Combines the name filter with all attribute filters. [fileMimeType] is optional because the
     * traversal layer can compute a cheap extension-based mime type before paying for a full
     * [me.zhanghai.android.files.file.loadFileItem]; when null the type filter is skipped.
     */
    fun matches(name: String, attributes: BasicFileAttributes, fileMimeType: MimeType?): Boolean {
        if (!matchesName(name)) {
            return false
        }
        // The reported size of a directory is filesystem-dependent and meaningless for filtering,
        // so we never apply size constraints to directories (they remain navigable into).
        if (!attributes.isDirectory) {
            val size = attributes.size()
            if (minSize != null && size < minSize) {
                return false
            }
            if (maxSize != null && size > maxSize) {
                return false
            }
        }
        val lastModified = attributes.lastModifiedTime().toMillis()
        if (startTime != null && lastModified < startTime) {
            return false
        }
        if (endTime != null && lastModified > endTime) {
            return false
        }
        if (mimeType != null && fileMimeType != null && !mimeType.match(fileMimeType)) {
            return false
        }
        return true
    }

    private fun hasWildcards(): Boolean = hasWildcards(query, isRegex)

    /** Compiles [pattern] case-insensitively; an invalid pattern becomes a never-matching regex. */
    private fun createRegex(pattern: String): Regex = try {
        Regex(pattern, RegexOption.IGNORE_CASE)
    } catch (e: Exception) {
        // An invalid pattern should never crash the search; treat it as no match.
        Regex("(?!)")
    }
    companion object {
        /**
         * Returns the ranges in [name] that should be highlighted as matches, given the same
         * matching strategy used by [SearchOptions.matchesName]. Empty when there is nothing to
         * highlight.
         *
         * For substring and regex modes this is the literal matched span; for wildcard mode we
         * fall back to highlighting the longest literal (non-metacharacter) run of the query that
         * occurs in the name, since reconstructing exact glob capture groups is not meaningful.
         */
        fun highlightRanges(name: String, options: SearchOptions): List<IntRange> {
            val query = options.query
            if (query.isEmpty() || name.isEmpty()) {
                return emptyList()
            }
            return when {
                options.isRegex -> rangesRegex(name, query)
                hasWildcards(query, options.isRegex) -> rangesWildcard(name, query)
                else -> rangesSubstring(name, query)
            }
        }

        fun hasWildcards(query: String, isRegex: Boolean): Boolean =
            !isRegex && query.any { it == '*' || it == '?' }

        private fun rangesSubstring(name: String, query: String): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            var index = 0
            while (true) {
                val found = name.indexOf(query, index, ignoreCase = true)
                if (found < 0) {
                    break
                }
                ranges.add(found until found + query.length)
                index = found + query.length
            }
            return ranges
        }

        private fun rangesRegex(name: String, query: String): List<IntRange> {
            val regex = try {
                Regex(query, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                return emptyList()
            }
            return regex.findAll(name).map { it.range }.toList()
        }

        private fun rangesWildcard(name: String, query: String): List<IntRange> {
            // Extract the longest literal run (sequence of non-metacharacters) and highlight its
            // occurrences; this is a cheap, robust approximation that never looks broken.
            var best = ""
            val current = StringBuilder()
            for (char in query) {
                if (char == '*' || char == '?') {
                    if (current.length > best.length) {
                        best = current.toString()
                    }
                    current.clear()
                } else {
                    current.append(char)
                }
            }
            if (current.length > best.length) {
                best = current.toString()
            }
            return if (best.isEmpty()) emptyList() else rangesSubstring(name, best)
        }
    }
}

/**
 * Translates a glob (with `*` and `?`) into an anchored, case-insensitive regex source. Anchoring
 * means `*.jpg` matches file names ending in `.jpg` rather than any name merely containing that
 * suffix. Matching itself goes through [SearchOptions]'s lazily compiled pattern.
 */
private fun globToRegex(glob: String): String {
    val builder = StringBuilder(glob.length + 4)
    builder.append('^')
    for (char in glob) {
        when (char) {
            '*' -> builder.append(".*")
            '?' -> builder.append('.')
            // Escape regex metacharacters so that literal characters in the glob are matched
            // verbatim.
            in "\\^$.|+(){}[]" -> {
                builder.append('\\')
                builder.append(char)
            }
            else -> builder.append(char)
        }
    }
    builder.append('$')
    return builder.toString()
}
