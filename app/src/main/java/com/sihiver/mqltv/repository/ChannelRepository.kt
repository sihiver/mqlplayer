package com.sihiver.mqltv.repository

import android.content.Context
import com.sihiver.mqltv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ChannelRepository {
    
    private var customChannels = mutableListOf<Channel>()
    private var sampleChannels = mutableListOf<Channel>()
    private var nextId = 100
    private var sampleChannelsCleared = false
    
    fun getSampleChannels(): List<Channel> {
        if (sampleChannelsCleared) return emptyList()
        if (sampleChannels.isNotEmpty()) return sampleChannels
        
        sampleChannels.addAll(
        listOf(
            // Local HDMI Capture Stream - Ganti dengan IP dan port server HLS Anda
            Channel(
                id = 1,
                name = "Event",
                url = "http://192.168.18.54:8080/hls/stream.m3u8",
                category = "Local",
                logo = ""
            ),
            // Sample IPTV channels
            Channel(
                id = 2,
                name = "Sintel",
                url = "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8",
                category = "Demo",
                logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/3/34/Sintel_poster.jpg/220px-Sintel_poster.jpg"
            ),
            Channel(
                id = 3,
                name = "Tears of Steel",
                url = "https://bitdash-a.akamaihd.net/content/MI201109210084_1/m3u8s/f08e80da-bf1d-4e3d-8899-f0f6155f6efa.m3u8",
                category = "Demo",
                logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/7/70/Tears_of_Steel_poster.jpg/220px-Tears_of_Steel_poster.jpg"
            ),
            Channel(
                id = 4,
                name = "Elephants Dream",
                url = "https://test-streams.mux.dev/elephants_dream.m3u8",
                category = "Demo",
                logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e8/Elephants_Dream_s5_proog.jpg/220px-Elephants_Dream_s5_proog.jpg"
            ),
        ))
        return sampleChannels
    }
    
    fun getAllChannels(): List<Channel> {
        return getSampleChannels() + customChannels
    }
    
    fun addChannel(channel: Channel) {
        val newChannel = channel.copy(id = nextId++)
        customChannels.add(newChannel)
    }
    
    fun removeChannel(channelId: Int) {
        customChannels.removeAll { it.id == channelId }
    }
    
    fun getChannelById(id: Int): Channel? {
        return getAllChannels().find { it.id == id }
    }
    
    fun getChannelsByCategory(category: String): List<Channel> {
        return getAllChannels().filter { it.category == category }
    }
    
    fun getCategories(): List<String> {
        return getAllChannels().map { it.category }.distinct().sorted()
    }
    
    fun getAllCategories(): List<String> {
        return getCategories().filter { it.isNotEmpty() }
    }
    
    suspend fun importFromM3U(m3uContent: String): Int {
        return withContext(Dispatchers.IO) {
            var count = 0
            val lines = m3uContent.lines()
            var currentName = ""
            var currentLogo = ""
            var currentGroup = "Imported"
            
            for (i in lines.indices) {
                val line = lines[i].trim()
                
                if (line.startsWith("#EXTINF:")) {
                    // Parse EXTINF line
                    currentName = line.substringAfter(",").trim()
                    
                    // Extract tvg-logo
                    if (line.contains("tvg-logo=\"")) {
                        currentLogo = line.substringAfter("tvg-logo=\"")
                            .substringBefore("\"")
                    }
                    
                    // Extract group-title
                    if (line.contains("group-title=\"")) {
                        currentGroup = line.substringAfter("group-title=\"")
                            .substringBefore("\"")
                    }
                } else if (line.isNotEmpty() && !line.startsWith("#")) {
                    // This is a URL line
                    if (currentName.isNotEmpty()) {
                        addChannel(
                            Channel(
                                id = 0, // Will be assigned
                                name = currentName,
                                url = line,
                                logo = currentLogo,
                                category = currentGroup
                            )
                        )
                        count++
                    }
                    // Reset for next channel
                    currentName = ""
                    currentLogo = ""
                    currentGroup = "Imported"
                }
            }
            count
        }
    }
    
    suspend fun importFromM3UUrl(url: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val content = reader.readText()
                reader.close()
                
                importFromM3U(content)
            } catch (e: Exception) {
                e.printStackTrace()
                0
            }
        }
    }
    
    fun saveChannels(context: Context) {
        val prefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        // Save as JSON-like string
        val channelsString = customChannels.joinToString("|") { channel ->
            "${channel.id},${channel.name},${channel.url},${channel.logo},${channel.category}"
        }
        editor.putString("custom_channels", channelsString)
        editor.putInt("next_id", nextId)
        editor.putBoolean("samples_cleared", sampleChannelsCleared)
        editor.apply()
    }
    
    fun loadChannels(context: Context) {
        val prefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)
        val channelsString = prefs.getString("custom_channels", "") ?: ""
        sampleChannelsCleared = prefs.getBoolean("samples_cleared", false)
        nextId = prefs.getInt("next_id", 100)
        
        customChannels.clear()
        if (channelsString.isNotEmpty()) {
            channelsString.split("|").forEach { channelStr ->
                val parts = channelStr.split(",")
                if (parts.size >= 5) {
                    customChannels.add(
                        Channel(
                            id = parts[0].toIntOrNull() ?: 0,
                            name = parts[1],
                            url = parts[2],
                            logo = parts[3],
                            category = parts[4]
                        )
                    )
                }
            }
        }
    }
    
    fun clearAllChannels(context: Context) {
        customChannels.clear()
        sampleChannels.clear()
        sampleChannelsCleared = true
        nextId = 100
        saveChannels(context)
        android.util.Log.d("ChannelRepository", "All channels cleared including samples")
    }
}
