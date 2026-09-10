/*
 * Copyright (c) NexFiles contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.nexfiles.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which classes/methods ART actually executes during a cold start and the first file
 * list render + scroll, producing the app's baseline profile so those paths get AOT-compiled
 * at install time instead of JIT-ing during real use. Regenerate after significant code path
 * changes with `./gradlew :benchmark:generateBaselineProfile` on a rooted device or emulator
 * (API 28+; the app's release variant is built and installed automatically).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "me.zhanghai.android.nexfiles",
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        // Exercise the list pipeline: directory load, async diff and RecyclerView binding.
        val list = device.findObject(By.scrollable(true))
        if (list != null) {
            list.fling(Direction.DOWN)
            list.fling(Direction.UP)
        }
    }
}
