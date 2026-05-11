package com.sihiver.mqltv

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * Orientasi pemutar: TV tetap landscape sensor; ponsel/tablet mengikuti pengaturan
 * Settings (prefs `video_settings` → `orientation`), sama opsi seperti [MainActivity].
 */
fun resolvePlayerRequestedOrientation(context: Context): Int {
    val isTv =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
    if (isTv) {
        return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    val prefs = context.getSharedPreferences("video_settings", Context.MODE_PRIVATE)
    val pref = prefs.getString("orientation", "Sensor Landscape")?.trim().orEmpty()
        .ifBlank { "Sensor Landscape" }

    return when (pref) {
        "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        "Sensor Landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        "Auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}
