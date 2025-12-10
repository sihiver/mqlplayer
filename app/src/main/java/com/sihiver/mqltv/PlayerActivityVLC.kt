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
import androidx.compose.foundation.layout.width
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
                            android.util.Log.d("PlayerActivityVLC", "Playing")
                        }
                        MediaPlayer.Event.Buffering -> {
                            android.util.Log.d("PlayerActivityVLC", "Buffering: ${event.buffering}%")
                        }
                        MediaPlayer.Event.Vout -> {
                            android.util.Log.d("PlayerActivityVLC", "Vout count: ${event.voutCount}")
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            android.util.Log.e("PlayerActivityVLC", "Error encountered")
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
        try {
            channelId = ch.id
            currentChannelName.value = ch.name
            
            vlcPlayer?.stop()
            
            val media = Media(libVLC, Uri.parse(ch.url)).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=1500")
                addOption(":live-caching=1500")
            }
            
            vlcPlayer?.media = media
            media.release()
            vlcPlayer?.play()
            
            showChannelList.value = false
            
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityVLC", "Error playing channel", e)
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val channels = ChannelRepository.getAllChannels()
        val currentIndex = channels.indexOfFirst { it.id == channelId }
        
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
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                showChannelList.value = !showChannelList.value
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                showChannelList.value = !showChannelList.value
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    @Composable
    private fun ChannelListOverlay() {
        val channels = remember { ChannelRepository.getAllChannels() }
        val listState = rememberLazyListState()
        val currentChId = channelId
        val currentIndex = channels.indexOfFirst { it.id == currentChId }
        
        LaunchedEffect(showChannelList.value, currentIndex) {
            if (showChannelList.value && currentIndex >= 0) {
                listState.animateScrollToItem(maxOf(0, currentIndex - 2))
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
                                    
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (isCurrentChannel) Color(0xFF2196F3)
                                                else Color.Transparent
                                            )
                                            .clickable { playChannel(item) }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = (index + 1).toString(),
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.width(30.dp)
                                        )
                                        Text(
                                            text = item.name,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            maxLines = 1
                                        )
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
