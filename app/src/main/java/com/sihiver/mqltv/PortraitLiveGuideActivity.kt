package com.sihiver.mqltv

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sihiver.mqltv.repository.ChannelRepository
import com.sihiver.mqltv.ui.live.PortraitLiveGuideScreen
import com.sihiver.mqltv.ui.theme.MQLTVTheme

/**
 * Halaman potret (video + panduan) setelah user memilih channel di grid Live — hanya untuk perangkat non-TV.
 */
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

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT

        ChannelRepository.loadChannels(this)

        setContent {
            MQLTVTheme {
                PortraitLiveGuideScreen(
                    initialChannelId = channelId,
                    onClose = { finish() },
                    onInlineFullscreenChanged = { landscape ->
                        requestedOrientation = if (landscape) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                        }
                    },
                )
            }
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
