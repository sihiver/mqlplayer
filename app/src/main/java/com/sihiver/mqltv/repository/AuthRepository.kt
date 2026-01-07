package com.sihiver.mqltv.repository

import android.content.Context

object AuthRepository {
    private const val PREFS_AUTH = "mqltv_auth"

    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_SERVER_BASE_URL = "server_base_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PLAYLIST_URL = "playlist_url"

    private const val KEY_EXPIRES_AT_RAW = "expires_at_raw"
    private const val KEY_EXPIRES_AT_MILLIS = "expires_at_millis"
    private const val KEY_IS_EXPIRED_SERVER = "is_expired_server"
    private const val KEY_LAST_STATUS_CHECKED_AT = "last_status_checked_at"

    fun isLoggedIn(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LOGGED_IN, false)
    }

    fun getServerBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_BASE_URL, "")?.trim().orEmpty()
    }

    fun getUsername(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USERNAME, "")?.trim().orEmpty()
    }

    fun getPlaylistUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PLAYLIST_URL, "")?.trim().orEmpty()
    }

    fun getExpiresAtRaw(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        return prefs.getString(KEY_EXPIRES_AT_RAW, "")?.trim().orEmpty()
    }

    fun isExpiredNow(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        val serverExpired = prefs.getBoolean(KEY_IS_EXPIRED_SERVER, false)
        if (serverExpired) return true

        val expiresAtMillis = prefs.getLong(KEY_EXPIRES_AT_MILLIS, 0L)
        return expiresAtMillis > 0L && System.currentTimeMillis() >= expiresAtMillis
    }

    fun saveSession(
        context: Context,
        serverBaseUrl: String,
        username: String,
        playlistUrl: String,
        expiresAtRaw: String = "",
        expiresAtMillis: Long = 0L,
        isExpiredServer: Boolean = false,
    ) {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_SERVER_BASE_URL, serverBaseUrl.trim())
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PLAYLIST_URL, playlistUrl.trim())
            .putString(KEY_EXPIRES_AT_RAW, expiresAtRaw.trim())
            .putLong(KEY_EXPIRES_AT_MILLIS, expiresAtMillis)
            .putBoolean(KEY_IS_EXPIRED_SERVER, isExpiredServer)
            .putLong(KEY_LAST_STATUS_CHECKED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
