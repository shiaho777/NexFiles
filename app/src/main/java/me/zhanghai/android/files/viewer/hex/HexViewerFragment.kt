/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

import android.os.Bundle
import android.util.LongSparseArray
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.HexViewerFragmentBinding
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.viewer.text.ConfirmCloseDialogFragment
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Paged hex viewer and (for files ≤ [EDIT_MAX_SIZE]) editor.
 *
 * **Viewing**: the file is read in [PAGE_SIZE]-byte pages. Each page is converted to 256 [HexLine]
 * rows and appended to the adapter. When the user scrolls near the bottom of the loaded content,
 * the next page is fetched on a background coroutine — so arbitrarily large files can be browsed
 * without holding the whole file in memory.
 *
 * For large files that support random access ([Path.newByteChannel]), pages are read via
 * [SeekableByteChannel.position] — a true O(1) seek. For paths that don't support random access
 * (SAF, archive, remote), a page cache ([pageCache]) ensures each page is read from the stream
 * at most once, avoiding the O(n²) re-skip that would otherwise occur on each append.
 *
 * **Editing**: if the file fits within [EDIT_MAX_SIZE], the entire content is read into a mutable
 * [ByteArray] up front. Long-pressing a hex line opens [HexEditByteDialogFragment]; the edited
 * byte is written back to the buffer and the affected line is refreshed. The toolbar Save item
 * writes the modified buffer back through [FileJobService.write]. Files larger than the limit are
 * view-only (the edit menu item is hidden).
 *
 * **Go to offset**: the toolbar menu offers a jump-to-offset action that scrolls the list to the
 * line containing the user-specified hex offset, loading intervening pages as needed.
 *
 * The RecyclerView sits inside a [HorizontalScrollView] so the hex columns scroll horizontally
 * on narrow screens while the list scrolls vertically.
 */
