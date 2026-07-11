/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.appcompat.widget.Toolbar

/**
 * Boundary between a [FileListFragment] (a pane) and its host (currently [FileListActivity], in a
 * dual-pane future a dedicated host fragment/activity). The fragment talks to the outside world
 * exclusively through this interface rather than `requireActivity() as AppCompatActivity`, so the
 * coupling is explicit and a pane can be hosted by something other than the activity.
 *
 * Introduced as the first step of the dual-pane refactor: for now [FileListActivity] is the sole
 * implementor and every method delegates to what the fragment used to do inline, so there is zero
 * behaviour change. Later stages (toolbar/drawer hoisting, second pane) build on this seam.
 */
interface FileListFragmentHost {
    /** Whether the host is in a large-screen landscape configuration (tablet dual-pane eligible). */
    val hasSw600Dp: Boolean

    /** Whether the host is currently in landscape orientation. */
    val isLandscape: Boolean

    /**
     * Sets the host's support action bar to [toolbar]. In single-pane this is the activity's
     * support action bar; in dual-pane the active pane's toolbar takes this role.
     */
    fun setSupportToolbar(toolbar: Toolbar)

    /** Invalidates the host's options menu so it rebuilds on the next prepare pass. */
    fun invalidateOptionsMenu()

    /** Sets the host's title (action bar title / window title). */
    fun setTitle(title: CharSequence)

    /**
     * Finishes the host activity. Used by the picker flow to terminate GET_CONTENT/OPEN_DOCUMENT
     * etc.; inherently a host-level concern since the whole task ends.
     */
    fun finish()
}
