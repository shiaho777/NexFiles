/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.apk

import android.os.AsyncTask
import java8.nio.file.Path
import me.zhanghai.android.files.fileproperties.PathObserverLiveData
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat

class ApkSignatureLiveData(path: Path) : PathObserverLiveData<Stateful<ApkSignatureInfo>>(path) {
    init {
        loadValue()
        observe()
    }

    override fun loadValue() {
        value = Loading(value?.value)
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value = try {
                // The signing-block reader works on any path the project can open as a channel.
                // For v1 (jarsigner) we additionally need a backing file path because the JDK
                // ZipFile can't be built from a channel; only Linux paths provide one.
                val v1FilePath = if (path.isLinuxPath) path.toFile().path else null
                val info = path.newByteChannel().use { channel ->
                    ApkSigningBlockReader.read(channel, v1FilePath)
                }
                Success(info)
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }
}
