/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.commitNow
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.navigation.NavigationFragment
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.DrawerLayoutOnBackPressedCallback
import me.zhanghai.android.files.ui.PersistentDrawerLayout
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.hasSw600Dp
import me.zhanghai.android.files.util.isOrientationLandscape
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.valueCompat

class FileListActivity : AppActivity(), FileListFragmentHost, NavigationFragment.Listener {
    private var drawerLayout: DrawerLayout? = null
    private var persistentDrawerLayout: PersistentDrawerLayout? = null
    private var paneContainerSecondary: View? = null
    private var paneDivider: View? = null

    private lateinit var navigationFragment: NavigationFragment

    private var activePaneTag: String = TAG_PRIMARY

    private val activePathLiveData = MediatorLiveData<Path>()
    private var activePathSource: LiveData<Path>? = null

    private var dualPaneSettingEnabled: Boolean =
        Settings.FILE_LIST_DUAL_PANE.valueCompat

    override val hasSw600Dp: Boolean
        get() = (this as Context).hasSw600Dp

    override val isLandscape: Boolean
        get() = isOrientationLandscape

    override val isDualPane: Boolean
        get() = isDualPaneLayoutAvailable && dualPaneSettingEnabled && !isPickerIntent(intent)

    private val isDualPaneLayoutAvailable: Boolean
        get() = hasSw600Dp && isLandscape && paneContainerSecondary != null

    private val activePane: FileListFragment?
        get() = supportFragmentManager.findFragmentByTag(activePaneTag) as? FileListFragment

    private val primaryPane: FileListFragment?
        get() = supportFragmentManager.findFragmentByTag(TAG_PRIMARY) as? FileListFragment

    private val secondaryPane: FileListFragment?
        get() = supportFragmentManager.findFragmentByTag(TAG_SECONDARY) as? FileListFragment

    override fun setSupportToolbar(toolbar: Toolbar) {
        setSupportActionBar(toolbar)
    }

    override fun invalidateOptionsMenu() {
        super.invalidateOptionsMenu()
    }

    override fun finish() {
        super.finish()
    }

    override fun openDrawer() {
        val drawer = drawerLayout
        if (drawer != null) {
            drawer.openDrawer(GravityCompat.START)
            return
        }
        val persistent = persistentDrawerLayout
        if (persistent != null) {
            Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(
                !Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.valueCompat
            )
        }
    }

    override fun closeDrawer() {
        drawerLayout?.closeDrawer(GravityCompat.START)
    }

    override fun isDrawerOpen(): Boolean {
        return drawerLayout?.isDrawerOpen(GravityCompat.START) == true
    }

    override fun isPersistentDrawerOpen(): Boolean {
        return persistentDrawerLayout?.isDrawerOpen(GravityCompat.START) == true
    }

    override fun requestActivePane(pane: FileListFragment) {
        val tag = pane.tag ?: return
        if (activePaneTag == tag) {
            return
        }
        activePaneTag = tag
        updateActivePaneVisuals()
        bindActivePathSource()
        invalidateOptionsMenu()
        activePane?.onBecameActivePane()
    }

    override fun swapPanes() {
        val primary = primaryPane ?: return
        val secondary = secondaryPane ?: return
        if (!isDualPane) {
            return
        }
        val primaryPath = primary.currentPath
        val secondaryPath = secondary.currentPath
        primary.navigateToRoot(secondaryPath)
        secondary.navigateToRoot(primaryPath)
    }

