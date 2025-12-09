package com.sihiver.mqltv

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.repository.ChannelRepository
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import okhttp3.OkHttpClient
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivityExo : ComponentActivity() {
    
    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var channelId: Int = -1
    private var overlayComposeView: ComposeView? = null
    private var showChannelList = mutableStateOf(false)
    private var currentChannelIndex = mutableStateOf(0)
    
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
        
        // Create root frame layout
        val rootLayout = FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        
        // Create PlayerView
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Set resize mode to fit with letterboxing
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            // Hide default controller, we'll use custom overlay
            useController = false
            // Handle click to show channel list
            setOnClickListener {
                showChannelList.value = !showChannelList.value
            }
        }
        
        rootLayout.addView(playerView)
        
        // Create channel list overlay (Compose)
        overlayComposeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setContent {
                ChannelListOverlay()
            }
        }
        
        rootLayout.addView(overlayComposeView)
        
        setContentView(rootLayout)
        
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
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Toggle channel list with Menu/OK button or Tab key
        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_TAB -> {
                showChannelList.value = !showChannelList.value
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!showChannelList.value) {
                    // Quick channel up
                    switchToNextChannel(true)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!showChannelList.value) {
                    // Quick channel down
                    switchToNextChannel(false)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun switchToNextChannel(previous: Boolean) {
        val channels = ChannelRepository.getAllChannels()
        if (channels.isEmpty()) return
        
        val currentIndex = channels.indexOfFirst { it.id == channelId }
        if (currentIndex == -1) return
        
        val newIndex = if (previous) {
            if (currentIndex > 0) currentIndex - 1 else channels.size - 1
        } else {
            if (currentIndex < channels.size - 1) currentIndex + 1 else 0
        }
        
        switchChannel(channels[newIndex])
    }
    
    private fun switchChannel(channel: Channel) {
        channelId = channel.id
        currentChannelIndex.value = ChannelRepository.getAllChannels().indexOfFirst { it.id == channelId }
        
        android.util.Log.d("PlayerActivityExo", "Switching to channel: ${channel.name}")
        
        try {
            val mediaItem = MediaItem.fromUri(channel.url)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.play()
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityExo", "Error switching channel", e)
            android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    @Composable
    private fun ChannelListOverlay() {
        val isVisible by showChannelList
        
        if (!isVisible) return
        
        val channels = remember { ChannelRepository.getAllChannels() }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentChannelIndex.value)
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable { showChannelList.value = false }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(400.dp)
                    .align(Alignment.CenterStart)
                    .clickable(enabled = false) { }, // Prevent closing when clicking list
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2196F3))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Semua Channel",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        IconButton(onClick = { showChannelList.value = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White
                            )
                        }
                    }
                    
                    // Channel info
                    val currentChannel = channels.getOrNull(currentChannelIndex.value)
                    if (currentChannel != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            color = Color(0xFF2196F3).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "Now Playing: ${currentChannel.name}",
                                modifier = Modifier.padding(12.dp),
                                fontSize = 14.sp,
                                color = Color(0xFF2196F3),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    
                    // Channel list
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        itemsIndexed(channels) { index, channel ->
                            val isCurrentChannel = channel.id == channelId
                            
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        switchChannel(channel)
                                        showChannelList.value = false
                                    },
                                color = if (isCurrentChannel) Color(0xFF2196F3) else Color(0xFF2E2E2E),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Channel number
                                    Text(
                                        text = "%04d".format(channel.id),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrentChannel) Color.White else Color(0xFF2196F3),
                                        modifier = Modifier.width(60.dp)
                                    )
                                    
                                    // Channel name
                                    Text(
                                        text = channel.name,
                                        fontSize = 16.sp,
                                        color = Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    // Category indicator
                                    if (channel.category.isNotEmpty()) {
                                        Text(
                                            text = "$",
                                            fontSize = 16.sp,
                                            color = Color(0xFF4CAF50),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Instructions
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "Menu/OK: Toggle List • ↑↓: Switch Channel • Back: Exit",
                    modifier = Modifier.padding(8.dp),
                    fontSize = 12.sp,
                    color = Color.White
                )
            }
        }
    }
}
