/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.databinding.HexLineItemBinding
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.layoutInflater

/**
 * Adapter for hex display rows. Each item is a [HexLine] rendered as a single monospace text line
 * (offset + hex bytes + ASCII). Long-pressing a line opens the byte-edit dialog for that line's
 * first byte; the fragment handles the actual editing flow.
 *
 * Uses [SimpleAdapter] for list management (replace/getItem/etc.), with stable IDs derived from
 * the line's absolute index in the file.
 */
class HexLineAdapter(
    private val onLineLongClick: (HexLine) -> Unit
) : SimpleAdapter<HexLine, HexLineAdapter.ViewHolder>() {

    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).lineIndex

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            HexLineItemBinding.inflate(parent.context.layoutInflater, parent, false),
            onLineLongClick
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = getItem(position)
        holder.binding.hexLineText.text = line.displayText
        holder.line = line
    }

    class ViewHolder(
        val binding: HexLineItemBinding,
        onLineLongClick: (HexLine) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        var line: HexLine? = null

        init {
            binding.root.setOnLongClickListener {
                line?.let { onLineLongClick(it) }
                true
            }
        }
    }
}
