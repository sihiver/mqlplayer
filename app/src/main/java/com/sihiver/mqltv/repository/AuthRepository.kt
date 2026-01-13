package com.sihiver.mqltv.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

object AuthRepository {
    private const val PREFS_AUTH = "mqltv_auth"
    private const val PREFS_SECURE = "mqltv_secure"

    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_SERVER_BASE_URL = "server_base_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PLAYLIST_URL = "playlist_url"

    private const val KEY_EXPIRES_AT_RAW = "expires_at_raw"
    private const val KEY_EXPIRES_AT_MILLIS = "expires_at_millis"
    private const val KEY_IS_EXPIRED_SERVER = "is_expired_server"
    private const val KEY_LAST_STATUS_CHECKED_AT = "last_status_checked_at"

    private const val KEY_PASSWORD = "password"
    private const val KEY_LAST_LOGIN_PROBE_AT = "last_login_probe_at"

    private fun securePrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_SECURE,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun savePassword(context: Context, password: String) {
        val trimmed = password.trim()
        if (trimmed.isBlank()) return
        securePrefs(context).edit().putString(KEY_PASSWORD, trimmed).apply()
    }

    fun getPassword(context: Context): String {
        return try {
            securePrefs(context).getString(KEY_PASSWORD, "")?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

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
        if (serverExpired) {
            android.util.Log.d("AuthRepository", "Expired: server flag true")
            return true
        }

        var expiresAtMillis = prefs.getLong(KEY_EXPIRES_AT_MILLIS, 0L)
        if (expiresAtMillis <= 0L) {
            val raw = prefs.getString(KEY_EXPIRES_AT_RAW, "")?.trim().orEmpty()
            if (raw.isNotBlank()) {
                expiresAtMillis = parseExpiresAtMillis(raw)
                if (expiresAtMillis > 0L) {
                    android.util.Log.d(
                        "AuthRepository",
                        "Recomputed expiresAtMillis from raw: raw='$raw', millis=$expiresAtMillis"
                    )
                    prefs.edit().putLong(KEY_EXPIRES_AT_MILLIS, expiresAtMillis).apply()
                }
            }
        }

        val expired = expiresAtMillis > 0L && System.currentTimeMillis() >= expiresAtMillis
        if (expired) {
            android.util.Log.d(
                "AuthRepository",
                "Expired by time: now=${System.currentTimeMillis()} >= expiresAtMillis=$expiresAtMillis"
            )
        }
        return expired
    }

    fun markExpiredServer(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_IS_EXPIRED_SERVER, true)
            .putLong(KEY_LAST_STATUS_CHECKED_AT, System.currentTimeMillis())
            .apply()
    }

