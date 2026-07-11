/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.isFinished
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.toError
import me.zhanghai.android.files.util.toLoading
import java.io.ByteArrayOutputStream
import java.io.IOException

class DexEditorViewModel(val file: Path) : ViewModel() {
    private val _modelState = MutableStateFlow<DataState<DexEditorModel>>(DataState.Loading())
    val modelState = _modelState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _modelState.value = _modelState.value.toLoading()
            try {
                val model = runInterruptible(Dispatchers.IO) {
                    val size = file.size()
                    if (size > MAX_FILE_SIZE) {
                        throw IOException("DEX file size $size is too large")
                    }
                    file.newInputStream().use { DexEditorModel.read(it) }
                }
                currentCoroutineContext().ensureActive()
                _modelState.value = DataState.Success(model)
            } catch (e: CancellationException) {
                e.printStackTrace()
            } catch (e: Exception) {
                _modelState.value = _modelState.value.toError(e)
            }
        }
    }

    /**
     * Replaces [oldValue] with [newValue] in the in-memory model and marks the model dirty so
     * the next save serializes the change. Runs on a background dispatcher because dexlib2's
     * immutable rewriter walks every class.
     */
    fun replaceString(oldValue: String, newValue: String) {
        applyEdit { model -> model.replaceString(oldValue, newValue) }
    }

    /** Renames a class type descriptor and updates all cross-references. */
    fun renameClass(oldType: String, newType: String): Int =
        applyEditBlocking { model -> model.renameClass(oldType, newType) }

    /** Renames a method and updates all invoke references. */
    fun renameMethod(
        definingClass: String, name: String, parameters: List<String>, returnType: String,
        newName: String
    ): Int = applyEditBlocking {
        model.renameMethod(definingClass, name, parameters, returnType, newName)
    }

    /** Renames a field and updates all field access references. */
    fun renameField(
        definingClass: String, name: String, type: String, newName: String
    ): Int = applyEditBlocking {
        model.renameField(definingClass, name, type, newName)
    }

    /** Changes a method's signature (parameters + return type) and updates all references. */
    fun changeMethodSignature(
        definingClass: String, name: String,
        oldParameters: List<String>, oldReturnType: String,
        newParameters: List<String>, newReturnType: String
    ): Int = applyEditBlocking {
        model.changeMethodSignature(
            definingClass, name, oldParameters, oldReturnType, newParameters, newReturnType
        )
    }

    /**
     * Runs [block] on the model on a background dispatcher, updates state, and marks dirty. Used
     * by string replacement which is fire-and-forget (no return value needed from the caller).
     */
    private fun applyEdit(block: (DexEditorModel) -> Int) {
        val model = (_modelState.value as? DataState.Success)?.data ?: return
        viewModelScope.launch {
            _modelState.value = _modelState.value.toLoading()
            try {
                val count = withContext(Dispatchers.Default) { block(model) }
                currentCoroutineContext().ensureActive()
                _modelState.value = DataState.Success(model)
                lastReplaceCount = count
                isDirty = true
            } catch (e: CancellationException) {
                e.printStackTrace()
            } catch (e: Exception) {
                _modelState.value = _modelState.value.toError(e)
            }
        }
    }

    /**
     * Like [applyEdit] but returns the count synchronously. The model edit itself runs on
     * [Dispatchers.Default] via `runBlocking`, which is safe here because these are user-triggered
     * actions from dialog callbacks (not the main thread's event loop in the strict sense — they
     * come from dialog dismiss, and we accept the brief blocking for simplicity).
     */
    private fun applyEditBlocking(block: (DexEditorModel) -> Int): Int {
        val model = (_modelState.value as? DataState.Success)?.data ?: return 0
        val count = kotlinx.coroutines.runBlocking(Dispatchers.Default) { block(model) }
        if (count > 0) isDirty = true
        return count
    }

    @Volatile
    var lastReplaceCount: Int = 0
        private set

    @Volatile
    var isDirty: Boolean = false
        private set

    private val _writeFileState =
        MutableStateFlow<ActionState<Path, Unit>>(ActionState.Ready())
    val writeFileState = _writeFileState.asStateFlow()

    fun writeFile(path: Path, context: Context) {
        val model = (_modelState.value as? DataState.Success)?.data ?: return
        viewModelScope.launch {
            check(_writeFileState.value.isReady)
            _writeFileState.value = ActionState.Running(path)
            val bytes = withContext(Dispatchers.Default) {
                val out = ByteArrayOutputStream()
                model.write(out)
                out.toByteArray()
            }
            FileJobService.write(path, bytes, context) { successful ->
                if (successful) {
                    isDirty = false
                    // Reload so the saved model is the new baseline.
                    loadJob?.cancel()
                    loadJob = viewModelScope.launch {
                        _modelState.value = DataState.Success(model)
                    }
                }
                _writeFileState.value = if (successful) {
                    ActionState.Success(path, Unit)
                } else {
                    ActionState.Error(path, Throwable())
                }
            }
        }
    }

    fun finishWritingFile() {
        viewModelScope.launch {
            check(_writeFileState.value.isFinished)
            _writeFileState.value = ActionState.Ready()
        }
    }

    companion object {
        // dexlib2 buffers the whole file; cap at 16 MiB to stay within memory budget. Most DEX
        // files are well under this.
        private const val MAX_FILE_SIZE = 16 * 1024 * 1024.toLong()
    }
}
