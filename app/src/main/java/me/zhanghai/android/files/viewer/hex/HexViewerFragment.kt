/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */
package me.zhanghai.android.files.viewer.hex

import android.os.Bundle
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
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

/** Five-page bidirectional viewer, with bounded in-memory editing for files up to 1 MiB. */
class HexViewerFragment : Fragment(), HexEditByteDialogFragment.Listener,
    HexGoToOffsetDialogFragment.Listener, ConfirmCloseDialogFragment.Listener {
    private val args by args<Args>()
    private var binding: HexViewerFragmentBinding? = null
    private var fileSize = 0L
    private var editState: HexEditState? = null
    private val window = HexPageWindow()
    private var worker: Job? = null
    private var requests: Channel<Request>? = null
    private var busy = false
    private var requestVersion = 0L
    private lateinit var onBackPressedCallback: OnBackPressedCallback
    private data class Request(val offset: Long, val jump: Long?, val version: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = HexViewerFragmentBinding.inflate(inflater, container, false).also {
        binding = it
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = binding!!
        ui.recyclerView.layoutManager = LinearLayoutManager(context)
        ui.recyclerView.adapter = HexLineAdapter { line ->
            if (editState == null) showToast(R.string.hex_viewer_read_only)
            else HexEditByteDialogFragment.show(
                line.globalOffset, line.bytes[0].toInt() and 0xFF, this
            )
        }
        ui.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Ignore layout/anchor changes: only user scrolling loads adjacent pages.
                if (dy != 0) maybeLoadPage(dy)
            }
        })
        onBackPressedCallback = object : OnBackPressedCallback(editState?.isDirty == true) {
            override fun handleOnBackPressed() {
                ConfirmCloseDialogFragment.show(this@HexViewerFragment)
            }
        }
        addOnBackPressedCallback(onBackPressedCallback)
        startReader(ui)
    }

    private fun startReader(ui: HexViewerFragmentBinding) {
        val queue = Channel<Request>(Channel.CONFLATED)
        requests = queue
        val path = args.path
        // This worker is the sole owner of the reader, including close. A blocked provider read
        // finishes before cleanup; cancellation is checked between every provider operation.
        val reader = HexPageReader({ path.newInputStream() }, {
            try {
                val channel = path.newByteChannel()
                try {
                    channel.position(0L)
                } catch (e: Exception) {
                    try { channel.close() } catch (close: Exception) { e.addSuppressed(close) }
                    throw e
                }
                object : HexSeekableSource {
                    override fun position(offset: Long) { channel.position(offset) }
                    override fun read(buffer: ByteBuffer): Int = channel.read(buffer)
                    override fun close() = channel.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsupportedOperationException) {
                null
            } catch (e: IOException) {
                null
            }
        })
        busy = true
        ui.progress.isVisible = true
        worker = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val context = coroutineContext
                if (editState == null) {
                    val size = withContext(Dispatchers.IO) { path.size() }
                    fileSize = size.coerceAtLeast(0)
                    if (size in 0..EDIT_MAX_SIZE.toLong()) {
                        // Metadata may be stale: read at most limit + 1, never readBytes().
                        val page = withContext(Dispatchers.IO) {
                            reader.read(0, EDIT_MAX_SIZE + 1) { context.ensureActive() }
                        }
                        if (page.bytes.size <= EDIT_MAX_SIZE && page.eofOffset != null) {
                            editState = HexEditState(page.bytes)
                            fileSize = page.bytes.size.toLong()
                        } else {
                            fileSize = maxOf(fileSize, page.bytes.size.toLong())
                        }
                    }
                } else fileSize = editState!!.buffer.size.toLong()
                queue.trySend(Request(0, 0, requestVersion))
                for (request in queue) {
                    busy = true
                    ui.progress.isVisible = true
                    ui.errorText.isVisible = false
                    try {
                        val buffer = editState?.buffer
                        val page = if (buffer != null) {
                            val start = minOf(request.offset, buffer.size.toLong()).toInt()
                            val end = minOf(start + HEX_PAGE_SIZE, buffer.size)
                            HexPage(request.offset, buffer.copyOfRange(start, end),
                                if (end == buffer.size) end.toLong() else null)
                        } else withContext(Dispatchers.IO) {
                            reader.read(request.offset) { context.ensureActive() }
                        }
                        if (request.version != requestVersion) continue
                        page.eofOffset?.let { fileSize = minOf(fileSize, it) }
                        applyPage(ui, page, request.jump)
                        ui.recyclerView.isVisible = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ui.errorText.text = e.toString()
                        ui.errorText.isVisible = true
                    } finally {
                        if (binding === ui && request.version == requestVersion) {
                            busy = false
                            ui.progress.isVisible = editState?.isSaving == true
                            activity?.invalidateOptionsMenu()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (binding === ui) {
                    ui.errorText.text = e.toString()
                    ui.errorText.isVisible = true
                    busy = false
                    ui.progress.isVisible = false
                }
            } finally {
                // Cancellation must not prevent resource cleanup or become a displayed error.
                withContext(NonCancellable + Dispatchers.IO) {
                    try { reader.close() } catch (_: IOException) { }
                }
            }
        }
    }

    private fun applyPage(ui: HexViewerFragmentBinding, page: HexPage, jump: Long?) {
        val adapter = ui.recyclerView.adapter as HexLineAdapter
        val layout = ui.recyclerView.layoutManager as LinearLayoutManager
        val first = layout.findFirstVisibleItemPosition()
        val anchor = adapter.list.getOrNull(first)?.lineIndex
        val top = layout.findViewByPosition(first)?.let {
            layout.getDecoratedTop(it) - ui.recyclerView.paddingTop
        } ?: 0
        window.put(page, replace = jump != null)
        adapter.replace(window.lines())
        val position = (jump?.div(HEX_BYTES_PER_LINE) ?: anchor)?.let {
            adapter.findPositionById(it)
        } ?: RecyclerView.NO_POSITION
        if (position != RecyclerView.NO_POSITION) {
            layout.scrollToPositionWithOffset(position, if (jump != null) 0 else top)
        }
    }

    private fun maybeLoadPage(direction: Int) {
        val ui = binding ?: return
        if (busy) return
        val layout = ui.recyclerView.layoutManager as LinearLayoutManager
        val offset = if (direction < 0) {
            val first = window.firstOffset ?: return
            if (first == 0L || layout.findFirstVisibleItemPosition() >= 50) return
            (first - HEX_PAGE_SIZE).coerceAtLeast(0)
        } else {
            val end = window.endOffset ?: return
            val count = ui.recyclerView.adapter?.itemCount ?: return
            if (end >= fileSize || count - layout.findLastVisibleItemPosition() >= 50) return
            end
        }
        busy = requests?.trySend(Request(offset, null, requestVersion))?.isSuccess == true
    }

    override fun onGoToOffset(offset: Long) {
        if (binding == null) return
        if (offset < 0 || offset >= fileSize) {
            showToast(R.string.hex_viewer_offset_out_of_range)
            return
        }
        ++requestVersion
        busy = requests?.trySend(Request(
            offset / HEX_PAGE_SIZE * HEX_PAGE_SIZE, offset, requestVersion
        ))?.isSuccess == true
    }

    override fun onByteEdited(offset: Long, newByte: Int) {
        val ui = binding ?: return
        val state = editState ?: return
        if (newByte !in 0..255 || !state.edit(offset, newByte.toByte())) return
        window.edit(offset, newByte.toByte())
        val adapter = ui.recyclerView.adapter as HexLineAdapter
        adapter.replace(window.lines())
        onBackPressedCallback.isEnabled = state.isDirty
        activity?.invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.hex_viewer, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.action_save)?.apply {
            isVisible = editState?.isDirty == true
            isEnabled = editState?.isSaving != true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_save -> { save(); true }
        R.id.action_go_to_offset -> {
            HexGoToOffsetDialogFragment.show(fileSize, this)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun save() {
        val ui = binding ?: return
        val state = editState ?: return
        if (state.isSaving) return
        val snapshot = state.beginSave() ?: run {
            showToast(R.string.hex_viewer_no_changes)
            return
        }
        ui.progress.isVisible = true
        activity?.invalidateOptionsMenu()
        try {
            FileJobService.write(args.path, snapshot, requireContext()) { successful ->
                // Bookkeeping belongs to the captured buffer, not whichever view is now current.
                state.finishSave(successful)
                if (binding !== ui || editState !== state || !isAdded) return@write
                ui.progress.isVisible = busy
                onBackPressedCallback.isEnabled = state.isDirty
                showToast(if (successful) R.string.hex_viewer_saved else R.string.hex_viewer_save_failed)
                activity?.invalidateOptionsMenu()
            }
        } catch (e: Exception) {
            state.finishSave(false)
            ui.progress.isVisible = busy
            activity?.invalidateOptionsMenu()
            if (e is CancellationException) throw e
            showToast(R.string.hex_viewer_save_failed)
        }
    }

    override fun onDestroyView() {
        binding?.recyclerView?.adapter = null
        binding = null
        requests?.close()
        requests = null
        worker?.cancel()
        worker = null
        ++requestVersion
        window.clear()
        busy = false
        onBackPressedCallback.remove()
        super.onDestroyView()
    }

    override fun finish() { activity?.finish() }

    fun onSupportNavigateUp(): Boolean {
        if (binding == null || editState?.isDirty != true) return false
        onBackPressedCallback.handleOnBackPressed()
        return true
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs

    companion object {
        private const val EDIT_MAX_SIZE = 1024 * 1024
    }
}
