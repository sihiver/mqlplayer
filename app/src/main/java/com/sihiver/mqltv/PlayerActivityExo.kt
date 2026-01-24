package com.sihiver.mqltv

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.repository.AuthRepository
import com.sihiver.mqltv.repository.ChannelRepository
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivityExo : ComponentActivity() {

    private companion object {
        private const val NUMERIC_INPUT_COMMIT_MS = 1_200L
    }
    
    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var channelId: Int = -1
    private var currentChannelIndex = mutableStateOf(0)
    private val numericInputDisplay = mutableStateOf("")
    private val uiHandler = Handler(Looper.getMainLooper())

    private val numericChannelInput = StringBuilder()
    private var numericCommitRunnable: Runnable? = null

    private lateinit var channelListLauncher: ActivityResultLauncher<Intent>

    private var expiryWatcherJob: Job? = null

    private var isClosingPlayer: Boolean = false
    private var isReleasingPlayer: Boolean = false

    private var idleCloseTimeoutMs: Long = 0L
    private var lastUserInteractionAtMs: Long = 0L
    private var idleCloseRunnable: Runnable? = null

    private fun startExpiryWatcher() {
        expiryWatcherJob?.cancel()
        expiryWatcherJob = lifecycleScope.launch {
            while (true) {
                if (!AuthRepository.isLoggedIn(this@PlayerActivityExo)) {
                    startActivity(
                        Intent(this@PlayerActivityExo, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                    return@launch
                }

                if (AuthRepository.isExpiredNow(this@PlayerActivityExo)) {
                    startActivity(
                        Intent(this@PlayerActivityExo, ExpiredActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                    return@launch
                }

                if (AuthRepository.probeExpiredFromPlaylistUrl(this@PlayerActivityExo)) {
                    startActivity(
                        Intent(this@PlayerActivityExo, ExpiredActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                    return@launch
                }

                if (AuthRepository.probeExpiredFromLoginIfNeeded(this@PlayerActivityExo)) {
                    startActivity(
                        Intent(this@PlayerActivityExo, ExpiredActivity::class.java)
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

    private fun readIdleCloseTimeoutMs(): Long {
        val prefs = getSharedPreferences("video_settings", Context.MODE_PRIVATE)
        val minutes = prefs.getInt("idle_close_minutes", -1)
        if (minutes >= 0) {
            if (minutes <= 0) return 0L
            return minutes.toLong() * 60L * 1000L
        }

        // Backward compat
        val hours = prefs.getInt("idle_close_hours", 0)
        if (hours <= 0) return 0L
        return hours.toLong() * 60L * 60L * 1000L
    }

    private fun markUserInteraction() {
        if (idleCloseTimeoutMs <= 0L) return
        lastUserInteractionAtMs = SystemClock.elapsedRealtime()
    }

    private fun startIdleCloseWatcher() {
        stopIdleCloseWatcher()
        idleCloseTimeoutMs = readIdleCloseTimeoutMs()
        if (idleCloseTimeoutMs <= 0L) return

        lastUserInteractionAtMs = SystemClock.elapsedRealtime()
        idleCloseRunnable = object : Runnable {
            override fun run() {
                val timeout = idleCloseTimeoutMs
                if (timeout <= 0L) return

                val idleFor = SystemClock.elapsedRealtime() - lastUserInteractionAtMs
                if (idleFor >= timeout) {
                    android.util.Log.d(
                        "PlayerActivityExo",
                        "Idle timeout reached (${timeout}ms), closing player"
                    )
                    closePlayerAndFinish("idle_timeout")
                    return
                }

                uiHandler.postDelayed(this, 60_000L)
            }
        }
        uiHandler.postDelayed(idleCloseRunnable!!, 60_000L)
    }

    private fun stopIdleCloseWatcher() {
        idleCloseRunnable?.let(uiHandler::removeCallbacks)
        idleCloseRunnable = null
        idleCloseTimeoutMs = 0L
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        markUserInteraction()
    }

    private fun digitFromKeyCode(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_0 -> 0
            KeyEvent.KEYCODE_1 -> 1
            KeyEvent.KEYCODE_2 -> 2
            KeyEvent.KEYCODE_3 -> 3
            KeyEvent.KEYCODE_4 -> 4
            KeyEvent.KEYCODE_5 -> 5
            KeyEvent.KEYCODE_6 -> 6
            KeyEvent.KEYCODE_7 -> 7
            KeyEvent.KEYCODE_8 -> 8
            KeyEvent.KEYCODE_9 -> 9
            KeyEvent.KEYCODE_NUMPAD_0 -> 0
            KeyEvent.KEYCODE_NUMPAD_1 -> 1
            KeyEvent.KEYCODE_NUMPAD_2 -> 2
            KeyEvent.KEYCODE_NUMPAD_3 -> 3
            KeyEvent.KEYCODE_NUMPAD_4 -> 4
            KeyEvent.KEYCODE_NUMPAD_5 -> 5
            KeyEvent.KEYCODE_NUMPAD_6 -> 6
            KeyEvent.KEYCODE_NUMPAD_7 -> 7
            KeyEvent.KEYCODE_NUMPAD_8 -> 8
            KeyEvent.KEYCODE_NUMPAD_9 -> 9
            else -> null
        }
    }

    private fun onNumericDigitPressed(digit: Int) {
        if (numericChannelInput.length >= 4) numericChannelInput.setLength(0)
        if (numericChannelInput.isEmpty() && digit == 0) return

        numericChannelInput.append(digit)
        val typed = numericChannelInput.toString().toIntOrNull() ?: return

        // Show typed number
        numericInputDisplay.value = typed.toString()

        numericCommitRunnable?.let(uiHandler::removeCallbacks)
        numericCommitRunnable = Runnable {
            commitNumericChannelSelection()
        }
        uiHandler.postDelayed(numericCommitRunnable!!, NUMERIC_INPUT_COMMIT_MS)
    }

    private fun commitNumericChannelSelection() {
        val typed = numericChannelInput.toString().toIntOrNull()
        numericChannelInput.setLength(0)
        numericInputDisplay.value = ""
        numericCommitRunnable?.let(uiHandler::removeCallbacks)
        numericCommitRunnable = null

        if (typed == null || typed <= 0) return

        ChannelRepository.loadChannels(this)
        val channels = ChannelRepository.getAllChannels()
        val index = typed - 1
        if (index !in channels.indices) {
            android.widget.Toast.makeText(this, "Channel $typed tidak ada", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        switchChannel(channels[index])
    }

    override fun onDestroy() {
        numericCommitRunnable?.let(uiHandler::removeCallbacks)
        numericCommitRunnable = null
        super.onDestroy()
    }
    
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
                android.util.Log.d("PlayerActivityExo", "Back pressed")
                closePlayerAndFinish("back")
            }
        })

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

        // Get channel ID from intent
        channelId = intent.getIntExtra("CHANNEL_ID", -1)
        android.util.Log.d("PlayerActivityExo", "Received channel ID: $channelId")

        channelListLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val selectedId = result.data?.getIntExtra(ChannelListActivity.EXTRA_SELECTED_CHANNEL_ID, -1) ?: -1
                if (selectedId > 0) {
                    ChannelRepository.loadChannels(this)
                    val channel = ChannelRepository.getChannelById(selectedId)
                    if (channel != null) {
                        switchChannel(channel)
                    }
                }
            }
        }

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
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)

            // Use SurfaceView for better hardware decoding on TV
            setUseController(false)
            
            // Keep default layer type - let Android handle it
            // Don't override layer type for SurfaceView

            // Optimize view for TV/low-end devices
            keepScreenOn = true

            // Hide default controller, we'll use custom overlay
            useController = false

            // Disable shutter animation for faster rendering
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)

            // Handle click to show channel list
            setOnClickListener {
                openChannelList()
            }
        }

        rootLayout.addView(playerView)

        val overlayComposeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setContent {
                NumericInputOverlay()
            }
        }
        rootLayout.addView(overlayComposeView)

        setContentView(rootLayout)

        // Hide system UI for immersive experience
        try {
            val isTvDevice = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (isTvDevice) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                // On mobile, keep navigation bar visible so BACK works.
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        } catch (e: Exception) {
            android.util.Log.w("PlayerActivityExo", "Failed to hide system UI: ${e.message}")
        }
    }

    private fun openChannelList() {
        if (!::channelListLauncher.isInitialized) return
        channelListLauncher.launch(ChannelListActivity.createIntent(this, channelId))
    }

    override fun onResume() {
        super.onResume()
        startExpiryWatcher()
        startIdleCloseWatcher()
    }

    override fun onPause() {
        numericCommitRunnable?.let(uiHandler::removeCallbacks)
        numericCommitRunnable = null
        numericChannelInput.setLength(0)
        numericInputDisplay.value = ""
        stopIdleCloseWatcher()
        stopExpiryWatcher()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // Allow BACK button to work normally (for mobile)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            val digit = digitFromKeyCode(keyCode)
            if (digit != null) {
                onNumericDigitPressed(digit)
                return true
            }
        }

        // Consume both DOWN and UP for OK/ENTER/MENU so we don't double-trigger.
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_DPAD_LEFT,
            23 -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    // If user is typing a number, confirm it instead of opening list.
                    if (numericChannelInput.isNotEmpty()) {
                        commitNumericChannelSelection()
                    } else {
                    openChannelList()
                    }
                }
                return true
            }
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                switchToNextChannel(previous = true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                switchToNextChannel(previous = false)
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun initializePlayer() {
        try {
            val prefs = getSharedPreferences("video_settings", Context.MODE_PRIVATE)
            val accelerationSetting = prefs.getString("acceleration", "HW (Hardware)") ?: "HW (Hardware)"

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

            // Use default data source
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this)

            // Use NextRenderersFactory for MPEG-L2 audio support
            // EXTENSION_RENDERER_MODE_PREFER = FFmpeg preferred for audio, but MediaCodec for video
            val renderersFactory = NextRenderersFactory(this).apply {
                setEnableDecoderFallback(true)

                // Map setting "acceleration" ke prioritas decoder.
                // - HW/HW+: MediaCodec diprioritaskan, FFmpeg hanya fallback.
                // - SW: FFmpeg diprioritaskan (best-effort software decode).
                val extensionMode = when (accelerationSetting) {
                    "SW (Software)" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    "HW (Hardware)", "HW+ (Hardware+)" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                }
                setExtensionRendererMode(extensionMode)

                android.util.Log.d(
                    "PlayerActivityExo",
                    "Video acceleration=$accelerationSetting, extensionRendererMode=$extensionMode"
                )
            }

            // Setup DRM if needed
            val drmSessionManagerProvider = if (!channel.drmLicenseUrl.isBlank()) {
                createDrmSessionManagerProvider(channel.drmLicenseUrl)
            } else {
                null
            }

            // Create media source factory with DRM support
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)
                .apply {
                    if (drmSessionManagerProvider != null) {
                        setDrmSessionManagerProvider { drmSessionManagerProvider }
                    }
                }

            // Simple LoadControl - let ExoPlayer manage buffers
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl()

            // Create TrackSelector - use defaults
            val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this)

            // Create ExoPlayer
            exoPlayer = ExoPlayer.Builder(this)
                .setRenderersFactory(renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
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
                            android.util.Log.e("PlayerActivityExo", "Player error: ${error.message}", error)
                            android.util.Log.e("PlayerActivityExo", "Error code: ${error.errorCode}")
                            android.util.Log.e("PlayerActivityExo", "Error cause: ${error.cause?.message}")
                            
                            val channel = ChannelRepository.getChannelById(channelId)
                            val channelName = channel?.name ?: "Unknown"
                            
                            // Log full error stack for DRM debugging
                            var currentError: Throwable? = error
                            while (currentError != null) {
                                android.util.Log.e("PlayerActivityExo", "  -> ${currentError.javaClass.simpleName}: ${currentError.message}")
                                currentError = currentError.cause
                            }
                            
                            // Check for DRM errors
                            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DRM_UNSPECIFIED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR ||
                                error.message?.contains("DRM", ignoreCase = true) == true ||
                                error.cause?.message?.contains("DRM", ignoreCase = true) == true) {
                                
                                val errorMsg = error.cause?.message ?: error.message ?: "Unknown DRM error"
                                val drmMsg = when {
                                    errorMsg.contains("401") -> "✗ DRM: Unauthorized (401)\nToken expired atau salah"
                                    errorMsg.contains("403") -> "✗ DRM: Forbidden (403)\nAkses ditolak"
                                    errorMsg.contains("404") -> "✗ DRM: License server not found (404)"
                                    errorMsg.contains("6004") -> "✗ DRM Error 6004\nLicense acquisition failed"
                                    errorMsg.contains("PROVISIONING") -> "✗ DRM: Device provisioning failed\nPerangkat tidak support"
                                    else -> "✗ DRM Source Error:\n${errorMsg.take(120)}"
                                }
                                
                                android.util.Log.e("PlayerActivityExo", "DRM error details: $errorMsg")
                                
                                runOnUiThread {
                                    android.widget.Toast.makeText(
                                        this@PlayerActivityExo,
                                        drmMsg,
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                                return
                            }

                            // If server denies stream with HTTP 403, treat as expired access.
                            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                                val hasHttp403 = generateSequence<Throwable>(error) { it.cause }
                                    .any { it is HttpDataSource.InvalidResponseCodeException && it.responseCode == 403 }

                                if (hasHttp403) {
                                    startActivity(
                                        Intent(this@PlayerActivityExo, ExpiredActivity::class.java)
                                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                    finish()
                                    return
                                }
                            }
                            
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
                                            openChannelList()
                                        }, 500)
                                    } catch (e: Exception) {
                                        android.util.Log.e("PlayerActivityExo", "Error clearing player state", e)
                                        openChannelList()
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

                                // Open channel list automatically so user can select another channel
                                openChannelList()
                            }
                        }
                    })
                }
            
            // Set player to view
            playerView?.player = exoPlayer
            
            // Create media item and start playback
            val mediaItem = createMediaItem(channel)
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
        if (isReleasingPlayer) return
        val player = exoPlayer ?: return
        isReleasingPlayer = true

        android.util.Log.d("PlayerActivityExo", "Releasing ExoPlayer")
        try {
            try {
                // Detach view first so Surface teardown happens cleanly.
                playerView?.player = null
            } catch (_: Exception) {
                // Ignore view detach issues
            }

            // Stop playback first
            player.stop()
            // Clear all media items
            player.clearMediaItems()
            // Clear video surface (best-effort)
            player.clearVideoSurface()
            // Release player resources
            player.release()
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityExo", "Error releasing player: ${e.message}", e)
        } finally {
            exoPlayer = null
            isReleasingPlayer = false
        }
    }

    private fun closePlayerAndFinish(reason: String) {
        if (isClosingPlayer) return
        if (isFinishing || isDestroyed) return
        isClosingPlayer = true

        android.util.Log.d("PlayerActivityExo", "Closing player & finishing (reason=$reason)")
        try {
            stopIdleCloseWatcher()
            stopExpiryWatcher()
        } catch (_: Exception) {
            // Ignore
        }

        releasePlayer()
        finish()
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
            val mediaItem = createMediaItem(channel)
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

    private fun createDrmSessionManagerProvider(licenseUrl: String): androidx.media3.exoplayer.drm.DrmSessionManager {
        android.util.Log.d("PlayerActivityExo", "=== DRM INIT ===")
        android.util.Log.d("PlayerActivityExo", "License URL: $licenseUrl")
        
        // Toast untuk informasi
        runOnUiThread {
            android.widget.Toast.makeText(
                this,
                "Init DRM Widevine...",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        
        try {
            // Parse license URL untuk extract custom data header jika ada
            // Format: https://lic.drmtoday.com/license-proxy-widevine/cenc/|x-dt-custom-data=JWT_TOKEN
            val parts = licenseUrl.split("|")
            val actualLicenseUrl = parts[0].trim()
            val customHeaders = mutableMapOf<String, String>()
            
            if (parts.size > 1) {
                // Parse custom headers dari format: key1=value1|key2=value2
                parts.drop(1).forEach { header ->
                    val keyValue = header.split("=", limit = 2)
                    if (keyValue.size == 2) {
                        customHeaders[keyValue[0].trim()] = keyValue[1].trim()
                        android.util.Log.d("PlayerActivityExo", "Custom header: ${keyValue[0]} = ${keyValue[1].take(50)}...")
                    }
                }
            }
            
            // Create DRM callback
            val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
            
            val drmCallback = androidx.media3.exoplayer.drm.HttpMediaDrmCallback(
                actualLicenseUrl,
                httpDataSourceFactory
            )
            
            // Set custom headers jika ada (untuk DRMtoday)
            if (customHeaders.isNotEmpty()) {
                customHeaders.forEach { (key, value) ->
                    drmCallback.setKeyRequestProperty(key, value)
                }
                android.util.Log.d("PlayerActivityExo", "DRM callback with ${customHeaders.size} custom headers")
            } else {
                android.util.Log.d("PlayerActivityExo", "DRM callback without custom headers (token in URL)")
            }

            // Create DRM session manager
            val drmSessionManager = androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(drmCallback)
            
            android.util.Log.d("PlayerActivityExo", "DRM session manager created successfully")
            
            return drmSessionManager
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityExo", "Failed to create DRM manager", e)
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    "✗ DRM Init Error: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            throw e
        }
    }

    private fun createMediaItem(channel: Channel): MediaItem {
        val streamUrl = channel.url.trim()
        val licenseUrl = channel.drmLicenseUrl.trim()
        val isDashMpd = streamUrl.endsWith(".mpd", ignoreCase = true)
        
        // If no DRM or not DASH, use simple MediaItem
        if (licenseUrl.isBlank() || !isDashMpd) {
            android.util.Log.d("PlayerActivityExo", "Creating standard MediaItem for: ${channel.name}")
            return MediaItem.fromUri(streamUrl)
        }

        // For DRM content, just return basic MediaItem with MIME type
        // DRM is handled by DrmSessionManager in MediaSourceFactory
        android.util.Log.d("PlayerActivityExo", "Creating DASH MediaItem for: ${channel.name}")
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build()
    }

    @Composable
    private fun NumericInputOverlay() {
        val display = numericInputDisplay.value
        if (display.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = display,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        fontSize = 24.sp
                    )
                }
            }
        }
    }
}
