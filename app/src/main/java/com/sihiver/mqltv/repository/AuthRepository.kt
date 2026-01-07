package com.sihiver.mqltv.repository

import android.content.Context

object AuthRepository {
    private const val PREFS_AUTH = "mqltv_auth"

    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_SERVER_BASE_URL = "server_base_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PLAYLIST_URL = "playlist_url"

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

    fun saveSession(
        context: Context,
        serverBaseUrl: String,
        username: String,
        playlistUrl: String,
    ) {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_SERVER_BASE_URL, serverBaseUrl.trim())
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PLAYLIST_URL, playlistUrl.trim())
            .apply()
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
