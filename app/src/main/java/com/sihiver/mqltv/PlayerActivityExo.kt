package com.sihiver.mqltv

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sihiver.mqltv.repository.ChannelRepository
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import okhttp3.OkHttpClient

@UnstableApi
class PlayerActivityExo : ComponentActivity() {
    
    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var channelId: Int = -1
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        android.util.Log.d("PlayerActivityExo", "onCreate - using ExoPlayer with FFmpeg extension")
        
        // Force landscape orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        
        // Keep screen on during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Handle back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                android.util.Log.d("PlayerActivityExo", "Back pressed, finishing activity")
                finish()
            }
        })
        
        // Create PlayerView
        playerView = PlayerView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Set resize mode to fit with letterboxing
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        
        setContentView(playerView)
        
        // Hide system UI for immersive experience
        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        } catch (e: Exception) {
            android.util.Log.w("PlayerActivityExo", "Failed to hide system UI: ${e.message}")
        }
        
        // Get channel ID from intent
        channelId = intent.getIntExtra("CHANNEL_ID", -1)
        android.util.Log.d("PlayerActivityExo", "Received channel ID: $channelId")
    }
    
    override fun onStart() {
        super.onStart()
        initializePlayer()
    }
    
    override fun onStop() {
        super.onStop()
        releasePlayer()
    }
    
    private fun initializePlayer() {
        try {
            // Load channels first
            ChannelRepository.loadChannels(this)
            
            // Get channel from repository
            val channel = ChannelRepository.getChannelById(channelId)
            
            if (channel == null) {
                android.util.Log.e("PlayerActivityExo", "Channel not found with ID: $channelId")
                android.widget.Toast.makeText(this, "Channel not found", android.widget.Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            android.util.Log.d("PlayerActivityExo", "Playing channel: ${channel.name}, URL: ${channel.url}")
            
            // Create OkHttp data source factory
            val okHttpClient = OkHttpClient.Builder().build()
            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            
            // Create NextLib FFmpeg renderers factory for MPEG-L2 support
            val renderersFactory = NextRenderersFactory(this).apply {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            }
            
            // Create media source factory
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)
            
            // Create ExoPlayer with FFmpeg renderers
            exoPlayer = ExoPlayer.Builder(this)
                .setRenderersFactory(renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    playWhenReady = true
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    android.util.Log.d("PlayerActivityExo", "Player ready")
                                }
                                Player.STATE_BUFFERING -> {
                                    android.util.Log.d("PlayerActivityExo", "Buffering...")
                                }
                                Player.STATE_ENDED -> {
                                    android.util.Log.d("PlayerActivityExo", "Playback ended")
                                }
                            }
                        }
                        
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            android.util.Log.e("PlayerActivityExo", "Player error: ${error.message}")
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                    this@PlayerActivityExo,
                                    "Error: ${error.message}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    })
                }
            
            // Set player to view
            playerView?.player = exoPlayer
            
            // Create media item and start playback
            val mediaItem = MediaItem.fromUri(channel.url)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            
            android.util.Log.d("PlayerActivityExo", "ExoPlayer initialized with FFmpeg support")
            
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityExo", "Error initializing player", e)
            android.widget.Toast.makeText(
                this,
                "Error initializing player: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }
    
    private fun releasePlayer() {
        android.util.Log.d("PlayerActivityExo", "Releasing ExoPlayer")
        exoPlayer?.release()
        exoPlayer = null
    }
}
