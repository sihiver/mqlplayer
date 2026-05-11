package com.sihiver.mqltv.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Presence ke backend (`POST .../public/presence`).
 *
 * **Catatan:** scaffold **mql_manager** di README hanya mencantumkan publik
 * `POST /public/login`, `GET /public/m3u/{id}.m3u`, `GET /public/users/{appKey}/playlist.m3u`.
 * Endpoint presence bersifat **opsional**; jika server mengembalikan 404, panggilan diabaikan.
 *
 * Flow:
 * 1. User clicks channel → sendOnlinePresence()
 * 2. After playback starts → startHeartbeat() (every 60 seconds)
 * 3. Player closes → sendOfflinePresence()
 *
 * Coroutine: satu [scope] anak [SupervisorJob] + IO; panggil [dispose] saat Activity/Composable
 * selesai agar job dibatalkan (tidak membuat CoroutineScope baru per request).
 */
class PresenceManager(private val context: Context) {
    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 60_000L // 60 seconds
        private const val TAG = "PresenceManager"
    }

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)

    private var heartbeatJob: Job? = null
    private var currentChannelTitle: String? = null
    private var currentChannelUrl: String? = null

    @Volatile
    private var finalized = false

    /**
     * Sends "online" presence when user clicks on a channel to play.
     * Should be called when playback is about to start or channel is selected.
     */
    fun sendOnlinePresence(channelTitle: String, channelUrl: String) {
        if (!supervisorJob.isActive) return
        currentChannelTitle = channelTitle
        currentChannelUrl = channelUrl

        Log.d(TAG, "Sending online presence for channel: $channelTitle")

        sendPresence(
            status = "online",
            channelTitle = channelTitle,
            channelUrl = channelUrl
        )
    }

    /**
     * Starts periodic heartbeat (every 60 seconds).
     * Should be called after playback successfully starts.
     * Call stopHeartbeat() to stop it.
     */
    fun startHeartbeat() {
        if (!supervisorJob.isActive) return
        stopHeartbeat()

        Log.d(TAG, "Starting heartbeat")
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)

                sendPresence(
                    status = "heartbeat",
                    channelTitle = currentChannelTitle,
                    channelUrl = currentChannelUrl
                )
            }
        }
    }

    /**
     * Stops the heartbeat timer.
     */
    fun stopHeartbeat() {
        if (heartbeatJob?.isActive == true) {
            Log.d(TAG, "Stopping heartbeat")
            heartbeatJob?.cancel()
        }
        heartbeatJob = null
    }

    /**
     * Sends "offline" presence when player is closed.
     * Should be called in closePlayerAndFinish() or onDestroy().
     */
    fun sendOfflinePresence() {
        val shouldRun = synchronized(this) {
            if (finalized) {
                false
            } else {
                finalized = true
                true
            }
        }
        if (!shouldRun) return

        stopHeartbeat()
        supervisorJob.cancelChildren()

        Log.d(TAG, "Sending offline presence")

        runBlocking(Dispatchers.IO) {
            try {
                postPresenceSuspend(
                    status = "offline",
                    channelTitle = null,
                    channelUrl = null,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error sending offline presence: ${e.message}", e)
            }
        }

        currentChannelTitle = null
        currentChannelUrl = null
    }

    /**
     * Batalkan semua coroutine presence (heartbeat + kiriman async). Panggil dari [onDispose]/[onDestroy].
     * Jika [sendOfflinePresence] belum pernah dipanggil, kirim offline dulu (best-effort).
     */
    fun dispose() {
        if (!finalized) {
            sendOfflinePresence()
        } else {
            stopHeartbeat()
        }
        supervisorJob.cancel()
    }

    /**
     * Sends presence update to backend.
     * This is the core method that makes the API call.
     */
    private fun sendPresence(
        status: String,
        channelTitle: String?,
        channelUrl: String?,
    ) {
        if (!supervisorJob.isActive) return
        scope.launch {
            try {
                postPresenceSuspend(status, channelTitle, channelUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending presence: ${e.message}", e)
            }
        }
    }

    private suspend fun postPresenceSuspend(
        status: String,
        channelTitle: String?,
        channelUrl: String?,
    ) {
        val appKey = com.sihiver.mqltv.repository.AuthRepository.getAppKey(context).trim()
        if (appKey.isBlank()) {
            Log.w(TAG, "AppKey is empty, cannot send presence")
            return
        }

        val serverBaseUrl = com.sihiver.mqltv.repository.AuthRepository.getServerBaseUrl(context)
            .trim()
            .removeSuffix("/")
        if (serverBaseUrl.isBlank()) {
            Log.w(TAG, "Server base URL is empty, cannot send presence")
            return
        }

        val presenceUrl = "$serverBaseUrl/public/presence"
        Log.d(TAG, "Sending presence to: $presenceUrl (status=$status)")

        val payload = JSONObject().apply {
            put("appKey", appKey)
            put("status", status)
            if (channelTitle != null) put("channelTitle", channelTitle)
            if (channelUrl != null) put("channelUrl", channelUrl)
        }

        val connection = (URL(presenceUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        connection.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        val responseStream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val response = responseStream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (responseCode in 200..299) {
            Log.d(TAG, "Presence sent successfully: status=$status, response=$response")
        } else {
            Log.w(TAG, "Failed to send presence: HTTP $responseCode, response=$response")
        }

        connection.disconnect()
    }
}
