/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.showToast

/**
 * Built-in media previewer for video and audio files, backed by Media3 ExoPlayer. ExoPlayer gives
 * us wider container/codec coverage (MKV, Opus, WebM), proper buffering UI via [PlayerView],
 * reliable lifecycle/resume, and structured error reporting. A future FFmpeg extension can layer
 * on top of this for the few formats the device's hardware decoders still reject.
 *
 * The intent carries a content/file uri for the media; we hand it straight to the player with a
 * read grant. The player lives in [MediaViewerViewModel], so playback state (position, buffering)
 * survives activity recreation for any reason — rotation, uiMode/density changes, or a
 * system-initiated recreate — and is only released when the ViewModel is cleared.
 */
class MediaViewerActivity : AppActivity() {

    private lateinit var playerView: PlayerView

    private val viewModel: MediaViewerViewModel
        by viewModels { viewModelFactory { initializer { MediaViewerViewModel(intent.data!!) } } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.media_viewer_activity)

        playerView = findViewById(R.id.playerView)
        if (intent.data == null) {
            finish()
            return
        }
        attachPlayer()
    }

    private fun attachPlayer() {
        val player = viewModel.player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // STATE_ENDED: loop back to the start so rewatching doesn't require a tap.
                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.playWhenReady = true
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Surface the failure rather than sitting on a blank screen; the user can then
                // fall back to "Open with" for a player that supports the codec.
                showToast(getString(R.string.media_viewer_playback_error, error.message ?: ""))
            }
        })
        playerView.player = player
    }

    override fun onStop() {
        super.onStop()
        // Pause (but don't release) when we lose the foreground: ExoPlayer keeps the buffered
        // media so returning resumes instantly. Audio keeps playing is undesired when backgrounded.
        if (intent.data != null) {
            viewModel.player.playWhenReady = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only detach here; the player itself is released by MediaViewerViewModel.onCleared()
        // so that it can outlive an activity recreation.
        playerView.player = null
    }
}
