package com.sihiver.mqltv

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.repository.ChannelRepository
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.VLCVideoLayout

class PlayerActivityVLC : ComponentActivity() {

    private var libVLC: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null
    private lateinit var videoLayout: VLCVideoLayout
    private var channelId: Int = -1
    
    private val showChannelList = mutableStateOf(false)
    private val currentChannelName = mutableStateOf("")
    private val selectedListIndex = mutableStateOf(0)
    private val isBuffering = mutableStateOf(true)
    private val bufferingPercent = mutableStateOf(0f)
    private val isVideoReady = mutableStateOf(false)
    private var savedAudioTrack: Int = -1
    private var pendingPlay = false
    private var isChangingChannel = false
    private val showSidebar = mutableStateOf(false)
    private val selectedCategory = mutableStateOf("all") // all, favorites, recent, or category name
    private val selectedSidebarIndex = mutableStateOf(0)

    private data class VideoSettings(
        val orientation: String,
        val acceleration: String,
        val aspectRatio: String
    )

    private fun readVideoSettings(): VideoSettings {
        val prefs = getSharedPreferences("video_settings", MODE_PRIVATE)
        return VideoSettings(
            orientation = prefs.getString("orientation", "Sensor Landscape") ?: "Sensor Landscape",
            acceleration = prefs.getString("acceleration", "HW (Hardware)") ?: "HW (Hardware)",
            aspectRatio = prefs.getString("aspect_ratio", "Fit") ?: "Fit"
        )
    }

