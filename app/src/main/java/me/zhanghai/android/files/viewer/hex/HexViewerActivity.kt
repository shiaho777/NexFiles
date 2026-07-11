/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs

class HexViewerActivity : AppActivity() {
    private lateinit var fragment: HexViewerFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val path = intent.extraPath ?: throw IllegalArgumentException("No path in intent")
            fragment = HexViewerFragment().putArgs(HexViewerFragment.Args(path))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        } else {
            fragment = supportFragmentManager.findFragmentById(android.R.id.content) as HexViewerFragment
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (fragment.view != null) {
            if (fragment.onSupportNavigateUp()) return true
            finish()
            return true
        }
        return super.onSupportNavigateUp()
    }
}
