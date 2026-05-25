package com.sihiver.mqltv

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import com.sihiver.mqltv.repository.ChannelRepository
import com.sihiver.mqltv.ui.live.PortraitLiveGuideScreen
import com.sihiver.mqltv.ui.theme.MQLTVTheme

/**
 * Halaman potret (video + panduan) setelah user memilih channel di grid Live — hanya untuk perangkat non-TV.
 */
@UnstableApi
class PortraitLiveGuideActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "CHANNEL_ID"

        fun createIntent(context: Context, channelId: Int): Intent {
            return Intent(context, PortraitLiveGuideActivity::class.java).putExtra(EXTRA_CHANNEL_ID, channelId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isTv =
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        if (isTv) {
            finish()
            return
        }

        val channelId = intent.getIntExtra(EXTRA_CHANNEL_ID, -1)
        if (channelId < 0) {
            finish()
            return
        }

        ChannelRepository.loadChannels(this)

        setContent {
            MQLTVTheme {
                PortraitLiveGuideScreen(
                    initialChannelId = channelId,
                    onClose = { finish() },
                    onInlineFullscreenChanged = { landscape ->
                        // Keep system UI in sync with fullscreen state without forcing orientation.
                        applyPortraitGuideSystemUi(landscape)
                    },
                )
            }
        }

        applyPortraitGuideSystemUi(fullscreenLandscape = false)
    }

    private fun applyPortraitGuideSystemUi(fullscreenLandscape: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !fullscreenLandscape)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreenLandscape) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
}
