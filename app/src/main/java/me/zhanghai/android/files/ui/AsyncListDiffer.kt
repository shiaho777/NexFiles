/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback

/**
 * A drop-in replacement for the synchronous [ListDiffer] that computes diffs on a background
 * thread, following androidx's AsyncListDiffer model:
 *
 * - Assignments from the main thread are cheap: they snapshot the new list and hand it to the
 *   diff executor. A null result means a newer list is already queued, so nothing is dispatched.
 * - Lists arriving while a diff is running supersede it — only the newest list is ever diffed
 *   (generation token), so bursts of updates cost at most one diff.
 * - The current list is visible to readers immediately; [AdapterListUpdateCallback] dispatch
 *   happens on the main thread.
 *
 * Invariants: mutation of `_list` and `maxGeneration` happens on the main thread only; the diff
 * worker only reads its captured snapshot and writes back via the main handler.
 */
class AsyncListDiffer<T : Any>(
    private val updateCallback: ListUpdateCallback,
    private val diffCallback: DiffUtil.ItemCallback<T>
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var _list: List<T> = emptyList()

    var list: List<T>
        get() = _list
        set(newList) {
            submitList(newList, null)
        }

    private var maxGeneration: Long = 0

    fun submitList(newList: List<T>, commitCallback: Runnable?) {
        // Commit callbacks are invoked in submission order; a snapshot keeps the pair coherent
        // even if a newer generation supersedes this one before the worker starts.
        val snapshot = _list
        val generation = ++maxGeneration
        if (newList === snapshot || newList.isEmpty() && snapshot.isEmpty()) {
            commitCallback?.run()
            return
        }
        if (newList.isEmpty()) {
            val oldListSize = snapshot.size
            _list = emptyList()
            updateCallback.onRemoved(0, oldListSize)
            commitCallback?.run()
            return
        }
        if (snapshot.isEmpty()) {
            _list = newList
            updateCallback.onInserted(0, newList.size)
            commitCallback?.run()
            return
        }
        diffExecutor.execute {
            val result = DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = snapshot.size

                    override fun getNewListSize(): Int = newList.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int
                    ): Boolean =
                        diffCallback.areItemsTheSame(
                            snapshot[oldItemPosition],
                            newList[newItemPosition]
                        )

                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int
                    ): Boolean =
                        diffCallback.areContentsTheSame(
                            snapshot[oldItemPosition],
                            newList[newItemPosition]
                        )

                    override fun getChangePayload(
                        oldItemPosition: Int,
                        newItemPosition: Int
                    ): Any? =
                        diffCallback.getChangePayload(
                            snapshot[oldItemPosition],
                            newList[newItemPosition]
                        )
                }
            )
            mainHandler.post {
                if (generation != maxGeneration) {
                    // A newer list was submitted while we were diffing; it owns the dispatch.
                    return@post
                }
                _list = newList
                result.dispatchUpdatesTo(updateCallback)
                commitCallback?.run()
            }
        }
    }

    companion object {
        // Dedicated single thread keeps diff ordering deterministic; it's daemon so an exiting
        // process doesn't wait on it.
        private val diffExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "AsyncListDiffer").apply { isDaemon = true }
            }
    }
}
