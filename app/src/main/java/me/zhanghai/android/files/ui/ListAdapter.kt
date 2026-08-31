/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

abstract class ListAdapter<T : Any, VH : RecyclerView.ViewHolder>(
    callback: DiffUtil.ItemCallback<T>
) : RecyclerView.Adapter<VH>() {
    // Diffing runs off the main thread (AsyncListDiffer): O(n·diff) work no longer janks the UI
    // on large directories, and superseded generations are dropped instead of queueing up.
    private val listDiffer = AsyncListDiffer(AdapterListUpdateCallback(this), callback)

    val list: List<T>
        get() = listDiffer.list

    override fun getItemCount(): Int = list.size

    fun getItem(position: Int): T = list[position]

    // Disable stable IDs and only let the list callback instruct animation properly.
    final override fun getItemId(position: Int): Long = RecyclerView.NO_ID

    open fun refresh() {
        val list = listDiffer.list
        listDiffer.list = emptyList()
        listDiffer.list = list
    }

    open fun replace(list: List<T>, clear: Boolean) {
        // An identical instance can't produce any diff; skip the O(n log n) calculation entirely.
        // Directory listings and search refreshes frequently republish the same list reference.
        if (!clear && list === listDiffer.list) {
            return
        }
        if (clear) {
            listDiffer.list = emptyList()
        }
        listDiffer.list = list
    }

    open fun clear() {
        listDiffer.list = emptyList()
    }
}