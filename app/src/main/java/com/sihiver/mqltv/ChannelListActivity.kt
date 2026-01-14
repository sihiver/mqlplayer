package com.sihiver.mqltv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.repository.ChannelRepository
import com.sihiver.mqltv.ui.player.PlayerChannelListNavState
import com.sihiver.mqltv.ui.player.PlayerChannelListOverlay
import com.sihiver.mqltv.ui.player.handlePlayerChannelListKeyEvent

class ChannelListActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CURRENT_CHANNEL_ID = "EXTRA_CURRENT_CHANNEL_ID"
        const val EXTRA_SELECTED_CHANNEL_ID = "EXTRA_SELECTED_CHANNEL_ID"

        fun createIntent(context: android.content.Context, currentChannelId: Int): Intent {
            return Intent(context, ChannelListActivity::class.java).apply {
                putExtra(EXTRA_CURRENT_CHANNEL_ID, currentChannelId)
            }
        }
    }

    private var currentChannelId: Int = -1

    private val showChannelList = mutableStateOf(true)
    private val headerSelected = mutableStateOf(false)
    private val categoryNavDirection = mutableStateOf(0)
    private val selectedCategory = mutableStateOf("all")
    private val selectedListIndex = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep playback underneath visible and avoid transition flicker.
        window.setWindowAnimations(0)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        } catch (_: Exception) {
            // Ignore
        }

        ChannelRepository.loadChannels(this)

        currentChannelId = intent.getIntExtra(EXTRA_CURRENT_CHANNEL_ID, -1)
        val allChannels = ChannelRepository.getAllChannels()
        val currentIndex = allChannels.indexOfFirst { it.id == currentChannelId }
        selectedListIndex.value = if (currentIndex >= 0) currentIndex else 0

        val root = ComposeView(this).apply {
            setContent {
                val visible by showChannelList

                LaunchedEffect(visible) {
                    if (!visible) {
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                }

                PlayerChannelListOverlay(
                    nav = PlayerChannelListNavState(
                        showChannelList = showChannelList,
                        headerSelected = headerSelected,
                        categoryNavDirection = categoryNavDirection,
                        selectedCategory = selectedCategory,
                        selectedListIndex = selectedListIndex,
                    ),
                    currentChannelId = currentChannelId,
                    onPlayChannel = { ch ->
                        returnResult(ch)
                    },
                )
            }
        }

        setContentView(root)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val consumed = handlePlayerChannelListKeyEvent(
            event = event,
            currentChannelId = currentChannelId,
            nav = PlayerChannelListNavState(
                showChannelList = showChannelList,
                headerSelected = headerSelected,
                categoryNavDirection = categoryNavDirection,
                selectedCategory = selectedCategory,
                selectedListIndex = selectedListIndex,
            ),
            onPlayChannel = { ch -> returnResult(ch) },
        )
        return if (consumed) true else super.dispatchKeyEvent(event)
    }

    private fun returnResult(channel: Channel) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_SELECTED_CHANNEL_ID, channel.id),
        )
        finish()
    }
}
