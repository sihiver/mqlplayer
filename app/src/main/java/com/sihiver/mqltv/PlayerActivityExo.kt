package com.sihiver.mqltv

import android.content.Context
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
import androidx.compose.material.icons.filled.List
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
import androidx.media3.exoplayer.DefaultLoadControl
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
    private var showCategoryView = mutableStateOf(false)
    private var selectedCategory = mutableStateOf<String?>(null)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        android.util.Log.d("PlayerActivityExo", "onCreate - using ExoPlayer with FFmpeg extension")
        
        // Load video settings
        val prefs = getSharedPreferences("video_settings", Context.MODE_PRIVATE)
        val orientationSetting = prefs.getString("orientation", "Sensor Landscape") ?: "Sensor Landscape"
        val accelerationSetting = prefs.getString("acceleration", "HW (Hardware)") ?: "HW (Hardware)"
        
        // Apply hardware acceleration based on settings
        when (accelerationSetting) {
            "HW (Hardware)" -> {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                )
            }
            "HW+ (Hardware+)" -> {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                )
            }
            "SW (Software)" -> {
                // Software rendering - no hardware acceleration
            }
        }
        
        // Apply orientation based on settings
        requestedOrientation = when (orientationSetting) {
            "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "Auto" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        
        // Keep screen on during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Set thread priority for smooth playback
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        
        // Disable window animations for faster transitions
        window.setWindowAnimations(0)
        
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
        
        // Create PlayerView with optimized settings
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            
            // Apply aspect ratio from settings
            val aspectRatioSetting = prefs.getString("aspect_ratio", "Fit") ?: "Fit"
            resizeMode = when (aspectRatioSetting) {
                "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                "Zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "16:9" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                "4:3" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            
            // Don't show buffering indicator for smoother UX
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            
            // Use surface view for better performance on most devices
            setUseController(false)
            
            // Apply layer type based on acceleration settings
            setLayerType(
                when (accelerationSetting) {
                    "SW (Software)" -> android.view.View.LAYER_TYPE_SOFTWARE
                    else -> android.view.View.LAYER_TYPE_HARDWARE
                },
                null
            )
            
            // Optimize view for TV/low-end devices
            keepScreenOn = true
            
            // Hide default controller, we'll use custom overlay
            useController = false
            
            // Disable shutter animation for faster rendering
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            
            // Force use TextureView for SW mode, SurfaceView for HW
            setUseTextureView(accelerationSetting == "SW (Software)")
            
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
            
            // Create OkHttp data source factory with optimized settings
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            
            // Create NextLib FFmpeg renderers factory for MPEG-L2 support
            val renderersFactory = NextRenderersFactory(this).apply {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                // Force simple video decoder for low-end devices
                forceEnableAudioOffload(false)
            }
            
            // Create media source factory
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)
            
            // Optimized LoadControl for low-end devices - more aggressive
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    8000,   // Min buffer: 8s (more aggressive)
                    16000,  // Max buffer: 16s (more aggressive)
                    1000,   // Buffer for playback: 1s (more aggressive)
                    2000    // Buffer for playback after rebuffer: 2s (more aggressive)
                )
                .setTargetBufferBytes(-1) // Use default
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(3000, false) // Keep 3s back buffer, don't retain
                .build()
            
            // Create TrackSelector with constraints for low-end devices
            val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
                parameters = buildUponParameters()
                    .setMaxVideoSizeSd() // Limit to SD quality
                    .setMaxVideoBitrate(2000000) // Max 2Mbps
                    .setForceHighestSupportedBitrate(false)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedRendererCapabilitiesIfNecessary(false)
                    .setTunnelingEnabled(false)
                    .build()
            }
            
            // Create ExoPlayer with optimized settings
            exoPlayer = ExoPlayer.Builder(this)
                .setRenderersFactory(renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .setUsePlatformDiagnostics(false) // Reduce overhead
                .setPauseAtEndOfMediaItems(false)
                .build()
                .apply {
                    playWhenReady = true
                    
                    // Optimize video scaling for low-end devices
                    videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                    
                    // Skip silence for smoother playback
                    skipSilenceEnabled = false
                    
                    // Disable video frame metadata for better performance
                    videoFrameMetadataListener = null
                    
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
                            android.util.Log.e("PlayerActivityExo", "Player error: ${error.message}", error)
                            android.util.Log.e("PlayerActivityExo", "Error code: ${error.errorCode}")
                            
                            val channel = ChannelRepository.getChannelById(channelId)
                            val channelName = channel?.name ?: "Unknown"
                            
                            // Handle decoder errors specifically
                            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                                android.util.Log.e("PlayerActivityExo", "Decoder init failed for channel: $channelName")
                                
                                runOnUiThread {
                                    android.widget.Toast.makeText(
                                        this@PlayerActivityExo,
                                        "Decoder gagal untuk '$channelName'.\nCoba channel lain atau tunggu sebentar.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    
                                    // Try to clear error state and retry
                                    try {
                                        exoPlayer?.stop()
                                        exoPlayer?.clearMediaItems()
                                        
                                        // Small delay before showing list
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            showChannelList.value = true
                                        }, 500)
                                    } catch (e: Exception) {
                                        android.util.Log.e("PlayerActivityExo", "Error clearing player state", e)
                                        showChannelList.value = true
                                    }
                                }
                                return
                            }
                            
                            val errorMessage = when (error.errorCode) {
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> 
                                    "Channel '$channelName' tidak dapat terhubung. Mungkin sedang down."
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                                    "Channel '$channelName' tidak tersedia (HTTP error)."
                                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                                    "Format stream channel '$channelName' tidak valid."
                                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ->
                                    "Decoding error pada '$channelName'. Coba channel lain."
                                else -> "Channel '$channelName' tidak dapat diputar. (Error: ${error.errorCode})"
                            }
                            
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                    this@PlayerActivityExo,
                                    "$errorMessage\nTekan OK/Menu untuk pilih channel lain.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                
                                // Show channel list automatically so user can select another channel
                                showChannelList.value = true
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
        try {
            exoPlayer?.let { player ->
                // Stop playback first
                player.stop()
                // Clear all media items
                player.clearMediaItems()
                // Remove listeners to prevent callbacks during release
                player.clearVideoSurface()
                // Release player resources
                player.release()
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityExo", "Error releasing player: ${e.message}", e)
        } finally {
            exoPlayer = null
        }
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
        try {
            channelId = channel.id
            currentChannelIndex.value = ChannelRepository.getAllChannels().indexOfFirst { it.id == channelId }
            
            android.util.Log.d("PlayerActivityExo", "Switching to channel: ${channel.name}, URL: ${channel.url}")
            
            // Validate URL
            if (channel.url.isBlank()) {
                android.util.Log.e("PlayerActivityExo", "Channel URL is empty")
                android.widget.Toast.makeText(
                    this,
                    "URL channel '${channel.name}' kosong.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            }
            
            // Stop current playback first
            exoPlayer?.stop()
            
            // Set new media item
            val mediaItem = MediaItem.fromUri(channel.url)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.play()
            
            android.util.Log.d("PlayerActivityExo", "Channel switched successfully")
            
        } catch (e: IllegalStateException) {
            android.util.Log.e("PlayerActivityExo", "Player state error when switching channel", e)
            android.widget.Toast.makeText(
                this,
                "Gagal beralih ke '${channel.name}'. Player dalam kondisi tidak valid.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: IllegalArgumentException) {
            android.util.Log.e("PlayerActivityExo", "Invalid URL format", e)
            android.widget.Toast.makeText(
                this,
                "URL channel '${channel.name}' tidak valid.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityExo", "Unexpected error switching channel", e)
            android.widget.Toast.makeText(
                this,
                "Error beralih ke '${channel.name}': ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    @Composable
    private fun ChannelListOverlay() {
        val isVisible by showChannelList
        
        if (!isVisible) return
        
        val showCategories by showCategoryView
        val category by selectedCategory
        
        if (showCategories) {
            CategoryListView()
        } else {
            ChannelListView(category)
        }
    }
    
    @Composable
    private fun CategoryListView() {
        val categories = remember { ChannelRepository.getAllCategories() }
        
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
                    .clickable(enabled = false) { },
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
                            text = "Pilih Category",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Row {
                            IconButton(onClick = { showCategoryView.value = false }) {
                                Icon(
                                    imageVector = Icons.Default.List,
                                    contentDescription = "Back to Channels",
                                    tint = Color.White
                                )
                            }
                            IconButton(onClick = { showChannelList.value = false }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                    
                    // "Semua" option
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .clickable {
                                selectedCategory.value = null
                                showCategoryView.value = false
                            },
                        color = Color(0xFF2E2E2E),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📺",
                                fontSize = 24.sp,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Column {
                                Text(
                                    text = "Semua Channel",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "${ChannelRepository.getAllChannels().size} channels",
                                    fontSize = 14.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                    
                    // Category list
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(categories.size) { index ->
                            val category = categories[index]
                            val channelCount = ChannelRepository.getChannelsByCategory(category).size
                            
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        // Show channels from this category
                                        selectedCategory.value = category
                                        showCategoryView.value = false
                                    },
                                color = Color(0xFF2E2E2E),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📁",
                                        fontSize = 24.sp,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = category,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "$channelCount channels",
                                            fontSize = 14.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    Text(
                                        text = "›",
                                        fontSize = 28.sp,
                                        color = Color(0xFF2196F3),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    @Composable
    private fun ChannelListView(filterCategory: String?) {
        val allChannels = remember { ChannelRepository.getAllChannels() }
        val channels = remember(filterCategory) {
            if (filterCategory != null) {
                ChannelRepository.getChannelsByCategory(filterCategory)
            } else {
                allChannels
            }
        }
        
        val currentIndex = channels.indexOfFirst { it.id == channelId }.coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
        
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = filterCategory ?: "Semua Channel",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "${channels.size} channels",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                        Row {
                            if (filterCategory != null) {
                                IconButton(onClick = { 
                                    selectedCategory.value = null
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.List,
                                        contentDescription = "All Channels",
                                        tint = Color.White
                                    )
                                }
                            }
                            Button(
                                onClick = { showCategoryView.value = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.2f)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Cari",
                                    fontSize = 14.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = { showChannelList.value = false }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White
                                )
                            }
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