    fun markNotExpiredServer(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_IS_EXPIRED_SERVER, false)
            .putLong(KEY_LAST_STATUS_CHECKED_AT, System.currentTimeMillis())
            .apply()
    }

    suspend fun probeExpiredFromPlaylistUrl(context: Context): Boolean {
        val playlistUrl = getPlaylistUrl(context)
        if (playlistUrl.isBlank()) return false

        return withContext(Dispatchers.IO) {
            try {
                val connection = (URL(playlistUrl).openConnection() as HttpURLConnection).apply {
                    // Some servers don't support HEAD well; GET with small timeouts is safer.
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                val status = try {
                    connection.responseCode
                } catch (_: Exception) {
                    -1
                }

                if (status in 200..299) {
                    // If user is renewed server-side, playlist may become accessible again.
                    // Clear the cached server-expired flag so app can recover without re-login.
                    markNotExpiredServer(context)
                    android.util.Log.d("AuthRepository", "Probe playlist OK (HTTP $status). Cleared expired flag.")
                    return@withContext false
                }

                if (status == 401 || status == 403 || status == 404) {
                    android.util.Log.w(
                        "AuthRepository",
                        "Probe playlist got HTTP $status. Marking session expired. url='$playlistUrl'"
                    )
                    markExpiredServer(context)
                    return@withContext true
                }

                android.util.Log.d("AuthRepository", "Probe playlist HTTP $status")
                false
            } catch (e: Exception) {
                android.util.Log.w("AuthRepository", "Probe playlist failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Probe account status by calling /api/user/login with stored credentials.
     * This is more sensitive than playlist probing, so it is throttled.
     */
    suspend fun probeExpiredFromLoginIfNeeded(context: Context, minIntervalMs: Long = 5 * 60 * 1000L): Boolean {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_LOGIN_PROBE_AT, 0L)
        val now = System.currentTimeMillis()
        if (now - last < minIntervalMs) return false

        prefs.edit().putLong(KEY_LAST_LOGIN_PROBE_AT, now).apply()
        return probeExpiredFromLogin(context)
    }

    private suspend fun probeExpiredFromLogin(context: Context): Boolean {
        val serverBaseUrl = getServerBaseUrl(context).trim().ifBlank { return false }.removeSuffix("/")
        val username = getUsername(context).trim().ifBlank { return false }
        val password = getPassword(context).trim().ifBlank { return false }

        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverBaseUrl/api/user/login")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }

                val payload = JSONObject()
                    .put("username", username)
                    .put("password", password)
                    .toString()

                connection.outputStream.use { os ->
                    os.write(payload.toByteArray(Charsets.UTF_8))
                }

                val status = try { connection.responseCode } catch (_: Exception) { -1 }
                val body = try {
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                } catch (_: Exception) {
                    ""
                }

                val json = try {
                    if (body.isNotBlank()) JSONObject(body) else JSONObject()
                } catch (_: Exception) {
                    JSONObject()
                }

                val data = json.optJSONObject("data")
                val expiresAtRaw = data?.optString("expires_at", "")?.trim().orEmpty()
                val expiresAtMillis = parseExpiresAtMillis(expiresAtRaw)
                val isExpiredServer = data?.optBoolean("is_expired", false) ?: false
                val daysRemaining = data?.optInt("days_remaining", 0) ?: 0
                val looksExpired =
                    status == 403 ||
                        isExpiredServer ||
                        daysRemaining <= 0 ||
                        json.optString("message", "").contains("expired", ignoreCase = true)

                if (looksExpired) {
                    android.util.Log.w("AuthRepository", "Login probe indicates expired. status=$status user=$username")
                    // Keep the most recent expiry info if provided.
                    val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(KEY_EXPIRES_AT_RAW, expiresAtRaw)
                        .putLong(KEY_EXPIRES_AT_MILLIS, expiresAtMillis)
                        .putBoolean(KEY_IS_EXPIRED_SERVER, true)
                        .putLong(KEY_LAST_STATUS_CHECKED_AT, System.currentTimeMillis())
                        .apply()
                    return@withContext true
                }

                if (status in 200..299) {
                    // Account is valid again; clear expired flag and refresh expiry info.
                    val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(KEY_EXPIRES_AT_RAW, expiresAtRaw)
                        .putLong(KEY_EXPIRES_AT_MILLIS, expiresAtMillis)
                        .putBoolean(KEY_IS_EXPIRED_SERVER, false)
                        .putLong(KEY_LAST_STATUS_CHECKED_AT, System.currentTimeMillis())
                        .apply()
                    android.util.Log.d("AuthRepository", "Login probe OK. status=$status user=$username")
                }

                false
            } catch (e: Exception) {
                android.util.Log.w("AuthRepository", "Login probe failed: ${e.message}")
                false
            }
        }
    }

    fun parseExpiresAtMillis(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return 0L

        val normalized = normalizeToMillisIso(trimmed)

        // Prefer the most standard form first.
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", // Z or +07:00
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",   // Z or +0700 or +07
        )

        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(normalized)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }

        return 0L
    }

    private fun normalizeToMillisIso(input: String): String {
        val raw = input.trim()
        if (raw.isBlank()) return ""

        // Split datetime and timezone safely (avoid '-' in date part)
        val tIndex = raw.indexOf('T')
        val endsWithZ = raw.endsWith('Z')

        val zoneStartIndex = when {
            endsWithZ -> raw.length - 1
            else -> {
                val plus = raw.lastIndexOf('+')
                val minus = raw.lastIndexOf('-')
                val candidate = max(plus, minus)
                if (candidate > tIndex) candidate else -1
            }
        }

        val dateTimePart: String
        val zonePartRaw: String

        if (zoneStartIndex >= 0) {
            if (endsWithZ) {
                dateTimePart = raw.substring(0, zoneStartIndex)
                zonePartRaw = "Z"
            } else {
                dateTimePart = raw.substring(0, zoneStartIndex)
                zonePartRaw = raw.substring(zoneStartIndex)
            }
        } else {
            dateTimePart = raw
            zonePartRaw = "+00:00"
        }

        val zonePart = when {
            zonePartRaw == "Z" -> "Z"
            // +07:00 already OK
            zonePartRaw.length == 6 && (zonePartRaw[0] == '+' || zonePartRaw[0] == '-') && zonePartRaw[3] == ':' -> zonePartRaw
            // +0700 -> +07:00
            zonePartRaw.length == 5 && (zonePartRaw[0] == '+' || zonePartRaw[0] == '-') ->
                zonePartRaw.substring(0, 3) + ":" + zonePartRaw.substring(3)
            // +07 -> +07:00
            zonePartRaw.length == 3 && (zonePartRaw[0] == '+' || zonePartRaw[0] == '-') ->
                zonePartRaw + ":00"
            else -> zonePartRaw
        }

        // Normalize fractional seconds to exactly 3 digits
        val dotIndex = dateTimePart.indexOf('.')
        val dateTimeWithMillis = if (dotIndex == -1) {
            // no fractional seconds
            "$dateTimePart.000"
        } else {
            val base = dateTimePart.substring(0, dotIndex)
            val fraction = dateTimePart.substring(dotIndex + 1)
            val millis = (fraction + "000").take(3)
            "$base.$millis"
        }

        return dateTimeWithMillis + zonePart
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
        val normalizedRaw = expiresAtRaw.trim()
        val normalizedMillis = if (expiresAtMillis > 0L) {
            expiresAtMillis
        } else if (normalizedRaw.isNotBlank()) {
            parseExpiresAtMillis(normalizedRaw)
        } else {
            0L
        }

        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_SERVER_BASE_URL, serverBaseUrl.trim())
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PLAYLIST_URL, playlistUrl.trim())
            .putString(KEY_EXPIRES_AT_RAW, normalizedRaw)
            .putLong(KEY_EXPIRES_AT_MILLIS, normalizedMillis)
            .putBoolean(KEY_IS_EXPIRED_SERVER, isExpiredServer)
            .putLong(KEY_LAST_STATUS_CHECKED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Logs out by clearing session/expiry state, while keeping remembered credentials
     * (username/server base URL and encrypted password) so user doesn't need to retype.
     */
    fun logout(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, false)
            .remove(KEY_PLAYLIST_URL)
            .remove(KEY_EXPIRES_AT_RAW)
            .remove(KEY_EXPIRES_AT_MILLIS)
            .remove(KEY_IS_EXPIRED_SERVER)
            .remove(KEY_LAST_STATUS_CHECKED_AT)
            .remove(KEY_LAST_LOGIN_PROBE_AT)
            .apply()
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        try {
            securePrefs(context).edit().clear().apply()
        } catch (_: Exception) {
        }
    }
}
