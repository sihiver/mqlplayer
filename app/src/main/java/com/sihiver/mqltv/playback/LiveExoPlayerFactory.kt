package com.sihiver.mqltv.playback

import android.content.Context
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.repository.ChannelRepository
import com.sihiver.mqltv.service.PresenceManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/** Bangun ExoPlayer untuk stream Live (inline halaman potret). Caller wajib [ExoPlayer.release]. */
@UnstableApi
object LiveExoPlayerFactory {

    fun createExoPlayer(
        context: Context,
        channel: Channel,
        presenceManager: PresenceManager,
        accelerationSetting: String,
    ): ExoPlayer {
        ChannelRepository.loadChannels(context)

        val playbackChannel = DrmPlaybackHelper.resolveChannelForPlayback(channel)
        val effectiveDrm = DrmPlaybackHelper.sanitizeForPlayback(playbackChannel.drmLicenseUrl)
        val httpFactory = DrmPlaybackHelper.createHttpDataSourceFactory(effectiveDrm)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val renderersFactory = NextRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            val extensionMode = when (accelerationSetting) {
                "SW (Software)" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                "HW (Hardware)", "HW+ (Hardware+)" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            }
            setExtensionRendererMode(extensionMode)
        }

        val drmSessionManager = DrmPlaybackHelper.createDrmSessionManager(effectiveDrm)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
            .apply {
                if (drmSessionManager != null) {
                    setDrmSessionManagerProvider { drmSessionManager }
                }
            }

        // Buffer yang dioptimasi untuk live IPTV.
        // Default ExoPlayer (minBuffer=50s, maxBuffer=50s) terlalu besar untuk live stream.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs            */ 15_000,
                /* maxBufferMs            */ 30_000,
                /* bufferForPlaybackMs    */ 1_000,
                /* bufferForPlaybackAfterRebufferMs */ 2_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTunnelingEnabled(true)
                    .build()
            )
        }

        val exo = ExoPlayer.Builder(context)
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
                            Player.STATE_READY -> presenceManager.startHeartbeat()
                            Player.STATE_ENDED -> presenceManager.stopHeartbeat()
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        presenceManager.stopHeartbeat()
                        if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                            val hasHttp403 = generateSequence<Throwable>(error) { it.cause }
                                .any { it is HttpDataSource.InvalidResponseCodeException && it.responseCode == 403 }
                            if (hasHttp403) {
                                Toast.makeText(
                                    context,
                                    "Akses ditolak atau akun kedaluwarsa.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return
                            }
                        }
                        Toast.makeText(
                            context,
                            "Stream error: ${channel.name}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
            }

        try {
            presenceManager.sendOnlinePresence(channel.name, channel.url)
        } catch (_: Exception) {
        }

        val mediaItem = DrmPlaybackHelper.createMediaItem(playbackChannel)
        exo.setMediaItem(mediaItem)
        exo.prepare()
        return exo
    }
}
