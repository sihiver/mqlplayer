package com.sihiver.mqltv

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.sihiver.mqltv.repository.AuthRepository
import com.sihiver.mqltv.repository.ChannelRepository
import com.sihiver.mqltv.service.PresenceManager
import com.sihiver.mqltv.ui.theme.MQLTVTheme
import kotlinx.coroutines.launch
import androidx.core.net.toUri

class PlayerActivityNative : ComponentActivity() {

    private var videoView: VideoView? = null
    private var fallbackStarted = false
    private lateinit var presenceManager: PresenceManager

    @OptIn(UnstableApi::class)
    private fun startFallbackPlayer(channelId: Int) {
        if (fallbackStarted) return
        fallbackStarted = true
        
        // Send offline before switching players
        presenceManager.sendOfflinePresence()

        Toast.makeText(
            this,
            "Native decoder gagal, beralih ke ExoPlayer",
            Toast.LENGTH_SHORT
        ).show()

        startActivity(
            Intent(this, PlayerActivityExo::class.java).apply {
                putExtra("CHANNEL_ID", channelId)
            }
        )
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize presence manager
        presenceManager = PresenceManager(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val isTvDevice =
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
                Configuration.UI_MODE_TYPE_TELEVISION
        if (!isTvDevice) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }

        if (!AuthRepository.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        if (AuthRepository.isExpiredNow(this)) {
            startActivity(Intent(this, ExpiredActivity::class.java))
            finish()
            return
        }

        val channelId = intent.getIntExtra("CHANNEL_ID", -1)
        if (channelId < 0) {
            Toast.makeText(this, "Channel ID tidak valid", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Ensure channels loaded
        ChannelRepository.loadChannels(this)
        val channel = ChannelRepository.getChannelById(channelId)
        if (channel == null) {
            Toast.makeText(this, "Channel tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val streamUrl = channel.url.trim()
        if (streamUrl.isBlank()) {
            Toast.makeText(this, "URL stream kosong", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Send online presence
        presenceManager.sendOnlinePresence(channel.name, channel.url)
        // Start heartbeat for native player
        presenceManager.startHeartbeat()

        // Extra safety: probe expiry in background (same behavior as other players)
        lifecycleScope.launch {
            try {
                if (AuthRepository.probeExpiredFromPlaylistUrl(this@PlayerActivityNative) ||
                    AuthRepository.probeExpiredFromLoginIfNeeded(this@PlayerActivityNative)
                ) {
                    startActivity(Intent(this@PlayerActivityNative, ExpiredActivity::class.java))
                    finish()
                }
            } catch (_: Exception) {
            }
        }

        setContent {
            MQLTVTheme {
                val isBuffering = remember { mutableStateOf(true) }

                BackHandler {
                    // Send offline presence before closing
                    presenceManager.sendOfflinePresence()
                    finish()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            VideoView(context).also { vv ->
                                videoView = vv
                                vv.post {
                                    runCatching {
                                        vv.setVideoURI(streamUrl.toUri())
                                    }.onFailure {
                                        isBuffering.value = false
                                        Toast.makeText(
                                            this@PlayerActivityNative,
                                            "Native player error",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                vv.setOnPreparedListener { mp ->
                                    isBuffering.value = false
                                    mp.isLooping = false
                                    if (!vv.isPlaying) {
                                        vv.start()
                                    }
                                }
                                vv.setOnErrorListener { _, _, _ ->
                                    isBuffering.value = false
                                    android.util.Log.w(
                                        "PlayerActivityNative",
                                        "Native player error, falling back to ExoPlayer"
                                    )
                                    startFallbackPlayer(channelId)
                                    true
                                }
                                vv.setOnInfoListener { _, what, _ ->
                                    // 701/702 buffering start/end
                                    if (what == 701) isBuffering.value = true
                                    if (what == 702) isBuffering.value = false
                                    false
                                }
                            }
                        },
                        update = {
                            // no-op
                        },
                    )

                    if (isBuffering.value) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Send offline presence when activity stops
        presenceManager.sendOfflinePresence()
        runCatching {
            videoView?.stopPlayback()
        }
        videoView = null
    }
}
