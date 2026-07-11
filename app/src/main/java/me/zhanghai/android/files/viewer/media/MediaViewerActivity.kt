/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.showToast

/**
 * Built-in media previewer for video and audio files, now backed by Media3 ExoPlayer instead of
 * the platform [android.widget.VideoView]. ExoPlayer gives us wider container/codec coverage
 * (MKV, Opus, WebM), proper buffering UI via [PlayerView], reliable lifecycle/resume, and
 * structured error reporting. A future FFmpeg extension can layer on top of this for the few
 * formats the device's hardware decoders still reject.
 *
 * The intent carries a content/file uri for the media; we hand it straight to the player with a
 * read grant. Playback state survives configuration changes because ExoPlayer is kept across
 * recreate via [retainInstance] — but we keep this first cut simple and rebuild on recreate.
 */
class MediaViewerActivity : AppActivity() {

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.media_viewer_activity)

        playerView = findViewById(R.id.playerView)
        val uri: Uri? = intent.data
        if (uri == null) {
            finish()
            return
        }
        createPlayer(uri)
    }

    private fun createPlayer(uri: Uri) {
        val exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // STATE_ENDED: loop back to the start so rewatching doesn't require a tap.
                if (playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                    exoPlayer.playWhenReady = true
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Surface the failure rather than sitting on a blank screen; the user can then
                // fall back to "Open with" for a player that supports the codec.
                showToast(getString(R.string.media_viewer_playback_error, error.message ?: ""))
            }
        })
        playerView.player = exoPlayer
        player = exoPlayer
    }

    override fun onStop() {
        super.onStop()
        // Pause (but don't release) when we lose the foreground: ExoPlayer keeps the buffered
        // media so returning resumes instantly. Audio keeps playing is undesired when backgrounded.
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the player and detach it from the view to avoid leaks.
        playerView.player = null
        player?.release()
        player = null
    }
}
