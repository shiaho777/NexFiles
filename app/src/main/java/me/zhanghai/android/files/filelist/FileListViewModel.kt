/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.filelist.FileSortOptions.By
import me.zhanghai.android.files.filelist.FileSortOptions.Order
import me.zhanghai.android.files.navigation.RecentDirectories
import me.zhanghai.android.files.provider.archive.archiveRefresh
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.SearchOptions
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.Closeable

// TODO: Use SavedStateHandle to save state.
class FileListViewModel : ViewModel() {
    private val trailLiveData = TrailLiveData()
    val hasTrail: Boolean
        get() = trailLiveData.value != null
    val pendingState: Parcelable?
        get() = trailLiveData.valueCompat.pendingState

    fun navigateTo(lastState: Parcelable, path: Path) {
        trailLiveData.navigateTo(lastState, path)
        recordRecent(path)
    }

    fun resetTo(path: Path) {
        trailLiveData.resetTo(path)
        recordRecent(path)
    }

    fun navigateUp(): Boolean {
        val navigated = trailLiveData.navigateUp()
        if (navigated) {
            recordRecent(currentPath)
        }
        return navigated
    }

    // Records every directory the user lands in, so the navigation drawer can offer one-tap return.
    private fun recordRecent(path: Path) {
        RecentDirectories.record(path)
    }

    val currentPathLiveData = trailLiveData.map { it.currentPath }
    val currentPath: Path
        get() = currentPathLiveData.valueCompat

    private val _searchStateLiveData = MutableLiveData(SearchState.DEFAULT)
    val searchStateLiveData: LiveData<SearchState> = _searchStateLiveData
    val searchState: SearchState
        get() = _searchStateLiveData.valueCompat

    /**
     * Live, editable view on the current search filter (type/size/time/recursive/regex). The
     * fragment binds the filter panel to these properties and, on commit, rebuilds the active
     * [SearchOptions] via [search]. Kept separate from [SearchState] so that in-progress filter
     * edits do not relaunch the traversal until the user applies them.
     */
    private val _searchFilterLiveData = MutableLiveData(SearchFilterOptions.DEFAULT)
    val searchFilterLiveData: LiveData<SearchFilterOptions> = _searchFilterLiveData
    val searchFilter: SearchFilterOptions
        get() = _searchFilterLiveData.valueCompat

    fun setSearchFilter(filter: SearchFilterOptions) {
        if (_searchFilterLiveData.valueCompat == filter) {
            return
        }
        // A filter change invalidates the cached base set: size/time/type constraints differ, so
        // refining the old set would give wrong results. The next query keystroke will re-walk.
        baseSearchResult = null
        _searchFilterLiveData.value = filter
    }

    /**
     * Launches (or replaces) a search with [options]. No-op when the same options are already
     * active, which avoids restarting the traversal on unrelated LiveData re-emissions.
     */
    fun search(options: SearchOptions) {
        val searchState = _searchStateLiveData.valueCompat
        if (searchState.isSearching && searchState.options == options) {
            return
        }
        // A fresh search discards any cached base result set used for in-result refinement.
        baseSearchResult = null
        _searchStateLiveData.value = SearchState(true, options)
    }

    /** Convenience overload for the common case of a name-only query. */
    fun search(query: String) {
        search(buildOptions(query))
    }

    /**
     * Snapshot of the last completed traversal, kept so that [refine] can narrow it without
     * re-walking the tree. Cleared whenever a new search is launched or the search ends.
     */
    private var baseSearchResult: List<FileItem>? = null

    /**
     * Whether in-result refinement is available right now (a completed search with a cached base
     * set). The fragment uses this to enable the "filter in results" toggle.
     */
    val canRefine: Boolean
        get() = baseSearchResult != null

    /**
     * Narrows the current search results to those whose name also matches [query], reusing the
     * cached base set instead of re-walking. Only valid while [canRefine] is true; falls back to a
     * full [search] otherwise.
     */
    fun refine(query: String) {
        val base = baseSearchResult
        if (base == null) {
            search(query)
            return
        }
        val options = buildOptions(query)
        // Name filtering only: size/time/type already applied when the base set was produced, and
        // re-applying them would be a no-op against already-filtered items.
        val refined = base.filter { options.matchesName(it.name) }
        // Guard the searchState update so the SwitchMap does not swap in a new traversal source.
        isRefining = true
        _searchStateLiveData.value = SearchState(true, options)
        _fileListLiveData.value = Success(refined)
        isRefining = false
    }

    // True only during a refine() call; the SwitchMap checks this to avoid relaunching traversal.
    @Volatile
    private var isRefining = false

