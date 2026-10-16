package com.galaxytv.app

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class StreamPlayerActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PLAYER_NAME = "channel_name"
        private const val EXTRA_PLAYER_URL = "stream_url"
        const val EXTRA_MIN_HD = "player_min_hd"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private var expiryWatcher: ActivationExpiryWatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ActivationSession.activeCode(this) == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_player)

        val streamUrl = intent.getStringExtra(EXTRA_PLAYER_URL).orEmpty()
        val channelName = intent.getStringExtra(EXTRA_PLAYER_NAME).orEmpty()
        val preferHd = intent.getBooleanExtra(EXTRA_MIN_HD, false)
        val uri = Uri.parse(streamUrl)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            finish()
            return
        }

        status = findViewById(R.id.playerStatus)
        playerView = findViewById(R.id.channelVideoView)
        val exoPlayer = AppMediaPlayer.create(this)
        player = exoPlayer
        playerView.player = exoPlayer
        status.text = channelName
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    status.text = "Connecting to $channelName..."
                    status.visibility = View.VISIBLE
                } else if (playbackState == Player.STATE_READY) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    status.visibility = View.GONE
                } else if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                status.visibility = View.VISIBLE
                status.text = "Playback failed (${error.errorCodeName})."
            }
        })
        exoPlayer.apply {
            if (preferHd) {
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setMinVideoSize(1920, 1080)
                    .build()
            }
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        expiryWatcher = ActivationExpiryWatcher(this) {
            player?.stop()
            Toast.makeText(this, "Your activation has expired. Please sign in again.", Toast.LENGTH_LONG).show()
            finish()
        }.also { it.start() }
    }

    override fun onPause() {
        expiryWatcher?.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player?.apply {
            playWhenReady = false
            stop()
        }
        super.onPause()
    }

    override fun onDestroy() {
        expiryWatcher?.stop()
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }
}