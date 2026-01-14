package com.sihiver.mqltv

import android.content.pm.ActivityInfo
import android.content.Intent
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
import com.sihiver.mqltv.repository.AuthRepository
import com.sihiver.mqltv.repository.ChannelRepository
import com.sihiver.mqltv.ui.player.PlayerChannelListNavState
import com.sihiver.mqltv.ui.player.PlayerChannelListOverlay
import com.sihiver.mqltv.ui.player.handlePlayerChannelListKeyEvent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private var expiryWatcherJob: Job? = null

    private fun startExpiryWatcher() {
        expiryWatcherJob?.cancel()
        expiryWatcherJob = lifecycleScope.launch {
            while (true) {
                if (!AuthRepository.isLoggedIn(this@PlayerActivityVLC)) {
                    startActivity(
                        Intent(this@PlayerActivityVLC, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                    return@launch
                }

                if (AuthRepository.isExpiredNow(this@PlayerActivityVLC)) {
                    startActivity(
                        Intent(this@PlayerActivityVLC, ExpiredActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                    return@launch
                }

                if (AuthRepository.probeExpiredFromPlaylistUrl(this@PlayerActivityVLC)) {
                    startActivity(
                        Intent(this@PlayerActivityVLC, ExpiredActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                    return@launch
                }

                if (AuthRepository.probeExpiredFromLoginIfNeeded(this@PlayerActivityVLC)) {
                    startActivity(
                        Intent(this@PlayerActivityVLC, ExpiredActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                    return@launch
                }

                delay(30_000)
            }
        }
    }

    private fun stopExpiryWatcher() {
        expiryWatcherJob?.cancel()
        expiryWatcherJob = null
    }

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

    override fun onResume() {
        super.onResume()
        startExpiryWatcher()
    }

    override fun onPause() {
        stopExpiryWatcher()
        super.onPause()
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

        // Safety gate
        if (!AuthRepository.isLoggedIn(this)) {
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
            return
        }

        if (AuthRepository.isExpiredNow(this)) {
            startActivity(
                Intent(this, ExpiredActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
            return
        }
        
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
        val consumed = handlePlayerChannelListKeyEvent(
            event = event,
            currentChannelId = channelId,
            nav = PlayerChannelListNavState(
                showChannelList = showChannelList,
                showSidebar = showSidebar,
                selectedCategory = selectedCategory,
                selectedListIndex = selectedListIndex,
                selectedSidebarIndex = selectedSidebarIndex,
            ),
            onPlayChannel = { ch -> playChannelDirect(ch) },
        )
        return if (consumed) true else super.dispatchKeyEvent(event)
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handled by dispatchKeyEvent
        return super.onKeyDown(keyCode, event)
    }

    @Composable
    private fun ChannelListOverlay() {
        PlayerChannelListOverlay(
            nav = PlayerChannelListNavState(
                showChannelList = showChannelList,
                showSidebar = showSidebar,
                selectedCategory = selectedCategory,
                selectedListIndex = selectedListIndex,
                selectedSidebarIndex = selectedSidebarIndex,
            ),
            currentChannelId = channelId,
            onPlayChannel = { ch -> playChannelDirect(ch) },
        )
        
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
