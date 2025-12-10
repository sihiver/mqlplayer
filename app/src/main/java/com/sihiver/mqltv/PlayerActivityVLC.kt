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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        android.util.Log.d("PlayerActivityVLC", "onCreate - using VLC player")
        
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
                "--avcodec-hw=any"
            )
            
            libVLC = LibVLC(this, options)
            
            vlcPlayer = MediaPlayer(libVLC).apply {
                setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Playing -> {
                            android.util.Log.d("PlayerActivityVLC", "Event: Playing")
                            // Playing means video is running, hide buffering
                            isBuffering.value = false
                            bufferingPercent.value = 100f
                        }
                        MediaPlayer.Event.Paused -> {
                            android.util.Log.d("PlayerActivityVLC", "Event: Paused")
                        }
                        MediaPlayer.Event.Buffering -> {
                            val percent = event.buffering
                            android.util.Log.d("PlayerActivityVLC", "Event: Buffering $percent%")
                            bufferingPercent.value = percent
                            // Only show buffering if percent is low (< 95%)
                            // Some streams report 100% but still buffer briefly
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
                            // Video output available means playback started
                            if (event.voutCount > 0) {
                                isBuffering.value = false
                            }
                        }
                        MediaPlayer.Event.TimeChanged -> {
                            // TimeChanged means video is playing, definitely not buffering
                            if (isBuffering.value) {
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
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=1500")
                addOption(":live-caching=1500")
            }
            
            vlcPlayer?.media = media
            media.release()
            vlcPlayer?.play()
            
            android.util.Log.d("PlayerActivityVLC", "Video attached and playing")
            
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityVLC", "Error initializing player", e)
            android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun releasePlayer() {
        try {
            vlcPlayer?.apply {
                stop()
                detachViews()
                release()
            }
            vlcPlayer = null
            
            libVLC?.release()
            libVLC = null
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityVLC", "Error releasing player", e)
        }
    }
    
    private fun playChannel(ch: Channel) {
        android.util.Log.d("PlayerActivityVLC", "playChannel called: ${ch.name}, URL: ${ch.url}")
        
        // Close overlay first
        showChannelList.value = false
        
        // Set buffering state
        isBuffering.value = true
        bufferingPercent.value = 0f
        
        // Update channel info
        channelId = ch.id
        currentChannelName.value = ch.name
        
        // Release current player completely and reinitialize (like from home)
        releasePlayer()
        
        // Small delay then reinitialize
        videoLayout.postDelayed({
            initializePlayer()
        }, 100)
    }
    
    // Direct play untuk D-pad - sama seperti playChannel, full reinit
    private fun playChannelDirect(ch: Channel) {
        android.util.Log.d("PlayerActivityVLC", "playChannelDirect called: ${ch.name}, URL: ${ch.url}")
        
        // Close overlay first
        showChannelList.value = false
        
        // Set buffering state
        isBuffering.value = true
        bufferingPercent.value = 0f
        
        // Update channel info
        channelId = ch.id
        currentChannelName.value = ch.name
        
        // Release current player completely and reinitialize
        releasePlayer()
        
        // Small delay then reinitialize
        videoLayout.postDelayed({
            initializePlayer()
        }, 100)
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
                        val channels = ChannelRepository.getAllChannels()
                        android.util.Log.d("PlayerActivityVLC", "OK DOWN - playing index: ${selectedListIndex.value}")
                        if (selectedListIndex.value in channels.indices) {
                            val selectedChannel = channels[selectedListIndex.value]
                            android.util.Log.d("PlayerActivityVLC", "Playing channel: ${selectedChannel.name}, URL: ${selectedChannel.url}")
                            
                            // Call play directly here
                            playChannelDirect(selectedChannel)
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
        
        android.util.Log.d("PlayerActivityVLC", "dispatchKeyEvent: keyCode=$keyCode, overlay=${showChannelList.value}")
        
        val channels = ChannelRepository.getAllChannels()
        val currentIndex = channels.indexOfFirst { it.id == channelId }
        
        // Jika channel list overlay tampil
        if (showChannelList.value) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (selectedListIndex.value > 0) {
                        selectedListIndex.value = selectedListIndex.value - 1
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (selectedListIndex.value < channels.size - 1) {
                        selectedListIndex.value = selectedListIndex.value + 1
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    showChannelList.value = false
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    showChannelList.value = false
                    return true
                }
            }
        } else {
            // Overlay tidak tampil
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (currentIndex > 0) {
                        playChannel(channels[currentIndex - 1])
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (currentIndex < channels.size - 1) {
                        playChannel(channels[currentIndex + 1])
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
        val channels = remember { ChannelRepository.getAllChannels() }
        val listState = rememberLazyListState()
        val currentChId = channelId
        val currentIndex = channels.indexOfFirst { it.id == currentChId }
        val selectedIdx = selectedListIndex.value
        
        // Auto scroll ke selected item saat list dibuka atau selected berubah
        LaunchedEffect(showChannelList.value, selectedIdx) {
            if (showChannelList.value && selectedIdx >= 0) {
                listState.animateScrollToItem(maxOf(0, selectedIdx - 2))
            }
        }
        
        if (showChannelList.value) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Card(
                        modifier = Modifier
                            .width(350.dp)
                            .fillMaxHeight()
                            .padding(16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1E1E1E)
                        )
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.List,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Channels (VLC)",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
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
                            
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(channels) { index, item ->
                                    val isCurrentChannel = item.id == currentChId
                                    val isSelected = index == selectedIdx
                                    
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                when {
                                                    isSelected && isCurrentChannel -> Color(0xFF1976D2) // Selected + playing
                                                    isSelected -> Color(0xFF424242) // Selected only
                                                    isCurrentChannel -> Color(0xFF2196F3) // Playing only
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
                                        Text(
                                            text = item.name,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            maxLines = 1
                                        )
                                        if (isCurrentChannel) {
                                            Spacer(modifier = Modifier.weight(1f))
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