    private fun applyRequestedOrientation(setting: String) {
        requestedOrientation = when (setting) {
            "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "Auto" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun hwFlagsForAcceleration(setting: String): Pair<Boolean, Boolean> {
        return when (setting) {
            "SW (Software)" -> false to false
            "HW+ (Hardware+)" -> true to true
            else -> true to false
        }
    }

    private fun avcodecHwOptionForAcceleration(setting: String): String {
        return when (setting) {
            "SW (Software)" -> "--avcodec-hw=none"
            "HW (Hardware)" -> "--avcodec-hw=mediacodec"
            "HW+ (Hardware+)" -> "--avcodec-hw=any"
            else -> "--avcodec-hw=any"
        }
    }

    private fun applyHwDecoderToMedia(media: Media, accelerationSetting: String) {
        val (enabled, force) = hwFlagsForAcceleration(accelerationSetting)
        try {
            media.setHWDecoderEnabled(enabled, force)
        } catch (e: Exception) {
            android.util.Log.w("PlayerActivityVLC", "setHWDecoderEnabled failed", e)
        }
    }

    private fun setPlayerAspectRatio(player: MediaPlayer?, ratio: String?) {
        try {
            player?.javaClass?.getMethod("setAspectRatio", String::class.java)?.invoke(player, ratio)
        } catch (_: Exception) {
        }
    }

    private fun setPlayerCropGeometry(player: MediaPlayer?, crop: String?) {
        try {
            player?.javaClass?.getMethod("setCropGeometry", String::class.java)?.invoke(player, crop)
        } catch (_: Exception) {
        }
    }

    private fun setPlayerScale(player: MediaPlayer?, scale: Float) {
        try {
            player?.javaClass?.getMethod("setScale", java.lang.Float.TYPE)?.invoke(player, scale)
        } catch (_: Exception) {
        }
    }

    private fun applyAspectRatioSetting(player: MediaPlayer?, aspectRatioSetting: String) {
        // Use reflection to stay compatible across libvlc versions
        when (aspectRatioSetting) {
            "Fill" -> {
                // Typical TV is 16:9; cropping to 16:9 fills screen.
                setPlayerAspectRatio(player, null)
                setPlayerCropGeometry(player, "16:9")
                setPlayerScale(player, 0f)
            }
            "Zoom" -> {
                setPlayerAspectRatio(player, null)
                setPlayerCropGeometry(player, null)
                setPlayerScale(player, 1.25f)
            }
            "16:9" -> {
                setPlayerCropGeometry(player, null)
                setPlayerScale(player, 0f)
                setPlayerAspectRatio(player, "16:9")
            }
            "4:3" -> {
                setPlayerCropGeometry(player, null)
                setPlayerScale(player, 0f)
                setPlayerAspectRatio(player, "4:3")
            }
            else -> {
                // Fit
                setPlayerCropGeometry(player, null)
                setPlayerAspectRatio(player, null)
                setPlayerScale(player, 0f)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        android.util.Log.d("PlayerActivityVLC", "onCreate - using VLC player")
        
        val settings = readVideoSettings()
        applyRequestedOrientation(settings.orientation)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
        
        val rootLayout = FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        
        // Use VLCVideoLayout - the official way to display VLC video
        videoLayout = VLCVideoLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            keepScreenOn = true
        }
        rootLayout.addView(videoLayout)
        
        videoLayout.setOnClickListener {
            showChannelList.value = !showChannelList.value
        }
        
        val overlayComposeView = ComposeView(this).apply {
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
        
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        channelId = intent.getIntExtra("CHANNEL_ID", -1)
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
            ChannelRepository.loadChannels(this)

            val settings = readVideoSettings()
            
            val ch = ChannelRepository.getChannelById(channelId)
            if (ch == null) {
                android.widget.Toast.makeText(this, "Channel not found", android.widget.Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            android.util.Log.d("PlayerActivityVLC", "Playing: ${ch.name}, URL: ${ch.url}")
            currentChannelName.value = ch.name
            
            val options = arrayListOf(
                "--aout=opensles",
                "--audio-time-stretch",
                "-vvv",
                "--avcodec-skiploopfilter=1",
                "--avcodec-skip-frame=0",
                "--avcodec-skip-idct=0",
                "--network-caching=1500",
                "--live-caching=1500",
                "--file-caching=1500",
                "--clock-jitter=0",
                "--clock-synchro=0",
                "--drop-late-frames",
                "--skip-frames",
                avcodecHwOptionForAcceleration(settings.acceleration)
            )
            
            libVLC = LibVLC(this, options)
            
            vlcPlayer = MediaPlayer(libVLC).apply {
                // Mute audio dari awal sebelum event apapun
                volume = 0
                
                setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Playing -> {
                            android.util.Log.d("PlayerActivityVLC", "Event: Playing")
                            bufferingPercent.value = 100f
                        }
                        MediaPlayer.Event.Paused -> {
                            android.util.Log.d("PlayerActivityVLC", "Event: Paused")
                        }
                        MediaPlayer.Event.Buffering -> {
                            val percent = event.buffering
                            bufferingPercent.value = percent
                            if (percent >= 95f) {
                                isBuffering.value = false
                            }
                        }
                        MediaPlayer.Event.Opening -> {
                            android.util.Log.d("PlayerActivityVLC", "Event: Opening")
                            isBuffering.value = true
                            bufferingPercent.value = 0f
                        }
                        MediaPlayer.Event.Stopped -> {
                            android.util.Log.d("PlayerActivityVLC", "Event: Stopped")
                            isBuffering.value = false
                        }
                        MediaPlayer.Event.EndReached -> {
                            android.util.Log.d("PlayerActivityVLC", "Event: EndReached")
                            isBuffering.value = false
                        }
                        MediaPlayer.Event.Vout -> {
                            android.util.Log.d("PlayerActivityVLC", "Event: Vout count=${event.voutCount}")
                            // Video output tersedia = video sudah siap ditampilkan
                            if (event.voutCount > 0 && !isVideoReady.value) {
                                isVideoReady.value = true
                                android.util.Log.d("PlayerActivityVLC", "Video ready")
                                // Hide buffering
                                isBuffering.value = false
                            }
                        }
                        MediaPlayer.Event.TimeChanged -> {
                            // TimeChanged berarti video sudah jalan
                            // Tapi hanya hide buffering jika video sudah ready
                            if (isBuffering.value && isVideoReady.value) {
                                isBuffering.value = false
                            }
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            android.util.Log.e("PlayerActivityVLC", "Event: Error")
                            isBuffering.value = false
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                    this@PlayerActivityVLC,
                                    "Gagal memutar channel",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
            
            // Attach to VLCVideoLayout - handles surface internally
            vlcPlayer?.attachViews(videoLayout, null, false, false)
            
            val media = Media(libVLC, Uri.parse(ch.url)).apply {
                applyHwDecoderToMedia(this, settings.acceleration)
                addOption(":network-caching=1500")
                addOption(":live-caching=1500")
            }
            
            vlcPlayer?.media = media
            media.release()
            
            // Reset video ready state
            isVideoReady.value = false
            pendingPlay = false
            
            // Langsung play dengan volume normal
            vlcPlayer?.volume = 100
            applyAspectRatioSetting(vlcPlayer, settings.aspectRatio)
            vlcPlayer?.play()
            
            android.util.Log.d("PlayerActivityVLC", "Video playing with full audio")
            
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityVLC", "Error initializing player", e)
            android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    // Enable audio setelah video ready - reload media tanpa :no-audio
    private fun enableAudioAfterVideoReady() {
        try {
            val ch = ChannelRepository.getChannelById(channelId) ?: return

            val settings = readVideoSettings()
            
            // Get current position
            val currentPos = vlcPlayer?.time ?: 0L
            
            android.util.Log.d("PlayerActivityVLC", "Enabling audio at position $currentPos")
            
            // Create new media WITH audio
            val media = Media(libVLC, Uri.parse(ch.url)).apply {
                applyHwDecoderToMedia(this, settings.acceleration)
                addOption(":network-caching=300")
                addOption(":live-caching=300")
                // NO :no-audio option - audio akan aktif
            }
            
            vlcPlayer?.media = media
            media.release()
            
            // Set volume penuh
            vlcPlayer?.volume = 100

            applyAspectRatioSetting(vlcPlayer, settings.aspectRatio)
            
            // Play dari posisi yang sama (untuk live stream akan start dari current)
            vlcPlayer?.play()
            
            android.util.Log.d("PlayerActivityVLC", "Audio enabled, playing with full volume")
            
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityVLC", "Error enabling audio", e)
        }
    }
    
    private fun releasePlayer() {
        try {
            vlcPlayer?.apply {
                // Mute dan stop audio dulu sebelum release
                volume = 0
                audioTrack = -1
                stop()
                detachViews()
                release()
            }
            vlcPlayer = null
            
            libVLC?.release()
            libVLC = null
            
            // Reset saved audio track
            savedAudioTrack = -1
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityVLC", "Error releasing player", e)
        }
    }
    
    private fun playChannel(ch: Channel) {
        android.util.Log.d("PlayerActivityVLC", "playChannel called: ${ch.name}, URL: ${ch.url}")
        
        // Cegah double call
        if (isChangingChannel) {
            android.util.Log.d("PlayerActivityVLC", "Already changing channel, ignoring")
            return
        }
        isChangingChannel = true
        
        // Close overlay first
        showChannelList.value = false
        
        // Set buffering state dan reset video ready
        isBuffering.value = true
        bufferingPercent.value = 0f
        isVideoReady.value = false
        savedAudioTrack = -1
        pendingPlay = false
        
        // Update channel info
        channelId = ch.id
        currentChannelName.value = ch.name
        
        // Release SYNCHRONOUSLY di main thread
        try {
            vlcPlayer?.apply {
                volume = 0
                stop()
                detachViews()
                release()
            }
            vlcPlayer = null
            
            libVLC?.release()
            libVLC = null
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityVLC", "Error stopping player", e)
        }
        
        // Delay lalu init baru
        videoLayout.postDelayed({
            isChangingChannel = false
            initializePlayer()
        }, 300)
    }
    
    // Direct play untuk D-pad - sama seperti playChannel, full reinit
    private fun playChannelDirect(ch: Channel) {
        android.util.Log.d("PlayerActivityVLC", "playChannelDirect called: ${ch.name}, URL: ${ch.url}")
        
        // Cegah double call
        if (isChangingChannel) {
            android.util.Log.d("PlayerActivityVLC", "Already changing channel, ignoring")
            return
        }
        isChangingChannel = true
        
        // Close overlay first
        showChannelList.value = false
        
        // Set buffering state dan reset video ready
        isBuffering.value = true
        bufferingPercent.value = 0f
        isVideoReady.value = false
        savedAudioTrack = -1
        pendingPlay = false
        
        // Update channel info
        channelId = ch.id
        currentChannelName.value = ch.name
        
        // Release SYNCHRONOUSLY di main thread
        try {
            vlcPlayer?.apply {
                volume = 0
                stop()
                detachViews()
                release()
            }
            vlcPlayer = null
            
            libVLC?.release()
            libVLC = null
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityVLC", "Error stopping player", e)
        }
        
        // Delay lalu init baru
        videoLayout.postDelayed({
            isChangingChannel = false
            initializePlayer()
        }, 300)
    }
    
    // Helper function to get filtered channels based on selected category
    private fun getFilteredChannels(): List<Channel> {
        val allChannels = ChannelRepository.getAllChannels()
        return when (selectedCategory.value) {
            "all" -> allChannels
            "favorites" -> ChannelRepository.getFavorites()
            "recent" -> allChannels.take(10)
            else -> allChannels.filter { it.category == selectedCategory.value }
        }
    }
    
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        
        // Untuk D-pad center/enter, kita perlu handle kedua ACTION_DOWN dan ACTION_UP
        // untuk mencegah event diteruskan ke view lain
        if (showChannelList.value) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, 
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                23 -> {
                    // Consume both DOWN and UP untuk mencegah double action
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (showSidebar.value) {
                            // Di sidebar, pilih kategori
                            val allChannels = ChannelRepository.getAllChannels()
                            val categories = allChannels.map { it.category }.distinct().filter { it.isNotEmpty() }
                            val sidebarItems = mutableListOf<String>().apply {
                                add("all")
                                add("favorites")
                                add("recent")
                                addAll(categories)
                            }
                            if (selectedSidebarIndex.value in sidebarItems.indices) {
                                selectedCategory.value = sidebarItems[selectedSidebarIndex.value]
                                selectedListIndex.value = 0
                                showSidebar.value = false // Kembali ke channel list
                            }
                        } else {
                            // Di channel list, play channel
                            val filteredChannels = getFilteredChannels()
                            android.util.Log.d("PlayerActivityVLC", "OK DOWN - playing index: ${selectedListIndex.value}")
                            if (selectedListIndex.value in filteredChannels.indices) {
                                val selectedChannel = filteredChannels[selectedListIndex.value]
                                android.util.Log.d("PlayerActivityVLC", "Playing channel: ${selectedChannel.name}")
                                playChannelDirect(selectedChannel)
                            }
                        }
                    }
                    return true // Consume event completely
                }
            }
        }
        
        // Hanya handle ACTION_DOWN untuk tombol lain
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        
        android.util.Log.d("PlayerActivityVLC", "dispatchKeyEvent: keyCode=$keyCode, overlay=${showChannelList.value}, sidebar=${showSidebar.value}")
        
        val allChannels = ChannelRepository.getAllChannels()
        val currentIndex = allChannels.indexOfFirst { it.id == channelId }
        
        // Jika channel list overlay tampil
        if (showChannelList.value) {
            if (showSidebar.value) {
                // Navigasi di sidebar
                val categories = allChannels.map { it.category }.distinct().filter { it.isNotEmpty() }
                val sidebarCount = 3 + categories.size // all, favorites, recent + categories
                
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (selectedSidebarIndex.value > 0) {
                            selectedSidebarIndex.value = selectedSidebarIndex.value - 1
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (selectedSidebarIndex.value < sidebarCount - 1) {
                            selectedSidebarIndex.value = selectedSidebarIndex.value + 1
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        showSidebar.value = false
                        return true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        showSidebar.value = false
                        return true
                    }
                }
            } else {
                // Navigasi di channel list
                val filteredChannels = getFilteredChannels()
                
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (selectedListIndex.value > 0) {
                            selectedListIndex.value = selectedListIndex.value - 1
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (selectedListIndex.value < filteredChannels.size - 1) {
                            selectedListIndex.value = selectedListIndex.value + 1
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        showSidebar.value = true
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        showChannelList.value = false
                        return true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        showChannelList.value = false
                        return true
                    }
                }
            }
        } else {
            // Overlay tidak tampil
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (currentIndex > 0) {
                        playChannel(allChannels[currentIndex - 1])
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (currentIndex < allChannels.size - 1) {
                        playChannel(allChannels[currentIndex + 1])
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, 
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_DPAD_LEFT,
                23 -> {
                    selectedListIndex.value = if (currentIndex >= 0) currentIndex else 0
                    showChannelList.value = true
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    selectedListIndex.value = if (currentIndex >= 0) currentIndex else 0
                    showChannelList.value = true
                    return true
                }
            }
        }
        
        return super.dispatchKeyEvent(event)
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handled by dispatchKeyEvent
        return super.onKeyDown(keyCode, event)
    }
    
    @Composable
    private fun ChannelListOverlay() {
        val allChannels: List<Channel> = remember { ChannelRepository.getAllChannels() }
        val favoriteChannels: List<Channel> = remember { ChannelRepository.getFavorites() }
        val categories: List<String> = remember {
            val base = allChannels
                .map { ch -> ch.category }
                .distinct()
                .filter { cat -> cat.isNotEmpty() }

            val eventCategory = base.firstOrNull { it.trim().equals("event", ignoreCase = true) }
            if (eventCategory == null) base else listOf(eventCategory) + base.filterNot { it == eventCategory }
        }
        
        // Filter channels based on selected category
        val filteredChannels: List<Channel> = remember(selectedCategory.value) {
            when (selectedCategory.value) {
                "all" -> allChannels
                "favorites" -> favoriteChannels
                "recent" -> allChannels.take(10) // Last 10 channels as recent (placeholder)
                else -> allChannels.filter { ch -> ch.category == selectedCategory.value }
            }
        }
        
        val listState = rememberLazyListState()
        val sidebarListState = rememberLazyListState()
        val currentChId = channelId
        val selectedIdx = selectedListIndex.value
        
        // Build sidebar items
        val sidebarItems = remember(categories) {
            mutableListOf<Pair<String, String>>().apply {
                add("all" to "Semua Channel")
                add("favorites" to "Favorit")
                add("recent" to "Terakhir Ditonton")
                categories.forEach { cat ->
                    add(cat to if (cat.trim().equals("event", ignoreCase = true)) "EVENTS" else cat)
                }
            }
        }
        
        // Auto scroll ke selected item
        LaunchedEffect(showChannelList.value, selectedIdx, selectedCategory.value) {
            if (showChannelList.value && selectedIdx >= 0 && selectedIdx < filteredChannels.size) {
                listState.animateScrollToItem(maxOf(0, selectedIdx - 2))
            }
        }
        
        if (showChannelList.value) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Single Card dengan sidebar dan channel list
                    Card(
                        modifier = Modifier
                            .width(if (showSidebar.value) 550.dp else 380.dp)
                            .fillMaxHeight()
                            .padding(16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1E1E1E).copy(alpha = 0.9f)
                        )
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            // Sidebar kategori (muncul jika showSidebar true)
                            if (showSidebar.value) {
                                Column(
                                    modifier = Modifier
                                        .width(170.dp)
                                        .fillMaxHeight()
                                        .background(Color(0xFF252525))
                                ) {
                                    LazyColumn(
                                        state = sidebarListState,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        itemsIndexed(sidebarItems) { index, (key, label) ->
                                            val isSelected = selectedCategory.value == key
                                            val isSidebarSelected = index == selectedSidebarIndex.value
                                            
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        when {
                                                            isSidebarSelected -> Color(0xFF424242)
                                                            isSelected -> Color(0xFF1976D2)
                                                            else -> Color.Transparent
                                                        }
                                                    )
                                                    .clickable {
                                                        selectedCategory.value = key
                                                        selectedListIndex.value = 0
                                                        selectedSidebarIndex.value = index
                                                        showSidebar.value = false
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = when (key) {
                                                        "all" -> Icons.AutoMirrored.Filled.List
                                                        "favorites" -> Icons.Filled.Favorite
                                                        "recent" -> Icons.Filled.History
                                                        else -> Icons.Filled.PlayArrow
                                                    },
                                                    contentDescription = null,
                                                    tint = if (isSelected) Color.White else Color.Gray,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = label,
                                                    color = if (isSelected) Color.White else Color.Gray,
                                                    fontSize = 13.sp,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                                
                                // Divider antara sidebar dan channel list
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(Color.Gray.copy(alpha = 0.3f))
                                )
                            }
                            
                            // Channel list
                            Column(modifier = Modifier.weight(1f)) {
                                // Header - klik untuk toggle sidebar
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showSidebar.value = !showSidebar.value }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = when (selectedCategory.value) {
                                                "all" -> Icons.AutoMirrored.Filled.List
                                                "favorites" -> Icons.Filled.Favorite
                                                "recent" -> Icons.Filled.History
                                                else -> Icons.Filled.PlayArrow
                                            },
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = when (selectedCategory.value) {
                                                "all" -> "Semua Channel"
                                                "favorites" -> "Favorit"
                                                "recent" -> "Terakhir Ditonton"
                                                else -> selectedCategory.value
                                            },
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                        Text(
                                            text = " (${filteredChannels.size})",
                                            color = Color.Gray,
                                            fontSize = 14.sp
                                        )
                                        Icon(
                                            imageVector = if (showSidebar.value) 
                                                Icons.Default.KeyboardArrowLeft 
                                            else 
                                                Icons.Default.KeyboardArrowRight,
                                            contentDescription = "Toggle sidebar",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(onClick = { showChannelList.value = false }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Close",
                                            tint = Color.White
                                        )
                                    }
                                }
                                
                                Divider(color = Color.Gray.copy(alpha = 0.3f))
                                
                                // Channel list
                                if (filteredChannels.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Tidak ada channel",
                                            color = Color.Gray,
                                            fontSize = 14.sp
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        state = listState,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    itemsIndexed(filteredChannels) { index, item ->
                                        val isCurrentChannel = item.id == currentChId
                                        val isSelected = index == selectedIdx
                                        val isFavorite = favoriteChannels.any { fav -> fav.id == item.id }
                                        
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    when {
                                                        isSelected && isCurrentChannel -> Color(0xFF1976D2)
                                                        isSelected -> Color(0xFF424242)
                                                        isCurrentChannel -> Color(0xFF2196F3)
                                                        else -> Color.Transparent
                                                    }
                                                )
                                                .clickable { 
                                                    selectedListIndex.value = index
                                                    playChannel(item) 
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = (index + 1).toString(),
                                                color = if (isSelected) Color.White else Color.Gray,
                                                fontSize = 12.sp,
                                                modifier = Modifier.width(30.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    maxLines = 1
                                                )
                                                if (item.category.isNotEmpty() && selectedCategory.value == "all") {
                                                    Text(
                                                        text = item.category,
                                                        color = Color.Gray,
                                                        fontSize = 11.sp,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                            if (isFavorite) {
                                                Icon(
                                                    Icons.Filled.Favorite,
                                                    contentDescription = null,
                                                    tint = Color.Red,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            if (isCurrentChannel) {
                                                Text(
                                                    text = "▶",
                                                    color = Color.White,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                    
                    // Area kosong untuk close overlay
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { showChannelList.value = false }
                    )
                }
            }
        }
        
        // Buffering indicator
        if (isBuffering.value) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        color = Color.White,
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.padding(8.dp))
                    Text(
                        text = if (bufferingPercent.value > 0) "Buffering ${bufferingPercent.value.toInt()}%" else "Loading...",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
        
        if (!showChannelList.value && currentChannelName.value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = currentChannelName.value,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
