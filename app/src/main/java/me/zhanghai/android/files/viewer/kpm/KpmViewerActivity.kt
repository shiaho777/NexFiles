/*
 * Copyright (c) NexFiles contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.kpm

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs

class KpmViewerActivity : AppActivity() {
    private lateinit var fragment: KpmViewerFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val path = intent.extraPath
                ?: throw IllegalArgumentException("No path in intent")
            fragment = KpmViewerFragment().putArgs(KpmViewerFragment.Args(path))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        } else {
            fragment = supportFragmentManager.findFragmentById(android.R.id.content)
                as KpmViewerFragment
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (fragment.view != null) {
            finish()
            return true
        }
        return super.onSupportNavigateUp()
    }
}