    override fun openInOtherPane(path: Path) {
        if (!isDualPane) {
            return
        }
        val other = if (activePaneTag == TAG_PRIMARY) secondaryPane else primaryPane
        other?.navigateToRoot(path)
        other?.let { requestActivePane(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.file_list_activity)
        drawerLayout = findViewById(R.id.drawerLayout)
        persistentDrawerLayout = findViewById(R.id.persistentDrawerLayout)
        paneContainerSecondary = findViewById(R.id.paneContainerSecondary)
        paneDivider = findViewById(R.id.paneDivider)

        if (savedInstanceState == null) {
            navigationFragment = NavigationFragment()
            supportFragmentManager.commitNow {
                add(R.id.navigationFragment, navigationFragment)
            }
        } else {
            navigationFragment = supportFragmentManager.findFragmentById(R.id.navigationFragment)
                as NavigationFragment
            activePaneTag = savedInstanceState.getString(STATE_ACTIVE_PANE_TAG, TAG_PRIMARY)
        }

        drawerLayout?.let { drawer ->
            onBackPressedDispatcher.addCallback(this, DrawerLayoutOnBackPressedCallback(drawer))
        }
        persistentDrawerLayout?.let {
            Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.observe(this) { open ->
                if (open) {
                    it.openDrawer(GravityCompat.START)
                } else {
                    it.closeDrawer(GravityCompat.START)
                }
                primaryPane?.onHostDrawerStateChanged()
                secondaryPane?.onHostDrawerStateChanged()
            }
        }

        Settings.FILE_LIST_DUAL_PANE.observe(this) { enabled ->
            if (dualPaneSettingEnabled == enabled) {
                return@observe
            }
            dualPaneSettingEnabled = enabled
            if (!isDualPaneLayoutAvailable) {
                return@observe
            }
            ensurePanes(isNew = false)
            applyPaneChromeVisibility()
            updateActivePaneVisuals()
            bindActivePathSource()
            invalidateOptionsMenu()
        }

        ensurePanes(savedInstanceState == null)
        applyPaneChromeVisibility()
        updateActivePaneVisuals()
        bindActivePathSource()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ACTIVE_PANE_TAG, activePaneTag)
    }

    private fun applyPaneChromeVisibility() {
        val dual = isDualPane
        paneContainerSecondary?.isVisible = dual
        paneDivider?.isVisible = dual
    }

    private fun ensurePanes(isNew: Boolean) {
        val dualPane = isDualPane
        val fm = supportFragmentManager
        fm.executePendingTransactions()
        if (isNew) {
            val primaryArgs = FileListFragment.Args(intent)
            if (dualPane) {
                val secondaryPath = secondaryInitialPath()
                val secondaryIntent = Intent(Intent.ACTION_MAIN).apply {
                    extraPath = secondaryPath
                }
                fm.commitNow {
                    add(
                        R.id.paneContainerPrimary,
                        FileListFragment().putArgs(primaryArgs),
                        TAG_PRIMARY
                    )
                    add(
                        R.id.paneContainerSecondary,
                        FileListFragment().putArgs(FileListFragment.Args(secondaryIntent)),
                        TAG_SECONDARY
                    )
                }
            } else {
                val containerId = if (findViewById<View>(R.id.paneContainer) != null) {
                    R.id.paneContainer
                } else {
                    R.id.paneContainerPrimary
                }
                fm.commitNow {
                    add(
                        containerId,
                        FileListFragment().putArgs(primaryArgs),
                        TAG_PRIMARY
                    )
                }
            }
            activePaneTag = TAG_PRIMARY
            return
        }

        val primary = fm.findFragmentByTag(TAG_PRIMARY) as? FileListFragment
        val secondary = fm.findFragmentByTag(TAG_SECONDARY) as? FileListFragment
        val singleContainerId = if (findViewById<View>(R.id.paneContainer) != null) {
            R.id.paneContainer
        } else {
            R.id.paneContainerPrimary
        }
        fm.commitNow {
            if (dualPane) {
                if (primary != null && primary.id != R.id.paneContainerPrimary) {
                    remove(primary)
                    add(R.id.paneContainerPrimary, primary, TAG_PRIMARY)
                } else if (primary == null) {
                    add(
                        R.id.paneContainerPrimary,
                        FileListFragment().putArgs(FileListFragment.Args(intent)),
                        TAG_PRIMARY
                    )
                }
                if (secondary != null && secondary.id != R.id.paneContainerSecondary) {
                    remove(secondary)
                    add(R.id.paneContainerSecondary, secondary, TAG_SECONDARY)
                } else if (secondary == null) {
                    val secondaryIntent = Intent(Intent.ACTION_MAIN).apply {
                        extraPath = secondaryInitialPath(primary?.currentPath)
                    }
                    add(
                        R.id.paneContainerSecondary,
                        FileListFragment().putArgs(FileListFragment.Args(secondaryIntent)),
                        TAG_SECONDARY
                    )
                }
            } else {
                if (secondary != null) {
                    remove(secondary)
                }
                if (primary != null && primary.id != singleContainerId) {
                    remove(primary)
                    add(singleContainerId, primary, TAG_PRIMARY)
                } else if (primary == null) {
                    add(
                        singleContainerId,
                        FileListFragment().putArgs(FileListFragment.Args(intent)),
                        TAG_PRIMARY
                    )
                }
                activePaneTag = TAG_PRIMARY
            }
        }
        if (dualPane && activePaneTag != TAG_PRIMARY && activePaneTag != TAG_SECONDARY) {
            activePaneTag = TAG_PRIMARY
        }
    }

