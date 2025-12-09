package com.sihiver.mqltv

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.repository.ChannelRepository

class PlayerActivity : ComponentActivity() {
    
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var channelId: Int = -1
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        android.util.Log.d("PlayerActivity", "onCreate called")
        
        // Handle back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                android.util.Log.d("PlayerActivity", "Back pressed, finishing activity")
                finish()
            }
        })
        
        // Create PlayerView first
        playerView = PlayerView(this)
        playerView.useController = true
        playerView.controllerShowTimeoutMs = 5000
        setContentView(playerView)
        
        // Hide system UI for immersive experience - use simpler approach
        try {
            // For all versions - use the more reliable deprecated API for now
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
            android.util.Log.w("PlayerActivity", "Failed to hide system UI: ${e.message}")
        }
        
        // Get channel ID from intent
        channelId = intent.getIntExtra("CHANNEL_ID", -1)
        android.util.Log.d("PlayerActivity", "Received channel ID: $channelId")
    }
    
    override fun onStart() {
        super.onStart()
        initializePlayer()
    }
    
    override fun onStop() {
        super.onStop()
        releasePlayer()
    }
    
    private fun createEnhancedRenderersFactory(): DefaultRenderersFactory {
        return DefaultRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            
            // Use a MediaCodecSelector that handles both video and audio codecs
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                // For MPEG-TS content, prefer software decoders
                when {
                    mimeType?.contains("video/avc") == true -> {
                        android.util.Log.d("PlayerActivity", "Selecting decoder for AVC video content")
                    }
                    mimeType?.contains("audio/") == true -> {
                        android.util.Log.d("PlayerActivity", "Selecting decoder for audio: $mimeType")
                        // Skip MPEG-L2 audio codec (not supported by most Android devices)
                        if (mimeType.contains("mpeg-L2", ignoreCase = true) || mimeType.contains("audio/mpeg")) {
                            android.util.Log.w("PlayerActivity", "MPEG-L2 audio codec not supported, video will play without audio")
                            return@setMediaCodecSelector emptyList() // Return empty list to skip this track
                        }
                    }
                }
                MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            }
        }
    }

    private fun createPureSoftwareRenderersFactory(): DefaultRenderersFactory {
        return DefaultRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            
            // Force software-only decoders
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                if (mimeType?.contains("video/avc") == true) {
                    android.util.Log.d("PlayerActivity", "Using pure software decoder for AVC content")
                }
                // Filter to only software decoders
                MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    .filter { !it.hardwareAccelerated }
            }
        }
    }
    
    private fun createTsMediaSource(uri: String): ProgressiveMediaSource {
        val dataSourceFactory = DefaultDataSourceFactory(this, "ExoPlayer")
        
        // Create extractor factory with enhanced TS support  
        val extractorsFactory = DefaultExtractorsFactory().apply {
            // Configure TS extractor for live streams
            setTsExtractorMode(TsExtractor.MODE_MULTI_PMT)
            setTsExtractorTimestampSearchBytes(600 * TsExtractor.TS_PACKET_SIZE)
        }
        
        return ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
    }

    private fun createHlsMediaSource(uri: String): HlsMediaSource {
        val dataSourceFactory = DefaultDataSourceFactory(this, "ExoPlayer")
        
        return HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(false)
            .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
    }

    private fun initializePlayer() {
        try {
            // Load channels first
            ChannelRepository.loadChannels(this)
            
            // Get channel from repository
            val channel = ChannelRepository.getChannelById(channelId)
            
            if (channel == null) {
                android.util.Log.e("PlayerActivity", "Channel not found with ID: $channelId")
                android.widget.Toast.makeText(this, "Channel not found", android.widget.Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            android.util.Log.d("PlayerActivity", "Playing channel: ${channel.name}, URL: ${channel.url}")
            
            // Create ExoPlayer instance with enhanced MPEG-TS support
            player = ExoPlayer.Builder(this)
                .setRenderersFactory(createEnhancedRenderersFactory())
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            15000, // Min buffer
                            50000, // Max buffer  
                            1500,  // Buffer for playback
                            5000   // Buffer for playback after rebuffer
                        )
                        .build()
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true // Handle audio focus
                )
                .build().also { exoPlayer ->
                    playerView.player = exoPlayer
                    
                    // Add error listener
                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            android.util.Log.e("PlayerActivity", "Playback error: ${error.message}", error)
                            if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                                android.util.Log.w("PlayerActivity", "Decoder initialization failed - likely unsupported audio codec (MPEG-L2)")
                                // Don't show toast for audio codec issues - video will continue
                            } else {
                                runOnUiThread {
                                    android.widget.Toast.makeText(
                                        this@PlayerActivity,
                                        "Playback error: ${error.message}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    })
                    
                    // Log audio and video format info
                    exoPlayer.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                        override fun onAudioEnabled(
                            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                            counters: androidx.media3.exoplayer.DecoderCounters
                        ) {
                            android.util.Log.d("PlayerActivity", "Audio renderer enabled")
                        }
                        
                        override fun onAudioDisabled(
                            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                            counters: androidx.media3.exoplayer.DecoderCounters
                        ) {
                            android.util.Log.w("PlayerActivity", "Audio renderer disabled - no audio will play")
                        }
                        
                        override fun onAudioDecoderInitialized(
                            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                            decoderName: String,
                            initializedTimestampMs: Long,
                            initializationDurationMs: Long
                        ) {
                            android.util.Log.d("PlayerActivity", "Audio decoder initialized: $decoderName")
                        }
                        
                        override fun onAudioInputFormatChanged(
                            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                            format: androidx.media3.common.Format,
                            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
                        ) {
                            android.util.Log.d("PlayerActivity", "Audio format: ${format.sampleMimeType}, channels: ${format.channelCount}, sample rate: ${format.sampleRate}")
                        }
                        
                        override fun onAudioDecoderReleased(
                            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                            decoderName: String
                        ) {
                            android.util.Log.d("PlayerActivity", "Audio decoder released: $decoderName")
                        }
                    })
                    
                    // For HDMI capture and MPEG-TS streams, use appropriate media source
                    if (channel.url.contains(".m3u8")) {
                        android.util.Log.d("PlayerActivity", "Using HLS media source for ${channel.name}")
                        val mediaSource = createHlsMediaSource(channel.url)
                        exoPlayer.setMediaSource(mediaSource)
                    } else if (channel.url.contains(".ts")) {
                        android.util.Log.d("PlayerActivity", "Using TS media source for ${channel.name}")
                        val mediaSource = createTsMediaSource(channel.url)
                        exoPlayer.setMediaSource(mediaSource)
                        exoPlayer.setMediaSource(mediaSource)
                    } else {
                        // Create media item from channel URL with HLS configuration
                        val mediaItem = MediaItem.Builder()
                            .setUri(channel.url)
                            .apply {
                                // For HLS streams, add specific configuration
                                if (channel.url.contains(".m3u8")) {
                                    setMimeType(MimeTypes.APPLICATION_M3U8)
                                }
                            }
                            .build()
                        
                        exoPlayer.setMediaItem(mediaItem)
                    }
                    
                    // Prepare and play
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                
                // Add listener for playback state
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                android.util.Log.d("PlayerActivity", "Buffering...")
                            }
                            Player.STATE_READY -> {
                                android.util.Log.d("PlayerActivity", "Ready to play")
                            }
                            Player.STATE_ENDED -> {
                                android.util.Log.d("PlayerActivity", "Playback ended")
                            }
                            Player.STATE_IDLE -> {
                                android.util.Log.d("PlayerActivity", "Player idle")
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e("PlayerActivity", "Playback error: ${error.message}", error)
                        
                        // Enhanced error handling for codec issues
                        when (error.errorCode) {
                            androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                            androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> {
                                android.util.Log.w("PlayerActivity", "Decoder error, attempting software fallback")
                                // Try to restart with software decoder
                                tryWithSoftwareDecoder(channel)
                                return
                            }
                        }
                        
                        // Check if it's any renderer error (including video frame decoding)
                        if (error.message?.contains("MediaCodecVideoRenderer", ignoreCase = true) == true ||
                            error.message?.contains("VideoRenderer", ignoreCase = true) == true ||
                            error.message?.contains("MediaCodecVideoDecoder", ignoreCase = true) == true ||
                            error.message?.contains("NO_EXCEEDS_CAPABILITIES", ignoreCase = true) == true ||
                            error.message?.contains("video/mp2t", ignoreCase = true) == true) {
                            android.util.Log.w("PlayerActivity", "Video renderer error, trying alternative approach")
                            
                            // Try direct TS file instead of HLS playlist
                            tryDirectTsStream(channel)
                            return
                        }
                        
                        // Show user-friendly error message
                        val errorMessage = when (error.errorCode) {
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> 
                                "Network connection failed. Check your internet connection."
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                                "Connection timeout. The stream may be unavailable."
                            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                                "Stream format not supported."
                            else -> "Error playing stream: ${error.message ?: "Unknown error"}"
                        }
                        
                        android.widget.Toast.makeText(
                            this@PlayerActivity, 
                            errorMessage, 
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                })
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Error initializing player", e)
            android.widget.Toast.makeText(this, "Error initializing player: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    private fun tryWithSoftwareDecoder(channel: Channel) {
        android.util.Log.d("PlayerActivity", "Trying software decoder with FFmpeg for channel: ${channel.name}")
        
        // Release current player
        player?.release()
        
        try {
            // Create ExoPlayer with enhanced MPEG-TS renderer
            player = ExoPlayer.Builder(this)
                .setRenderersFactory(createEnhancedRenderersFactory())
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            20000, // Min buffer - larger for problematic streams
                            60000, // Max buffer
                            2000,  // Buffer for playback
                            8000   // Buffer for playback after rebuffer
                        )
                        .build()
                )
                .build().also { exoPlayer ->
                    playerView.player = exoPlayer
                    
                    // Create media item with basic configuration
                    val mediaItem = MediaItem.Builder()
                        .setUri(channel.url)
                        .build()
                    
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    
                    // Add simplified listener for software decoder attempt
                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            android.util.Log.e("PlayerActivity", "Software decoder also failed: ${error.message}")
                            // TODO: If software decoder also fails, try LibVLC
                            android.widget.Toast.makeText(
                                this@PlayerActivity,
                                "All decoders failed. Stream format not supported.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            // tryWithLibVLC(channel)
                        }
                        
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                android.util.Log.d("PlayerActivity", "Software decoder successful!")
                                android.widget.Toast.makeText(
                                    this@PlayerActivity,
                                    "Playing with software decoder",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    })
                }
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Software decoder fallback failed", e)
            // TODO: If software decoder setup fails, try LibVLC
            android.widget.Toast.makeText(
                this,
                "Decoder initialization failed: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
            // tryWithLibVLC(channel)
        }
    }
    
    private fun tryDirectTsStream(channel: Channel) {
        android.util.Log.d("PlayerActivity", "Trying direct TS stream for channel: ${channel.name}")
        
        // Release current player
        player?.release()
        
        try {
            // Try getting a direct TS file URL from the HLS playlist
            val tsUrl = if (channel.url.contains(".m3u8")) {
                // For now, try to get the base URL and append a recent TS file
                val baseUrl = channel.url.substringBeforeLast("/")
                "$baseUrl/stream-latest.ts" // This is speculative - might need actual playlist parsing
            } else {
                channel.url
            }
            
            android.util.Log.d("PlayerActivity", "Trying direct TS URL: $tsUrl")
            
            // Create ExoPlayer with pure software renderer
            player = ExoPlayer.Builder(this)
                .setRenderersFactory(createPureSoftwareRenderersFactory())
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(5000, 20000, 1000, 3000)
                        .build()
                )
                .build().also { exoPlayer ->
                    playerView.player = exoPlayer
                    
                    // Use progressive media source with TS file
                    val mediaSource = createTsMediaSource(tsUrl)
                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    
                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            android.util.Log.e("PlayerActivity", "Direct TS stream also failed: ${error.message}")
                            android.widget.Toast.makeText(
                                this@PlayerActivity,
                                "MPEG-TS format not supported on this device. Please use VLC app.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                android.util.Log.d("PlayerActivity", "Direct TS stream successful!")
                                android.widget.Toast.makeText(
                                    this@PlayerActivity,
                                    "Playing direct TS stream",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    })
                }
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Direct TS stream failed", e)
            android.widget.Toast.makeText(
                this,
                "Failed to play MPEG-TS stream. Try VLC app.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    
    /* TODO: Implement LibVLC fallback when dependency is resolved
    private fun tryWithLibVLC(channel: Channel) {
        android.util.Log.d("PlayerActivity", "Trying LibVLC for channel: ${channel.name}")
        
        try {
            // Release ExoPlayer
            player?.release()
            player = null
            
            // Remove existing player view and create LibVLC view
            val vlcVideoView = org.videolan.libvlc.util.VLCVideoLayout(this)
            setContentView(vlcVideoView)
            
            // Create LibVLC instance
            val options = arrayListOf<String>()
            options.add("--intf=dummy")
            options.add("--no-video-title-show")
            options.add("--network-caching=1500")
            options.add("--rtsp-timeout=60")
            options.add("--http-reconnect")
            options.add("--live-caching=1000")
            
            val libvlc = org.videolan.libvlc.LibVLC(this, options)
            val mediaPlayer = org.videolan.libvlc.MediaPlayer(libvlc)
            
            // Attach to video view
            mediaPlayer.attachViews(vlcVideoView, null, false, false)
            
            // Create media and play
            val media = org.videolan.libvlc.Media(libvlc, android.net.Uri.parse(channel.url))
            mediaPlayer.media = media
            mediaPlayer.play()
            
            android.util.Log.d("PlayerActivity", "LibVLC player started for ${channel.name}")
            android.widget.Toast.makeText(
                this,
                "Playing with LibVLC (better codec support)",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            
            // Set events listener
            mediaPlayer.setEventListener { event ->
                when (event.type) {
                    org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                        android.util.Log.d("PlayerActivity", "LibVLC: Playing")
                    }
                    org.videolan.libvlc.MediaPlayer.Event.EncounteredError -> {
                        android.util.Log.e("PlayerActivity", "LibVLC: Error encountered")
                        android.widget.Toast.makeText(
                            this@PlayerActivity,
                            "LibVLC also failed. Stream format not supported.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    org.videolan.libvlc.MediaPlayer.Event.EndReached -> {
                        android.util.Log.d("PlayerActivity", "LibVLC: End reached")
                    }
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "LibVLC fallback failed", e)
            android.widget.Toast.makeText(
                this,
                "All players failed: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    */
    
    private fun releasePlayer() {
        player?.let {
            it.release()
        }
        player = null
    }
}
