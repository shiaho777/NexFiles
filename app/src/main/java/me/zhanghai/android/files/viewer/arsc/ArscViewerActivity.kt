/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.arsc

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs

class ArscViewerActivity : AppActivity() {
    private lateinit var fragment: ArscViewerFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val path = intent.extraPath ?: throw IllegalArgumentException("No path in intent")
            fragment = ArscViewerFragment().putArgs(ArscViewerFragment.Args(path))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        } else {
            fragment = supportFragmentManager.findFragmentById(android.R.id.content) as ArscViewerFragment
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (fragment.view != null) { finish(); return true }
        return super.onSupportNavigateUp()
    }
}