    private fun secondaryInitialPath(primaryPath: Path? = null): Path {
        val path = primaryPath
            ?: intent.extraPath
            ?: Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
        return path.parent ?: Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
    }

    private fun updateActivePaneVisuals() {
        primaryPane?.setPaneActive(activePaneTag == TAG_PRIMARY)
        secondaryPane?.setPaneActive(isDualPane && activePaneTag == TAG_SECONDARY)
        if (!isDualPane) {
            primaryPane?.setPaneActive(true)
        }
    }

    private fun bindActivePathSource() {
        activePathSource?.let { activePathLiveData.removeSource(it) }
        val source = activePane?.currentPathLiveData
        activePathSource = source
        if (source != null) {
            activePathLiveData.addSource(source) { activePathLiveData.value = it }
        }
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (activePane?.onKeyShortcut(keyCode, event) == true) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override val currentPath: Path
        get() = activePane?.currentPath
            ?: Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat

    override fun navigateTo(path: Path) {
        activePane?.navigateTo(path)
    }

    override fun navigateToRoot(path: Path) {
        activePane?.navigateToRoot(path)
    }

    override fun navigateToDefaultRoot() {
        activePane?.navigateToDefaultRoot()
    }

    override fun observeCurrentPath(owner: LifecycleOwner, observer: (Path) -> Unit) {
        activePathLiveData.observe(owner, observer)
    }

    override fun closeNavigationDrawer() {
        closeDrawer()
    }

    companion object {
        const val TAG_PRIMARY = "pane_primary"
        const val TAG_SECONDARY = "pane_secondary"
        private const val STATE_ACTIVE_PANE_TAG = "active_pane_tag"

        fun isPickerIntent(intent: Intent): Boolean {
            return when (intent.action) {
                Intent.ACTION_GET_CONTENT,
                Intent.ACTION_OPEN_DOCUMENT,
                Intent.ACTION_CREATE_DOCUMENT,
                Intent.ACTION_OPEN_DOCUMENT_TREE -> true
                else -> false
            }
        }

        fun createViewIntent(path: Path): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_VIEW)
                .apply { extraPath = path }
    }

    class OpenFileContract : ActivityResultContract<List<MimeType>, Path?>() {
        override fun createIntent(context: Context, input: List<MimeType>): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT)
                .setType(MimeType.ANY.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, input.map { it.value }.toTypedArray())

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class CreateFileContract : ActivityResultContract<Triple<MimeType, String?, Path?>, Path?>() {
        override fun createIntent(
            context: Context,
            input: Triple<MimeType, String?, Path?>
        ): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_CREATE_DOCUMENT)
                .setType(input.first.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .apply {
                    input.second?.let { putExtra(Intent.EXTRA_TITLE, it) }
                    input.third?.let { extraPath = it }
                }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class OpenDirectoryContract : ActivityResultContract<Path?, Path?>() {
        override fun createIntent(context: Context, input: Path?): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .apply { input?.let { extraPath = it } }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }
}
