/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.fragment.app.commit
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs

/**
 * Hosts [SmaliEditorFragment] for editing a single DEX class's smali source. The fragment needs
 * both the DEX file path and the class type descriptor to know which class to disassemble and
 * where to write back the reassembled result.
 */
class SmaliEditorActivity : AppActivity() {
    private lateinit var fragment: SmaliEditorFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<android.view.View>(android.R.id.content)
        if (savedInstanceState == null) {
            val path = intent.extraPath ?: throw IllegalArgumentException("No path in intent")
            val classType = intent.getStringExtra(EXTRA_CLASS_TYPE)
                ?: throw IllegalArgumentException("No class type in intent")
            fragment = SmaliEditorFragment().putArgs(SmaliEditorFragment.Args(path, classType))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        } else {
            fragment = supportFragmentManager.findFragmentById(android.R.id.content)
                as SmaliEditorFragment
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.smali_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> { fragment.save(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (fragment.view != null) {
            // Defer to the fragment so it can intercept unsaved changes.
            if (fragment.onSupportNavigateUp()) return true
            finish()
            return true
        }
        return super.onSupportNavigateUp()
    }

    companion object {
        const val EXTRA_CLASS_TYPE = "me.zhanghai.android.files.extra.CLASS_TYPE"
    }
}