    /**
     * Builds [SearchOptions] from the current query and the editable filter. Used by the fragment
     * when committing the filter panel, and exposed so the search view can re-run with the same
     * filters after a query change.
     */
    fun buildOptions(query: String): SearchOptions {
        val filter = searchFilter
        return SearchOptions(
            query = query,
            isRegex = filter.isRegex,
            isRecursive = filter.isRecursive,
            mimeType = filter.mimeType,
            minSize = filter.minSize,
            maxSize = filter.maxSize,
            startTime = filter.startTime,
            endTime = filter.endTime,
            searchContent = filter.searchContent
        )
    }

    fun stopSearching() {
        val searchState = _searchStateLiveData.valueCompat
        if (!searchState.isSearching) {
            return
        }
        baseSearchResult = null
        _searchStateLiveData.value = SearchState.DEFAULT
    }

    private val _fileListLiveData =
        FileListSwitchMapLiveData(currentPathLiveData, _searchStateLiveData)
    val fileListLiveData: LiveData<Stateful<List<FileItem>>>
        get() = _fileListLiveData
    val fileListStateful: Stateful<List<FileItem>>
        get() = _fileListLiveData.valueCompat

    fun reload() {
        val path = currentPath
        if (path.isArchivePath) {
            path.archiveRefresh()
        }
        // Drop cached directory sizes so they recompute against the refreshed listing.
        DirectorySizeCalculator.clear()
        _fileListLiveData.reload()
    }

    val searchViewExpandedLiveData = MutableLiveData(false)
    var isSearchViewExpanded: Boolean
        get() = searchViewExpandedLiveData.valueCompat
        set(value) {
            if (searchViewExpandedLiveData.valueCompat == value) {
                return
            }
            searchViewExpandedLiveData.value = value
        }

    private val _searchViewQueryLiveData = MutableLiveData("")
    var searchViewQuery: String
        get() = _searchViewQueryLiveData.valueCompat
        set(value) {
            if (_searchViewQueryLiveData.valueCompat == value) {
                return
            }
            _searchViewQueryLiveData.value = value
        }

    val breadcrumbLiveData: LiveData<BreadcrumbData> = BreadcrumbLiveData(trailLiveData)
    val canNavigateUpBreadcrumb: Boolean
        get() = breadcrumbLiveData.valueCompat.selectedIndex > 0

    private val _viewTypeLiveData = FileViewTypeLiveData(currentPathLiveData)
    val viewTypeLiveData: LiveData<FileViewType> = _viewTypeLiveData
    var viewType: FileViewType
        get() = _viewTypeLiveData.valueCompat
        set(value) {
            _viewTypeLiveData.putValue(value)
        }

    private val _sortOptionsLiveData = FileSortOptionsLiveData(currentPathLiveData)
    val sortOptionsLiveData: LiveData<FileSortOptions> = _sortOptionsLiveData
    val sortOptions: FileSortOptions
        get() = _sortOptionsLiveData.valueCompat

    fun setSortBy(by: By) = _sortOptionsLiveData.putBy(by)

    fun setSortOrder(order: Order) = _sortOptionsLiveData.putOrder(order)

    fun setSortDirectoriesFirst(isDirectoriesFirst: Boolean) =
        _sortOptionsLiveData.putIsDirectoriesFirst(isDirectoriesFirst)

    private val _viewSortPathSpecificLiveData =
        FileViewSortPathSpecificLiveData(currentPathLiveData)
    val viewSortPathSpecificLiveData: LiveData<Boolean>
        get() = _viewSortPathSpecificLiveData
    var isViewSortPathSpecific: Boolean
        get() = _viewSortPathSpecificLiveData.valueCompat
        set(value) {
            _viewSortPathSpecificLiveData.putValue(value)
        }

    private val _pickOptionsLiveData = MutableLiveData<PickOptions?>()
    val pickOptionsLiveData: LiveData<PickOptions?>
        get() = _pickOptionsLiveData
    var pickOptions: PickOptions?
        get() = _pickOptionsLiveData.value
        set(value) {
            _pickOptionsLiveData.value = value
        }

    var isCreateFileNameEditInitialized: Boolean = false

    private val _selectedFilesLiveData = MutableLiveData(fileItemSetOf())
    val selectedFilesLiveData: LiveData<FileItemSet>
        get() = _selectedFilesLiveData
    val selectedFiles: FileItemSet
        get() = _selectedFilesLiveData.valueCompat

    fun selectFile(file: FileItem, selected: Boolean) {
        selectFiles(fileItemSetOf(file), selected)
    }

