package com.sihiver.mqltv.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Skala tampilan video: konten SD / non-lebar (4:3) di layar 16:9 dengan "Fit" default
 * menimbulkan letterbox atas-bawah. Untuk pengaturan "Fit", SD otomatis memakai zoom/fill layar.
 */
object VideoDisplayHelper {

    private const val WIDESCREEN_MIN_RATIO = 1.55f
    private const val SD_MAX_HEIGHT = 576
    private const val SD_MAX_WIDTH = 1280

    fun isSdOrNonWidescreenContent(
        width: Int,
        height: Int,
        pixelAspectRatio: Float = 1f,
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        val par = pixelAspectRatio.takeIf { it > 0f } ?: 1f
        val displayAspect = (width * par) / height.toFloat()
        return height <= SD_MAX_HEIGHT ||
            (width <= SD_MAX_WIDTH && height <= SD_MAX_HEIGHT) ||
            displayAspect < WIDESCREEN_MIN_RATIO
    }

    /** Untuk VLC: "Fit" + SD → perlakuan seperti Fill (isi layar TV). */
    fun effectiveAspectRatioSetting(
        userSetting: String,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
        pixelAspectRatio: Float = 1f,
    ): String {
        if (userSetting != "Fit") return userSetting
        return if (isSdOrNonWidescreenContent(videoWidth, videoHeight, pixelAspectRatio)) {
            "Fill"
        } else {
            "Fit"
        }
    }

    @UnstableApi
    fun exoResizeMode(
        userSetting: String,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
        pixelAspectRatio: Float = 1f,
    ): Int {
        val effective = effectiveAspectRatioSetting(
            userSetting,
            videoWidth,
            videoHeight,
            pixelAspectRatio,
        )
        return when (effective) {
            "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            "Zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "16:9" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            "4:3" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            else -> {
                if (videoWidth > 0 && videoHeight > 0 &&
                    isSdOrNonWidescreenContent(videoWidth, videoHeight, pixelAspectRatio)
                ) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        }
    }
}
