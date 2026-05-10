package com.sihiver.mqltv.playback

import android.content.Context
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.repository.ChannelRepository
import com.sihiver.mqltv.service.PresenceManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/** Bangun ExoPlayer untuk stream Live (inline halaman potret). Caller wajib [ExoPlayer.release]. */
@UnstableApi
object LiveExoPlayerFactory {

    fun createMediaItem(channel: Channel): MediaItem {
        val streamUrl = channel.url.trim()
        val licenseUrl = channel.drmLicenseUrl.trim()
        val isDashMpd = streamUrl.endsWith(".mpd", ignoreCase = true)
        if (licenseUrl.isBlank() || !isDashMpd) {
            return MediaItem.fromUri(streamUrl)
        }
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build()
    }

    fun createDrmSessionManager(context: Context, licenseUrl: String): DrmSessionManager {
        val parts = licenseUrl.split("|")
        val actualLicenseUrl = parts[0].trim()
        val customHeaders = mutableMapOf<String, String>()
        if (parts.size > 1) {
            parts.drop(1).forEach { header ->
                val keyValue = header.split("=", limit = 2)
                if (keyValue.size == 2) {
                    customHeaders[keyValue[0].trim()] = keyValue[1].trim()
                }
            }
        }
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)

        val drmCallback = androidx.media3.exoplayer.drm.HttpMediaDrmCallback(
            actualLicenseUrl,
            httpDataSourceFactory
        )
        if (customHeaders.isNotEmpty()) {
            customHeaders.forEach { (key, value) ->
                drmCallback.setKeyRequestProperty(key, value)
            }
        }
        return androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(drmCallback)
    }

    fun createExoPlayer(
        context: Context,
        channel: Channel,
        presenceManager: PresenceManager,
        accelerationSetting: String,
    ): ExoPlayer {
        ChannelRepository.loadChannels(context)

        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context)
        val renderersFactory = NextRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            val extensionMode = when (accelerationSetting) {
                "SW (Software)" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                "HW (Hardware)", "HW+ (Hardware+)" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            }
            setExtensionRendererMode(extensionMode)
        }

        val drmSessionManager = if (!channel.drmLicenseUrl.isBlank()) {
            createDrmSessionManager(context, channel.drmLicenseUrl)
        } else {
            null
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
            .apply {
                if (drmSessionManager != null) {
                    setDrmSessionManagerProvider { drmSessionManager }
                }
            }

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl()
        val trackSelector = DefaultTrackSelector(context)

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

        val mediaItem = createMediaItem(channel)
        exo.setMediaItem(mediaItem)
        exo.prepare()
        return exo
    }
}
