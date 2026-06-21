/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.provider.common.SearchOptions

/**
 * Snapshot of the search mode held by [FileListViewModel]. Carries whether a search is active and
 * the full [SearchOptions] (not just the query string), so that attribute filters survive
 * configuration changes and drive both the traversal and the in-result refinement.
 */
data class SearchState(val isSearching: Boolean, val options: SearchOptions) {
    companion object {
        val DEFAULT = SearchState(false, SearchOptions(""))
    }
}