    fun selectFiles(files: FileItemSet, selected: Boolean) {
        val selectedFiles = _selectedFilesLiveData.valueCompat
        if (selectedFiles === files) {
            if (!selected && selectedFiles.isNotEmpty()) {
                selectedFiles.clear()
                _selectedFilesLiveData.value = selectedFiles
            }
            return
        }
        var changed = false
        for (file in files) {
            changed = changed or if (selected) {
                selectedFiles.add(file)
            } else {
                selectedFiles.remove(file)
            }
        }
        if (changed) {
            _selectedFilesLiveData.value = selectedFiles
        }
    }

    fun replaceSelectedFiles(files: FileItemSet) {
        val selectedFiles = _selectedFilesLiveData.valueCompat
        if (selectedFiles == files) {
            return
        }
        selectedFiles.clear()
        selectedFiles.addAll(files)
        _selectedFilesLiveData.value = selectedFiles
    }

    fun clearSelectedFiles() {
        val selectedFiles = _selectedFilesLiveData.valueCompat
        if (selectedFiles.isEmpty()) {
            return
        }
        selectedFiles.clear()
        _selectedFilesLiveData.value = selectedFiles
    }

    val pasteStateLiveData: LiveData<PasteState> = _pasteStateLiveData
    val pasteState: PasteState
        get() = _pasteStateLiveData.valueCompat

    fun addToPasteState(copy: Boolean, files: FileItemSet) {
        val pasteState = _pasteStateLiveData.valueCompat
        var changed = false
        if (pasteState.copy != copy) {
            changed = pasteState.files.isNotEmpty()
            pasteState.files.clear()
            pasteState.copy = copy
        }
        changed = changed or pasteState.files.addAll(files)
        if (changed) {
            _pasteStateLiveData.value = pasteState
        }
    }

    fun clearPasteState() {
        val pasteState = _pasteStateLiveData.valueCompat
        if (pasteState.files.isEmpty()) {
            return
        }
        pasteState.files.clear()
        _pasteStateLiveData.value = pasteState
    }

    private val _isRequestingStorageAccessLiveData = MutableLiveData(false)
    var isStorageAccessRequested: Boolean
        get() = _isRequestingStorageAccessLiveData.valueCompat
        set(value) {
            _isRequestingStorageAccessLiveData.value = value
        }

    private val _isRequestingNotificationPermissionLiveData = MutableLiveData(false)
    var isNotificationPermissionRequested: Boolean
        get() = _isRequestingNotificationPermissionLiveData.valueCompat
        set(value) {
            _isRequestingNotificationPermissionLiveData.value = value
        }

    override fun onCleared() {
        _fileListLiveData.close()
    }

    companion object {
        private val _pasteStateLiveData = MutableLiveData(PasteState())
    }

    private inner class FileListSwitchMapLiveData(
        private val pathLiveData: LiveData<Path>,
        private val searchStateLiveData: LiveData<SearchState>
    ) : MediatorLiveData<Stateful<List<FileItem>>>(), Closeable {
        private var liveData: CloseableLiveData<Stateful<List<FileItem>>>? = null

        init {
            addSource(pathLiveData) { updateSource() }
            addSource(searchStateLiveData) { updateSource() }
        }

        private fun updateSource() {
            // A refine() updates searchState too (so the query/highlight follow), but it has
            // already produced the filtered list itself; relaunching the traversal here would
            // discard that work and re-walk the tree. Skip the source swap in that case.
            if (isRefining) {
                return
            }
            liveData?.let {
                removeSource(it)
                it.close()
            }
            val path = pathLiveData.valueCompat
            val searchState = searchStateLiveData.valueCompat
            val liveData = if (searchState.isSearching) {
                SearchFileListLiveData(path, searchState.options)
            } else {
                FileListLiveData(path)
            }
            this.liveData = liveData
            addSource(liveData) {
                // Cache the completed traversal so refine() can narrow it without re-walking.
                if (liveData is SearchFileListLiveData && it is Success) {
                    baseSearchResult = it.value
                }
                // Kick off async directory-size computation for any directories in the new list,
                // so folders show their true recursive size once computed.
                if (it is Success) {
                    val directories = it.value.asSequence()
                        .filter { file -> file.attributes.isDirectory }
                        .map { file -> file.path }
                        .toList()
                    if (directories.isNotEmpty()) {
                        DirectorySizeCalculator.requestSizes(directories)
                    }
                }
                value = it
            }
        }

        fun reload() {
            when (val liveData = liveData) {
                is FileListLiveData -> liveData.loadValue()
                is SearchFileListLiveData -> liveData.loadValue()
            }
        }

        override fun close() {
            liveData?.let {
                removeSource(it)
                it.close()
                this.liveData = null
            }
        }
    }
}
