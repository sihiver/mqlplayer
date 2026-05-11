package com.sihiver.mqltv.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

object AuthRepository {
    private const val DEFAULT_PUBLIC_SERVER_BASE_URL = "http://iptv.mqlspot.my.id:8088"

    private const val PREFS_AUTH = "mqltv_auth"
    private const val PREFS_SECURE = "mqltv_secure"

    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_SERVER_BASE_URL = "server_base_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PLAYLIST_URL = "playlist_url"
    /** >0 = pakai GET /public/m3u/{id}.m3u (mql_manager). -1 = tidak dipakai. */
    private const val KEY_PLAYLIST_ID = "playlist_id"
    private const val KEY_APP_KEY = "app_key"

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
        val raw = prefs.getString(KEY_SERVER_BASE_URL, "")?.trim().orEmpty()
        val preferred = if (isLikelyLocalDevUrl(raw)) "" else raw
        return normalizeServerBaseUrl(preferred)
    }

    private fun isLikelyLocalDevUrl(rawInput: String): Boolean {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return false

        val withoutScheme = trimmed.substringAfter("://", trimmed)
        val hostPort = withoutScheme.substringBefore('/').trim()
        val host = hostPort.substringBefore(':').trim().lowercase(Locale.US)

        if (host == "localhost" || host == "127.0.0.1") return true
        if (host.startsWith("192.168.")) return true
        if (host.startsWith("10.")) return true
        if (host.startsWith("172.")) return true

        return false
    }

    fun normalizeServerBaseUrl(rawInput: String): String {
        val trimmed = rawInput.trim()
        val base = trimmed.ifBlank { DEFAULT_PUBLIC_SERVER_BASE_URL }

        val withScheme = if (base.contains("://")) {
            base
        } else {
            // If user stored just a host (e.g. iptv.mqlspot.my.id:8088), prefer HTTP by default.
            "http://$base"
        }

        return withScheme.removeSuffix("/")
    }

    fun getUsername(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USERNAME, "")?.trim().orEmpty()
    }

    fun getPlaylistUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PLAYLIST_URL, "")?.trim().orEmpty()
    }

    fun getPlaylistId(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_PLAYLIST_ID, -1L)
    }

    /**
     * URL unduhan M3U publik sesuai **mql_manager**:
     * - Jika [getPlaylistId] > 0: `GET /public/m3u/{playlistId}.m3u`
     * - Jika tidak: `GET /public/users/{appKey}/playlist.m3u`
     * - Fallback: [getPlaylistUrl]
     */
    fun getResolvedPlaylistUrl(context: Context): String {
        val base = getServerBaseUrl(context).trim()
        val pid = getPlaylistId(context)
        if (pid > 0L && base.isNotBlank()) {
            return buildPublicM3uByPlaylistId(base, pid)
        }
        val key = getAppKey(context).trim()
        return if (key.isNotBlank() && base.isNotBlank()) {
            buildPublicPlaylistM3uUrl(base, key)
        } else {
            getPlaylistUrl(context)
        }
    }

    fun getAppKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        return prefs.getString(KEY_APP_KEY, "")?.trim().orEmpty()
    }

    /** `GET /public/users/{appKey}/playlist.m3u` — mql_manager public API. */
    fun buildPublicPlaylistM3uUrl(serverBaseUrl: String, appKey: String): String {
        val base = normalizeServerBaseUrl(serverBaseUrl).trim().removeSuffix("/")
        val key = appKey.trim()
        return "$base/public/users/$key/playlist.m3u"
    }

    /** `GET /public/m3u/{playlistId}.m3u` — mql_manager public API. */
    fun buildPublicM3uByPlaylistId(serverBaseUrl: String, playlistId: Long): String {
        val base = normalizeServerBaseUrl(serverBaseUrl).trim().removeSuffix("/")
        return "$base/public/m3u/$playlistId.m3u"
    }

    /** Baca `playlistId` dari body JSON login (akar atau di dalam `user`). */
    fun parsePlaylistIdFromLoginJson(json: JSONObject, user: JSONObject?): Long {
        var id = json.optLong("playlistId", -1L)
        if (id <= 0L) id = json.optLong("playlist_id", -1L)
        if (id <= 0L && user != null) {
            id = user.optLong("playlistId", -1L)
            if (id <= 0L) id = user.optLong("playlist_id", -1L)
        }
        return id
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
        val playlistUrl = getResolvedPlaylistUrl(context)
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
     * Probe akun (throttled).
     *
     * Dokumentasi mql_manager hanya mencantumkan publik:
     * `POST /public/login`, `GET /public/m3u/{id}.m3u`, `GET /public/users/{appKey}/playlist.m3u`.
     * Endpoint `GET /public/users/{appKey}/status` bersifat opsional: jika **404**, tidak dianggap expired
     * dan akan lanjut ke **POST /public/login**.
     */
    suspend fun probeExpiredFromLoginIfNeeded(context: Context, minIntervalMs: Long = 5 * 60 * 1000L): Boolean {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_LOGIN_PROBE_AT, 0L)
        val now = System.currentTimeMillis()
        if (now - last < minIntervalMs) return false

        prefs.edit().putLong(KEY_LAST_LOGIN_PROBE_AT, now).apply()

        // Prefer status endpoint if possible (no password needed)
        val appKey = getAppKey(context)
        if (appKey.isNotBlank()) {
            val byStatus = probeExpiredFromPublicStatus(context, appKey)
            if (byStatus != null) return byStatus
        }

        return probeExpiredFromPublicLogin(context)
    }

    private suspend fun probeExpiredFromPublicStatus(context: Context, appKey: String): Boolean? {
        val serverBaseUrl = getServerBaseUrl(context).trim().ifBlank { return null }.removeSuffix("/")

        return withContext(Dispatchers.IO) {
            fun runProbe(baseUrl: String): Boolean? {
                val url = URL("$baseUrl/public/users/${appKey.trim()}/status")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                }

                val status = try {
                    connection.responseCode
                } catch (_: Exception) {
                    -1
                }

                val body = try {
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                } catch (_: Exception) {
                    ""
                }

                if (status !in 200..299) {
                    if (status == 401 || status == 403) {
                        android.util.Log.w("AuthRepository", "Status probe HTTP $status — sesi ditandai expired.")
                        markExpiredServer(context)
                        return true
                    }
                    if (status == 404) {
                        android.util.Log.d(
                            "AuthRepository",
                            "GET /public/users/.../status tidak ada (404) — lewati, pakai /public/login.",
                        )
                        return null
                    }
                    android.util.Log.d("AuthRepository", "Status probe HTTP $status — lewati.")
                    return null
                }

                val json = try {
                    if (body.isNotBlank()) JSONObject(body) else JSONObject()
                } catch (_: Exception) {
                    JSONObject()
                }

                val user = json.optJSONObject("user")
                val expiresAtRaw = user?.optString("expiresAt", "")?.trim().orEmpty()
                val expiresAtMillis = parseExpiresAtMillis(expiresAtRaw)
                val isExpired = expiresAtMillis > 0L && System.currentTimeMillis() >= expiresAtMillis

                val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_EXPIRES_AT_RAW, expiresAtRaw)
                    .putLong(KEY_EXPIRES_AT_MILLIS, expiresAtMillis)
                    .putBoolean(KEY_IS_EXPIRED_SERVER, isExpired)
                    .putLong(KEY_LAST_STATUS_CHECKED_AT, System.currentTimeMillis())
                    .apply()

                if (isExpired) {
                    android.util.Log.w("AuthRepository", "Status probe indicates expired for appKey=$appKey")
                } else {
                    android.util.Log.d("AuthRepository", "Status probe OK for appKey=$appKey")
                }

                return isExpired
            }

            try {
                runProbe(serverBaseUrl)
            } catch (e: Exception) {
                // One-time fallback for environments where HTTPS is blocked but HTTP works.
                if (serverBaseUrl.startsWith("https://")) {
                    val httpBaseUrl = "http://" + serverBaseUrl.removePrefix("https://")
                    try {
                        val result = runProbe(httpBaseUrl)
                        context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_SERVER_BASE_URL, httpBaseUrl)
                            .apply()
                        result
                    } catch (_: Exception) {
                        android.util.Log.w("AuthRepository", "Status probe failed: ${e.message}")
                        null
                    }
                } else {
                    android.util.Log.w("AuthRepository", "Status probe failed: ${e.message}")
                    null
                }
            }
        }
    }

    private suspend fun probeExpiredFromPublicLogin(context: Context): Boolean {
        val serverBaseUrl = getServerBaseUrl(context).trim().ifBlank { return false }.removeSuffix("/")
        val username = getUsername(context).trim().ifBlank { return false }
        val password = getPassword(context).trim().ifBlank { return false }

        return withContext(Dispatchers.IO) {
            fun runLogin(baseUrl: String): Boolean {
                val url = URL("$baseUrl/public/login")
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

                // /public/login returns: { ok: true, user: {...}, publicPlaylistUrl: "/public/users/{appKey}/playlist.m3u" }
                val user = json.optJSONObject("user")
                val expiresAtRaw = user?.optString("expiresAt", "")?.trim().orEmpty()
                val expiresAtMillis = parseExpiresAtMillis(expiresAtRaw)
                val isExpired = expiresAtMillis > 0L && System.currentTimeMillis() >= expiresAtMillis

                val appKey = user?.optString("appKey", "")?.trim().orEmpty()
                if (appKey.isNotBlank()) {
                    context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_APP_KEY, appKey)
                        .apply()
                }

                val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_EXPIRES_AT_RAW, expiresAtRaw)
                    .putLong(KEY_EXPIRES_AT_MILLIS, expiresAtMillis)
                    .putBoolean(KEY_IS_EXPIRED_SERVER, isExpired)
                    .putLong(KEY_LAST_STATUS_CHECKED_AT, System.currentTimeMillis())
                    .apply()

                if (isExpired) {
                    android.util.Log.w("AuthRepository", "Public login probe indicates expired. status=$status user=$username")
                } else {
                    android.util.Log.d("AuthRepository", "Public login probe OK. status=$status user=$username")
                }

                return isExpired
            }

            try {
                runLogin(serverBaseUrl)
            } catch (e: Exception) {
                if (serverBaseUrl.startsWith("https://")) {
                    val httpBaseUrl = "http://" + serverBaseUrl.removePrefix("https://")
                    try {
                        val result = runLogin(httpBaseUrl)
                        context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_SERVER_BASE_URL, httpBaseUrl)
                            .apply()
                        result
                    } catch (_: Exception) {
                        android.util.Log.w("AuthRepository", "Public login probe failed: ${e.message}")
                        false
                    }
                } else {
                    android.util.Log.w("AuthRepository", "Public login probe failed: ${e.message}")
                    false
                }
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

    /**
     * Memanggil ulang **POST /public/login** (kredensial tersimpan) agar [KEY_PLAYLIST_URL] dan
     * **publicPlaylistUrl** selaras dengan server sebelum unduh .m3u.
     * Tanpa ini, klien hanya GET ke URL lama — jika API mengubah path/token playlist, daftar channel tidak pernah terbarui.
     */
    suspend fun syncPlaylistUrlFromLoginApi(context: Context): Boolean {
        if (!isLoggedIn(context)) return false
        val username = getUsername(context).trim()
        val password = getPassword(context).trim()
        if (username.isBlank() || password.isBlank()) {
            android.util.Log.w(
                "AuthRepository",
                "syncPlaylistUrlFromLoginApi: butuh username + password tersimpan (login ulang jika perlu).",
            )
            return false
        }

        return withContext(Dispatchers.IO) {
            fun doLogin(baseUrl: String): Pair<Int, JSONObject> {
                val url = URL("$baseUrl/public/login")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
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
                val status = connection.responseCode
                val body = try {
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    BufferedReader(InputStreamReader(stream)).use { it.readText() }
                } catch (_: Exception) {
                    ""
                }
                val json = try {
                    if (body.isNotBlank()) JSONObject(body) else JSONObject()
                } catch (_: Exception) {
                    JSONObject()
                }
                return status to json
            }

            var usedBase = getServerBaseUrl(context).trim().removeSuffix("/")
            if (usedBase.isBlank()) return@withContext false

            val (status, json) = try {
                doLogin(usedBase)
            } catch (e: Exception) {
                if (usedBase.startsWith("https://")) {
                    val httpBase = "http://" + usedBase.removePrefix("https://")
                    context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_SERVER_BASE_URL, httpBase)
                        .apply()
                    usedBase = httpBase
                    try {
                        doLogin(usedBase)
                    } catch (e2: Exception) {
                        android.util.Log.e("AuthRepository", "syncPlaylistUrlFromLoginApi login failed", e2)
                        return@withContext false
                    }
                } else {
                    android.util.Log.e("AuthRepository", "syncPlaylistUrlFromLoginApi login failed", e)
                    return@withContext false
                }
            }

            if (status !in 200..299) {
                android.util.Log.w("AuthRepository", "syncPlaylistUrlFromLoginApi HTTP $status")
                return@withContext false
            }
            if (!json.optBoolean("ok", false)) {
                android.util.Log.w("AuthRepository", "syncPlaylistUrlFromLoginApi ok=false")
                return@withContext false
            }

            val user = json.optJSONObject("user")
                ?: run {
                    android.util.Log.w("AuthRepository", "syncPlaylistUrlFromLoginApi: user kosong")
                    return@withContext false
                }

            val appKey = user.optString("appKey", "").trim()
            if (appKey.isBlank()) {
                android.util.Log.w("AuthRepository", "syncPlaylistUrlFromLoginApi: appKey kosong")
                return@withContext false
            }

            val playlistPath = json.optString("publicPlaylistUrl", "").trim()
            if (playlistPath.isNotBlank()) {
                val expectedSegment = "/public/users/${appKey}/"
                val candidate = if (playlistPath.startsWith("http://") || playlistPath.startsWith("https://")) {
                    try {
                        URL(playlistPath).path
                    } catch (_: Exception) {
                        playlistPath
                    }
                } else {
                    playlistPath
                }
                if (candidate.contains("/public/users/") && !candidate.contains(expectedSegment)) {
                    android.util.Log.w(
                        "AuthRepository",
                        "syncPlaylistUrlFromLoginApi: publicPlaylistUrl tidak cocok appKey, pakai URL kanonik.",
                    )
                }
            }

            val expiresAtRaw = user.optString("expiresAt", "").trim()
            val expiresAtMillis = parseExpiresAtMillis(expiresAtRaw)
            val isExpired = expiresAtMillis > 0L && System.currentTimeMillis() >= expiresAtMillis

            val playlistId = parsePlaylistIdFromLoginJson(json, user)
            val fullPlaylistUrl = if (playlistId > 0L) {
                buildPublicM3uByPlaylistId(usedBase, playlistId)
            } else {
                buildPublicPlaylistM3uUrl(usedBase, appKey)
            }

            saveSession(
                context = context,
                serverBaseUrl = usedBase,
                username = username,
                playlistUrl = fullPlaylistUrl,
                appKey = appKey,
                expiresAtRaw = expiresAtRaw,
                expiresAtMillis = expiresAtMillis,
                isExpiredServer = isExpired,
                playlistId = playlistId,
            )

            ChannelRepository.addPlaylistUrl(context, fullPlaylistUrl)

            android.util.Log.d(
                "AuthRepository",
                "Playlist URL disegarkan dari API /public/login: $fullPlaylistUrl",
            )
            true
        }
    }

    fun saveSession(
        context: Context,
        serverBaseUrl: String,
        username: String,
        playlistUrl: String,
        appKey: String = "",
        expiresAtRaw: String = "",
        expiresAtMillis: Long = 0L,
        isExpiredServer: Boolean = false,
        /** >0: unduh dari `GET /public/m3u/{id}.m3u` (mql_manager). ≤0: pakai appKey playlist. */
        playlistId: Long = -1L,
    ) {
        val normalizedServerBaseUrl = normalizeServerBaseUrl(serverBaseUrl)
        val normalizedRaw = expiresAtRaw.trim()
        val normalizedMillis = if (expiresAtMillis > 0L) {
            expiresAtMillis
        } else if (normalizedRaw.isNotBlank()) {
            parseExpiresAtMillis(normalizedRaw)
        } else {
            0L
        }

        val keyTrim = appKey.trim()
        val pid = playlistId.coerceAtLeast(-1L)
        val playlistToStore = when {
            pid > 0L -> buildPublicM3uByPlaylistId(normalizedServerBaseUrl, pid)
            keyTrim.isNotBlank() -> buildPublicPlaylistM3uUrl(normalizedServerBaseUrl, keyTrim)
            else -> playlistUrl.trim()
        }

        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_SERVER_BASE_URL, normalizedServerBaseUrl)
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PLAYLIST_URL, playlistToStore)
            .putString(KEY_APP_KEY, keyTrim)
            .putLong(KEY_PLAYLIST_ID, if (pid > 0L) pid else -1L)
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
            .remove(KEY_PLAYLIST_ID)
            .remove(KEY_APP_KEY)
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
