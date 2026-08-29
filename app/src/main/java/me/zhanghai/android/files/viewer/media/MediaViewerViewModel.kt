/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import me.zhanghai.android.files.app.application

class MediaViewerViewModel(mediaUri: Uri) : ViewModel() {
    // Built lazily on first access from the activity, which is always on the main thread as
    // ExoPlayer requires. Owning the player here keeps playback state (position, buffering,
    // playWhenReady) across activity recreation; onCleared() is the single release point.
    val player: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        setMediaItem(MediaItem.fromUri(mediaUri))
        prepare()
        playWhenReady = true
    }

    override fun onCleared() {
        player.release()
    }
}
