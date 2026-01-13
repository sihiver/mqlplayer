package com.sihiver.mqltv.repository

import android.content.Context
import com.sihiver.mqltv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val _channelsRevision = MutableStateFlow(0)
    val channelsRevision: StateFlow<Int> = _channelsRevision

    private const val PREFS_APP = "mqltv_prefs"
    private const val KEY_PLAYLIST_URL_LEGACY = "playlist_url"
    private const val KEY_PLAYLIST_URLS = "playlist_urls"

    // Default embedded playlist URL (always kept unless user clears samples).
    private const val DEFAULT_SAMPLE_PLAYLIST_URL = "http://192.168.15.10/playlist.m3u"
    
    fun getSampleChannels(): List<Channel> {
        if (sampleChannelsCleared) return emptyList()
        if (sampleChannels.isNotEmpty()) return sampleChannels

        // Sample channels are intentionally empty.
        // The app now uses a default sample *playlist URL* on first run and imports channels from it.
        return emptyList()
    }

    /**
     * Ensures a default playlist URL exists for first-time users.
     *
     * Returns true if the default URL was added, false otherwise.
     */
    fun ensureDefaultPlaylistUrl(context: Context): Boolean {
        if (sampleChannelsCleared) return false

        val existing = getPlaylistUrls(context)
        if (existing.contains(DEFAULT_SAMPLE_PLAYLIST_URL)) return false

        addPlaylistUrl(context, DEFAULT_SAMPLE_PLAYLIST_URL)
        return true
    }
    
    fun getAllChannels(): List<Channel> {
        return getSampleChannels() + customChannels
    }
    
    fun addChannel(channel: Channel) {
        val source = if (channel.source.isBlank()) "manual" else channel.source
        val newChannel = channel.copy(id = nextId++, source = source)
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
    
    suspend fun importFromM3U(m3uContent: String, source: String = "paste"): Int {
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
                    val afterCommaRaw = line.substringAfter(",", "").trim()
                    
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

                    // Some playlists put the media URL on the same line as EXTINF.
                    val httpIndex = when {
                        afterCommaRaw.contains("http://") -> afterCommaRaw.indexOf("http://")
                        afterCommaRaw.contains("https://") -> afterCommaRaw.indexOf("https://")
                        else -> -1
                    }
                    if (httpIndex >= 0) {
                        val namePart = afterCommaRaw.substring(0, httpIndex).trim()
                        val urlPart = afterCommaRaw.substring(httpIndex).trim()
                        val normalizedSource = source.trim()
                        val finalName = namePart.ifBlank { "Imported" }
                        val alreadyExists = customChannels.any { it.url == urlPart && it.source == normalizedSource }
                        if (!alreadyExists) {
                            addChannel(
                                Channel(
                                    id = 0,
                                    name = finalName,
                                    url = urlPart,
                                    logo = currentLogo,
                                    category = currentGroup,
                                    source = normalizedSource
                                )
                            )
                            count++
                        }

                        // Reset for next channel
                        currentName = ""
                        currentLogo = ""
                        currentGroup = "Imported"
                        continue
                    }

                    currentName = afterCommaRaw
                } else if (line.isNotEmpty() && !line.startsWith("#")) {
                    // This is a URL line
                    if (currentName.isNotEmpty()) {
                        val normalizedSource = source.trim()
                        val alreadyExists = customChannels.any { it.url == line && it.source == normalizedSource }
                        if (!alreadyExists) {
                            addChannel(
                                Channel(
                                    id = 0, // Will be assigned
                                    name = currentName,
                                    url = line,
                                    logo = currentLogo,
                                    category = currentGroup,
                                    source = normalizedSource
                                )
                            )
                            count++
                        }
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

                importFromM3U(content, source = url)
            } catch (e: Exception) {
                e.printStackTrace()
                0
            }
        }
    }
    
    suspend fun refreshPlaylistFromServer(context: Context, url: String) {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("ChannelRepository", "Refreshing playlist from server: $url")

                // Fetch new playlist from server
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val status = try {
                    connection.responseCode
                } catch (_: Exception) {
                    -1
                }

                if (status == 401 || status == 403 || status == 404) {
                    android.util.Log.w(
                        "ChannelRepository",
                        "Playlist refresh got HTTP $status (expired/invalid). Marking session expired."
                    )
                    AuthRepository.markExpiredServer(context)
                    return@withContext
                }
                if (status !in 200..299) {
                    android.util.Log.e("ChannelRepository", "Playlist refresh failed: HTTP $status")
                    return@withContext
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val content = reader.readText()
                reader.close()

                // Parse new playlist
                val newChannels = mutableListOf<Channel>()
                val lines = content.lines()
                var currentName = ""
                var currentLogo = ""
                var currentGroup = "Imported"

                for (i in lines.indices) {
                    val line = lines[i].trim()

                    if (line.startsWith("#EXTINF:")) {
                        val extinfParts = line.split(",", limit = 2)
                        if (extinfParts.size > 1) {
                            val afterCommaRaw = extinfParts[1].trim()

                            // Some playlists put the media URL on the same line as EXTINF.
                            val httpIndex = when {
                                afterCommaRaw.contains("http://") -> afterCommaRaw.indexOf("http://")
                                afterCommaRaw.contains("https://") -> afterCommaRaw.indexOf("https://")
                                else -> -1
                            }
                            if (httpIndex >= 0) {
                                val namePart = afterCommaRaw.substring(0, httpIndex).trim()
                                val urlPart = afterCommaRaw.substring(httpIndex).trim()
                                val finalName = namePart.ifBlank { "Imported" }

                                val logoRegex = "tvg-logo=\"([^\"]+)\"".toRegex()
                                val logoMatch = logoRegex.find(line)
                                currentLogo = logoMatch?.groupValues?.get(1) ?: ""

                                val groupRegex = "group-title=\"([^\"]+)\"".toRegex()
                                val groupMatch = groupRegex.find(line)
                                currentGroup = groupMatch?.groupValues?.get(1) ?: "Imported"

                                newChannels.add(
                                    Channel(
                                        id = 0,
                                        name = finalName,
                                        url = urlPart,
                                        category = currentGroup,
                                        logo = currentLogo,
                                        source = url
                                    )
                                )
                                currentName = ""
                                currentLogo = ""
                                currentGroup = "Imported"
                                continue
                            }

                            currentName = afterCommaRaw
                        }

                        val logoRegex = "tvg-logo=\"([^\"]+)\"".toRegex()
                        val logoMatch = logoRegex.find(line)
                        currentLogo = logoMatch?.groupValues?.get(1) ?: ""

                        val groupRegex = "group-title=\"([^\"]+)\"".toRegex()
                        val groupMatch = groupRegex.find(line)
                        currentGroup = groupMatch?.groupValues?.get(1) ?: "Imported"
                    } else if (line.isNotEmpty() && !line.startsWith("#") && currentName.isNotEmpty()) {
                        newChannels.add(
                            Channel(
                                id = 0,
                                name = currentName,
                                url = line,
                                category = currentGroup,
                                logo = currentLogo,
                                source = url
                            )
                        )
                        currentName = ""
                        currentLogo = ""
                    }
                }

                // Compare with existing channels and update
                val existingChannels = customChannels.filter { it.source == url }

                val newByUrl = newChannels.associateBy { it.url }
                val existingByUrl = existingChannels.associateBy { it.url }

                val channelsToRemove = mutableListOf<Channel>()
                val channelsToAdd = mutableListOf<Channel>()
                var updatedCount = 0

                // Remove: exists locally but not on server anymore
                for (existing in existingChannels) {
                    if (!newByUrl.containsKey(existing.url)) {
                        channelsToRemove.add(existing)
                        android.util.Log.d(
                            "ChannelRepository",
                            "Channel removed from server: ${existing.name} (${existing.url})"
                        )
                    }
                }

                // Add: exists on server but not locally
                for (newCh in newChannels) {
                    val alreadyExists = existingByUrl.containsKey(newCh.url)
                    if (!alreadyExists) {
                        channelsToAdd.add(newCh.copy(id = nextId++))
                        android.util.Log.d(
                            "ChannelRepository",
                            "New channel from server: ${newCh.name} (${newCh.url})"
                        )
                    }
                }

                // Update metadata for channels that still exist (name/logo/category/drm)
                // This is the key bugfix so UI updates even when URL membership doesn't change.
                customChannels = customChannels.mapTo(mutableListOf()) { ch ->
                    val server = if (ch.source == url) newByUrl[ch.url] else null
                    if (server == null) {
                        ch
                    } else {
                        val merged = ch.copy(
                            name = server.name,
                            logo = server.logo,
                            category = server.category,
                            // keep existing ID
                            drmLicenseUrl = if (server.drmLicenseUrl.isNotBlank()) server.drmLicenseUrl else ch.drmLicenseUrl
                        )
                        if (merged != ch) updatedCount++
                        merged
                    }
                }

                // Apply add/remove
                if (channelsToRemove.isNotEmpty()) {
                    customChannels.removeAll(channelsToRemove)
                }
                if (channelsToAdd.isNotEmpty()) {
                    customChannels.addAll(channelsToAdd)
                }

                val didChange = channelsToRemove.isNotEmpty() || channelsToAdd.isNotEmpty() || updatedCount > 0
                if (didChange) {
                    saveChannels(context)
                }

                android.util.Log.d(
                    "ChannelRepository",
                    "Playlist refreshed: ${channelsToRemove.size} removed, ${channelsToAdd.size} added, $updatedCount updated (saved=$didChange)"
                )
            } catch (e: Exception) {
                android.util.Log.e("ChannelRepository", "Error refreshing playlist", e)
            }
        }
    }
    
    fun getPlaylistUrls(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
        val currentSet = (prefs.getStringSet(KEY_PLAYLIST_URLS, emptySet()) ?: emptySet()).toMutableSet()

        // Migrate legacy single URL if present
        val legacy = prefs.getString(KEY_PLAYLIST_URL_LEGACY, "")?.trim().orEmpty()
        if (legacy.isNotEmpty() && !currentSet.contains(legacy)) {
            currentSet.add(legacy)
            prefs.edit()
                .remove(KEY_PLAYLIST_URL_LEGACY)
                .putStringSet(KEY_PLAYLIST_URLS, currentSet)
                .apply()
        }

        return currentSet.filter { it.isNotBlank() }.distinct()
    }

    fun addPlaylistUrl(context: Context, url: String) {
        val normalized = url.trim()
        if (normalized.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
        val currentSet = (prefs.getStringSet(KEY_PLAYLIST_URLS, emptySet()) ?: emptySet()).toMutableSet()
        currentSet.add(normalized)
        prefs.edit()
            .remove(KEY_PLAYLIST_URL_LEGACY)
            .putStringSet(KEY_PLAYLIST_URLS, currentSet)
            .apply()
    }

    fun clearPlaylistUrls(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_PLAYLIST_URL_LEGACY)
            .remove(KEY_PLAYLIST_URLS)
            .apply()
    }
    
    fun saveChannels(context: Context) {
        val prefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        // Save as JSON-like string
        val channelsString = customChannels.joinToString("|") { channel ->
            "${channel.id},${channel.name},${channel.url},${channel.logo},${channel.category},${channel.source},${channel.drmLicenseUrl}"
        }
        editor.putString("custom_channels", channelsString)
        editor.putInt("next_id", nextId)
        editor.putBoolean("samples_cleared", sampleChannelsCleared)
        editor.apply()

        _channelsRevision.value = _channelsRevision.value + 1
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
                            category = parts[4],
                            source = if (parts.size >= 6) parts[5] else "",
                            drmLicenseUrl = if (parts.size >= 7) parts[6] else ""
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
        clearPlaylistUrls(context)
        saveChannels(context)
        android.util.Log.d("ChannelRepository", "All channels cleared including samples")
    }
    
    // Recently Watched functionality
    private val recentlyWatchedIds = mutableListOf<Int>()
    
    fun addToRecentlyWatched(context: Context, channelId: Int) {
        // Remove if already exists to move to front
        recentlyWatchedIds.remove(channelId)
        // Add to front
        recentlyWatchedIds.add(0, channelId)
        // Keep only last 10
        if (recentlyWatchedIds.size > 10) {
            recentlyWatchedIds.removeAt(recentlyWatchedIds.size - 1)
        }
        saveRecentlyWatched(context)
        android.util.Log.d("ChannelRepository", "Added channel $channelId to recently watched")
    }
    
    fun getRecentlyWatched(): List<Channel> {
        val allChannels = getAllChannels()
        return recentlyWatchedIds.mapNotNull { id ->
            allChannels.find { it.id == id }
        }
    }
    
    private fun saveRecentlyWatched(context: Context) {
        val prefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString("recently_watched", recentlyWatchedIds.joinToString(","))
        editor.apply()
    }
    
    fun loadRecentlyWatched(context: Context) {
        val prefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)
        val recentString = prefs.getString("recently_watched", "") ?: ""
        recentlyWatchedIds.clear()
        if (recentString.isNotEmpty()) {
            recentlyWatchedIds.addAll(
                recentString.split(",").mapNotNull { it.toIntOrNull() }
            )
        }
    }
    
    // Favorites functionality
    private val favoriteIds = mutableSetOf<Int>()
    
    fun toggleFavorite(context: Context, channelId: Int) {
        if (favoriteIds.contains(channelId)) {
            favoriteIds.remove(channelId)
        } else {
            favoriteIds.add(channelId)
        }
        saveFavorites(context)
        android.util.Log.d("ChannelRepository", "Toggled favorite for channel $channelId")
    }
    
    fun isFavorite(channelId: Int): Boolean {
        return favoriteIds.contains(channelId)
    }
    
    fun getFavorites(): List<Channel> {
        val allChannels = getAllChannels()
        return favoriteIds.mapNotNull { id ->
            allChannels.find { it.id == id }
        }.sortedBy { it.name }
    }
    
    private fun saveFavorites(context: Context) {
        val prefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString("favorites", favoriteIds.joinToString(","))
        editor.apply()
    }
    
    fun loadFavorites(context: Context) {
        val prefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)
        val favString = prefs.getString("favorites", "") ?: ""
        favoriteIds.clear()
        if (favString.isNotEmpty()) {
            favoriteIds.addAll(
                favString.split(",").mapNotNull { it.toIntOrNull() }
            )
        }
    }
}
