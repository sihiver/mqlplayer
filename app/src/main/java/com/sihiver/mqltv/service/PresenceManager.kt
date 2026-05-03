package com.sihiver.mqltv.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages user presence (online/offline/heartbeat) to the backend server.
 * 
 * Flow:
 * 1. User clicks channel → sendOnlinePresence()
 * 2. After playback starts → startHeartbeat() (every 60 seconds)
 * 3. Player closes → sendOfflinePresence()
 */
class PresenceManager(private val context: Context) {
    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 60_000L // 60 seconds
        private const val TAG = "PresenceManager"
    }

    private var heartbeatJob: Job? = null
    private var currentChannelTitle: String? = null
    private var currentChannelUrl: String? = null

    /**
     * Sends "online" presence when user clicks on a channel to play.
     * Should be called when playback is about to start or channel is selected.
     */
    fun sendOnlinePresence(channelTitle: String, channelUrl: String) {
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
        stopHeartbeat() // Cancel any existing heartbeat
        
        Log.d(TAG, "Starting heartbeat")
        
        heartbeatJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                
                // Send heartbeat with current channel info
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
        stopHeartbeat()
        
        Log.d(TAG, "Sending offline presence")
        
        sendPresence(
            status = "offline",
            channelTitle = null,
            channelUrl = null
        )
        
        currentChannelTitle = null
        currentChannelUrl = null
    }

    /**
     * Sends presence update to backend.
     * This is the core method that makes the API call.
     */
    private fun sendPresence(
        status: String,
        channelTitle: String?,
        channelUrl: String?
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get appKey and server URL from auth
                val appKey = com.sihiver.mqltv.repository.AuthRepository.getAppKey(context).trim()
                if (appKey.isBlank()) {
                    Log.w(TAG, "AppKey is empty, cannot send presence")
                    return@launch
                }
                
                val serverBaseUrl = com.sihiver.mqltv.repository.AuthRepository.getServerBaseUrl(context)
                    .trim()
                    .removeSuffix("/")
                if (serverBaseUrl.isBlank()) {
                    Log.w(TAG, "Server base URL is empty, cannot send presence")
                    return@launch
                }

                val presenceUrl = "$serverBaseUrl/public/presence"
                Log.d(TAG, "Sending presence to: $presenceUrl (status=$status)")

                // Build JSON payload
                val payload = JSONObject().apply {
                    put("appKey", appKey)
                    put("status", status)
                    if (channelTitle != null) put("channelTitle", channelTitle)
                    if (channelUrl != null) put("channelUrl", channelUrl)
                }

                // Make HTTP POST request
                val connection = (URL(presenceUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }

                // Send payload
                connection.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                // Get response
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

            } catch (e: Exception) {
                Log.e(TAG, "Error sending presence: ${e.message}", e)
            }
        }
    }
}
