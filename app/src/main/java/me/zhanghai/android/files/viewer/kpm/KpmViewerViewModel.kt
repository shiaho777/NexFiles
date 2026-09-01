/*
 * Copyright (c) NexFiles contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.kpm

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.toError
import me.zhanghai.android.files.util.toLoading
import java.io.IOException

class KpmViewerViewModel(val file: Path) : ViewModel() {
    private val _modelState = MutableStateFlow<DataState<KpmModel>>(DataState.Loading())
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
                        throw IOException("Module size $size is too large")
                    }
                    file.newInputStream().use { KpmModel.read(it) }
                }
                currentCoroutineContext().ensureActive()
                _modelState.value = DataState.Success(model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _modelState.value = _modelState.value.toError(e)
            }
        }
    }

    companion object {
        // Some shipped kernel modules reach several MB; keep a generous ceiling.
        const val MAX_FILE_SIZE: Long = 64L * 1024 * 1024
    }
}