class HexViewerFragment : Fragment(), HexEditByteDialogFragment.Listener,
    HexGoToOffsetDialogFragment.Listener, ConfirmCloseDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var binding: HexViewerFragmentBinding

    private var fileSize: Long = 0
    private var editableBuffer: ByteArray? = null
    private var isDirty: Boolean = false
    private var isLoading: Boolean = false
    private var loadedOffset: Long = 0 // How many bytes have been loaded into the list so far.

    /** True if [args.path] supports [Path.newByteChannel] for O(1) random access. */
    private var supportsRandomAccess: Boolean = true

    /** Cache of pages for files without random access: maps page-start offset → lines. */
    private val pageCache = LongSparseArray<List<HexLine>>()

    private val canEdit: Boolean
        get() = editableBuffer != null

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = HexViewerFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = HexLineAdapter { line -> onLineLongClick(line) }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                maybeLoadNextPage()
            }
        })
        onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                ConfirmCloseDialogFragment.show(this@HexViewerFragment)
            }
        }
        addOnBackPressedCallback(onBackPressedCallback)
        loadInitial()
    }

    // -----------------------------------------------------------------------------------
    //  Loading
    // -----------------------------------------------------------------------------------

    private fun loadInitial() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progress.isVisible = true
            binding.errorText.isVisible = false
            try {
                val info = withContext(Dispatchers.IO) { readFileInfo() }
                fileSize = info.fileSize
                editableBuffer = info.buffer
                supportsRandomAccess = info.supportsRandomAccess
                // Load the first page.
                loadPage(0)
                binding.recyclerView.isVisible = true
                if (!canEdit) {
                    showToast(R.string.hex_viewer_read_only)
                }
            } catch (e: Exception) {
                binding.errorText.text = e.toString()
                binding.errorText.isVisible = true
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    private data class FileInfo(
        val fileSize: Long,
        val buffer: ByteArray?,
        val supportsRandomAccess: Boolean
    )

    private fun readFileInfo(): FileInfo {
        val size = args.path.size()
        val buffer = if (size <= EDIT_MAX_SIZE) {
            args.path.newInputStream().use { it.readBytes() }
        } else {
            null
        }
        // Probe whether the path supports random access (SeekableByteChannel). Some providers
        // (SAF, archive) don't — we fall back to a page cache for those.
        val randomAccess = try {
            args.path.newByteChannel().use { it.position(0); true }
        } catch (e: Exception) {
            false
        }
        return FileInfo(size, buffer, randomAccess)
    }

    /**
     * Reads [PAGE_SIZE] bytes starting at [offset] and appends them as [HexLine]s to the adapter.
     * For editable files, reads from the in-memory buffer; otherwise reads from the file.
     */
    private fun loadPage(offset: Long) {
        if (isLoading || offset >= fileSize) return
        isLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val lines = withContext(Dispatchers.IO) {
                    val buffer = editableBuffer
                    if (buffer != null) {
                        buildLines(buffer, offset)
                    } else {
                        readLinesFromFile(offset)
                    }
                }
                val adapter = binding.recyclerView.adapter as HexLineAdapter
                adapter.addAll(lines)
                loadedOffset = offset + lines.size * BYTES_PER_LINE
            } catch (e: Exception) {
                binding.errorText.text = e.toString()
                binding.errorText.isVisible = true
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Extracts up to [LINES_PER_PAGE] lines from [buffer] starting at [offset].
     */
    private fun buildLines(buffer: ByteArray, offset: Long): List<HexLine> {
        val lines = mutableListOf<HexLine>()
        val startLine = (offset / BYTES_PER_LINE).toInt()
        val endLine = minOf(
            startLine + LINES_PER_PAGE,
            (buffer.size + BYTES_PER_LINE - 1) / BYTES_PER_LINE
        )
        for (lineIdx in startLine until endLine) {
            val lineOffset = lineIdx * BYTES_PER_LINE
            if (lineOffset >= buffer.size) break
            val validBytes = minOf(BYTES_PER_LINE, buffer.size - lineOffset)
            val byteSlice = ByteArray(BYTES_PER_LINE)
            System.arraycopy(buffer, lineOffset, byteSlice, 0, validBytes)
            lines.add(HexLine(lineIdx.toLong(), lineOffset.toLong(), byteSlice, validBytes))
        }
        return lines
    }

    /**
     * Reads [LINES_PER_PAGE] × [BYTES_PER_LINE] bytes from the file starting at [offset].
     *
     * If the path supports random access, uses [SeekableByteChannel.position] for an O(1) seek.
     * Otherwise, checks the [pageCache] first; if the page was already loaded, returns the cached
     * lines. If not cached, reads forward from the stream. This ensures each page is only read
     * once even without random access, avoiding O(n²) re-skip.
     */
    private fun readLinesFromFile(offset: Long): List<HexLine> {
        // Check the cache first (handles both random-access and stream fallback).
        val cached = pageCache.get(offset)
        if (cached != null) return cached

        val lines = if (supportsRandomAccess) {
            readLinesWithChannel(offset)
        } else {
            readLinesWithStream(offset)
        }
        // Cache the page so we never re-read it from the stream.
        pageCache.put(offset, lines)
        return lines
    }

    /**
     * Reads lines from [offset] using a [SeekableByteChannel] — true random access, O(1) seek.
     */
    private fun readLinesWithChannel(offset: Long): List<HexLine> {
        val lines = mutableListOf<HexLine>()
        args.path.newByteChannel().use { channel ->
            channel.position(offset)
            val startLine = (offset / BYTES_PER_LINE).toInt()
            for (lineIdx in startLine until startLine + LINES_PER_PAGE) {
                val byteSlice = ByteArray(BYTES_PER_LINE)
                val read = channel.read(ByteBuffer.wrap(byteSlice))
                if (read <= 0) break
                lines.add(HexLine(
                    lineIdx.toLong(), lineIdx.toLong() * BYTES_PER_LINE, byteSlice, read
                ))
            }
        }
        return lines
    }

    /**
     * Reads lines from [offset] using a forward-only stream. This is the fallback for paths that
     * don't support [SeekableByteChannel]. The [pageCache] ensures this is only called once per
     * page, so even without seek the total work is O(file_size), not O(n²).
     */
    private fun readLinesWithStream(offset: Long): List<HexLine> {
        val lines = mutableListOf<HexLine>()
        args.path.newInputStream().use { input ->
            var remaining = offset
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) return lines
                remaining -= skipped
            }
            val startLine = (offset / BYTES_PER_LINE).toInt()
            for (lineIdx in startLine until startLine + LINES_PER_PAGE) {
                val byteSlice = ByteArray(BYTES_PER_LINE)
                val read = readFully(input, byteSlice)
                if (read <= 0) break
                lines.add(HexLine(
                    lineIdx.toLong(), lineIdx.toLong() * BYTES_PER_LINE, byteSlice, read
                ))
            }
        }
        return lines
    }

    /** Reads up to [buf.size] bytes, blocking; returns the number read, or -1 at EOF. */
    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buf.size) {
            val read = input.read(buf, totalRead, buf.size - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return if (totalRead == 0) -1 else totalRead
    }

    /**
     * Checks whether we've scrolled close to the bottom of the loaded content and, if so, loads
     * the next page. The threshold is 50 lines.
     */
    private fun maybeLoadNextPage() {
        if (isLoading || loadedOffset >= fileSize) return
        val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val itemCount = binding.recyclerView.adapter?.itemCount ?: 0
        if (itemCount - lastVisible < 50) {
            loadPage(loadedOffset)
        }
    }

    /**
     * Jumps to the hex line containing [byteOffset], loading all intervening pages as needed.
     * Called when the user enters an offset in the go-to-offset dialog.
     */
    private fun jumpToOffset(byteOffset: Long) {
        if (byteOffset < 0 || byteOffset >= fileSize) {
            showToast(R.string.hex_viewer_offset_out_of_range)
            return
        }
        val targetLineIndex = (byteOffset / BYTES_PER_LINE).toInt()
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progress.isVisible = true
            try {
                // Collect all pages up to and including the one containing the target offset,
                // reading on a background thread.
                val allPages = withContext(Dispatchers.IO) {
                    val pages = mutableListOf<List<HexLine>>()
                    var pageStart = loadedOffset
                    while (pageStart <= byteOffset && pageStart < fileSize) {
                        val lines = if (editableBuffer != null) {
                            buildLines(editableBuffer!!, pageStart)
                        } else {
                            readLinesFromFile(pageStart)
                        }
                        if (lines.isEmpty()) break
                        pages.add(lines)
                        loadedOffset = pageStart + lines.size * BYTES_PER_LINE
                        pageStart = loadedOffset
                    }
                    pages
                }
                // Add the collected pages to the adapter on the main thread.
                val adapter = binding.recyclerView.adapter as HexLineAdapter
                for (lines in allPages) {
                    adapter.addAll(lines)
                }
                // Scroll to the target line.
                binding.recyclerView.scrollToPosition(targetLineIndex)
            } catch (e: Exception) {
                binding.errorText.text = e.toString()
                binding.errorText.isVisible = true
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    // -----------------------------------------------------------------------------------
    //  Editing
    // -----------------------------------------------------------------------------------

    private fun onLineLongClick(line: HexLine) {
        if (!canEdit) {
            showToast(R.string.hex_viewer_read_only)
            return
        }
        HexEditByteDialogFragment.show(line.globalOffset, line.bytes[0].toInt() and 0xFF, this)
    }

    override fun onByteEdited(offset: Long, newByte: Int) {
        val buffer = editableBuffer ?: return
        if (offset >= buffer.size) return
        buffer[offset.toInt()] = newByte.toByte()
        isDirty = true
        // Refresh the affected line in the adapter.
        val adapter = binding.recyclerView.adapter as HexLineAdapter
        val lineIdx = (offset / BYTES_PER_LINE).toInt()
        val lineOffset = lineIdx * BYTES_PER_LINE
        val validBytes = minOf(BYTES_PER_LINE, buffer.size - lineOffset)
        val byteSlice = ByteArray(BYTES_PER_LINE)
        System.arraycopy(buffer, lineOffset, byteSlice, 0, validBytes)
        val newLine = HexLine(lineIdx.toLong(), lineOffset.toLong(), byteSlice, validBytes)
        val position = adapter.findPositionById(lineIdx.toLong())
        if (position != RecyclerView.NO_POSITION) {
            adapter[position] = newLine
        }
        onBackPressedCallback.isEnabled = true
        requireActivity().invalidateOptionsMenu()
    }

    // -----------------------------------------------------------------------------------
    //  Go-to-offset dialog
    // -----------------------------------------------------------------------------------

    override fun onGoToOffset(offset: Long) {
        jumpToOffset(offset)
    }

    // -----------------------------------------------------------------------------------
    //  Menu
    // -----------------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.hex_viewer, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        // Only show the save item when editing is possible and there are unsaved changes.
        menu.findItem(R.id.action_save)?.isVisible = canEdit && isDirty
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> { save(); true }
            R.id.action_go_to_offset -> {
                HexGoToOffsetDialogFragment.show(fileSize, this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun save() {
        val buffer = editableBuffer ?: return
        if (!isDirty) {
            showToast(R.string.hex_viewer_no_changes)
            return
        }
        binding.progress.isVisible = true
        FileJobService.write(args.path, buffer, requireContext()) { successful ->
            binding.progress.isVisible = false
            if (successful) {
                isDirty = false
                onBackPressedCallback.isEnabled = false
                showToast(R.string.hex_viewer_saved)
                requireActivity().invalidateOptionsMenu()
            } else {
                showToast(R.string.hex_viewer_save_failed)
            }
        }
    }

    // -- ConfirmCloseDialogFragment.Listener --

    override fun finish() {
        requireActivity().finish()
    }

    /**
     * Called by the host activity on toolbar navigate-up. If there are unsaved edits, show the
     * confirmation dialog instead of finishing.
     */
    fun onSupportNavigateUp(): Boolean {
        if (isDirty) {
            onBackPressedCallback.handleOnBackPressed()
            return true
        }
        return false
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs

    companion object {
        private const val BYTES_PER_LINE = 16
        private const val LINES_PER_PAGE = 256
        private const val PAGE_SIZE = (LINES_PER_PAGE * BYTES_PER_LINE).toLong() // 4096
        // Files up to 1 MiB can be edited; larger files are view-only to avoid excessive memory use.
        private const val EDIT_MAX_SIZE = 1024 * 1024.toLong()
    }
}
