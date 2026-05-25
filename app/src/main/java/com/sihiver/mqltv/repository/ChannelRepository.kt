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
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import java.util.HashSet
import java.util.Locale

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
    private const val KEY_PENDING_LIVE_GRID_CATEGORY_TAB = "pending_live_grid_category_tab"
    private const val KEY_LAST_LIVE_GRID_TAB_FOR_PLAYER = "last_live_grid_tab_for_player"

    // Default embedded playlist URL (always kept unless user clears samples).
    private const val DEFAULT_SAMPLE_PLAYLIST_URL = "http://192.168.15.1:5140/playlist.m3u"

    /**
     * Tag sumber stabil untuk channel yang diimpor dari playlist login (bukan URL penuh).
     * Mencegah channel lama “nyangkut” bila URL ter-resolve berubah (mis. `/public/users/...`
     * vs `/public/m3u/{id}.m3u`) sehingga merge/hapus tidak lagi memakai `source == normalizedUrl`.
     */
    private const val SOURCE_ACCOUNT_PLAYLIST = "__mql_account_playlist__"

    private fun isSamePlaylistHost(context: Context, channelSource: String): Boolean {
        val base = AuthRepository.getResolvedPlaylistUrl(context).trim()
        if (base.isEmpty()) return false
        return try {
            val u1 = URL(base)
            val u2 = URL(channelSource.trim())
            u1.host.equals(u2.host, ignoreCase = true) && u1.protocol == u2.protocol
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True jika channel ini berasal dari impor playlist akun (tag stabil, URL tersimpan,
     * atau varian URL backend yang sama sehingga harus diganti saat server mengubah isi playlist).
     */
    private fun belongsToAccountPlaylistImport(
        context: Context,
        channelSource: String,
        normalizedUrl: String,
    ): Boolean {
        val cs = channelSource.trim()
        val nu = normalizedUrl.trim()
        val res = AuthRepository.getResolvedPlaylistUrl(context).trim()
        val raw = AuthRepository.getPlaylistUrl(context).trim()
        if (cs == SOURCE_ACCOUNT_PLAYLIST) return true
        if (cs == nu || cs == raw || cs == res) return true
        if (!isSamePlaylistHost(context, cs)) return false
        if (cs.contains("/public/users/") && cs.contains("playlist.m3u")) return true
        val accountUsesNumericM3u = res.contains("/public/m3u/") || nu.contains("/public/m3u/")
        if (accountUsesNumericM3u && cs.contains("/public/m3u/") && cs.endsWith(".m3u", ignoreCase = true)) {
            return true
        }
        return false
    }

    private fun playlistCacheKey(url: String, suffix: String): String {
        // Short, stable key derived from URL to avoid very long preference keys.
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val short = digest.take(12).joinToString("") { b -> "%02x".format(b) }
        return "playlist_${suffix}_$short"
    }

    private fun sha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }
    
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
    
    // Return all channels with deduplication by URL.
    // Preference: customChannels (user-added/imported) override sampleChannels when URLs collide.
    fun getAllChannels(): List<Channel> {
        val result = mutableListOf<Channel>()
        val seenUrls = mutableSetOf<String>()

        // Add custom/user channels first (they take precedence)
        for (ch in customChannels) {
            if (seenUrls.add(ch.url)) {
                result.add(ch)
            }
        }

        // Then add sample channels only if URL not already present
        for (ch in getSampleChannels()) {
            if (seenUrls.add(ch.url)) {
                result.add(ch)
            }
        }

        return result
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

    /** Urutan tab Live: Local & Sports dulu, lalu alfabet (tanpa movie). */
    private fun prioritizeLiveCategoryTabOrder(categories: List<String>): List<String> {
        val priorityLabels = listOf("Local", "Sports")
        val usedLower = mutableSetOf<String>()
        val prioritized = mutableListOf<String>()
        for (label in priorityLabels) {
            val match = categories.firstOrNull { it.equals(label, ignoreCase = true) }
            if (match != null) {
                val key = match.lowercase(Locale.getDefault())
                if (key !in usedLower) {
                    usedLower.add(key)
                    prioritized.add(match)
                }
            }
        }
        val rest = categories
            .filter { it.lowercase(Locale.getDefault()) !in usedLower }
            .sortedBy { it.lowercase(Locale.getDefault()) }
        return prioritized + rest
    }

    /** Daftar key tab Live (ALL_CHANNELS + kategori), sama dengan LiveChannelsScreen. */
    fun getLiveScreenCategoryTabKeys(): List<String> {
        val all = getAllCategories()
            .filterNot { it.contains("movie", ignoreCase = true) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
        return listOf("ALL_CHANNELS") + prioritizeLiveCategoryTabOrder(all)
    }

    /** Tab Live yang sesuai kategori channel (ALL_CHANNELS jika tidak ada match). */
    fun resolveLiveScreenCategoryTabForChannel(channel: Channel): String {
        val tabs = getLiveScreenCategoryTabKeys()
        val c = channel.category.trim()
        if (c.isEmpty()) return "ALL_CHANNELS"
        val match = tabs.find { it != "ALL_CHANNELS" && it.equals(c, ignoreCase = true) }
        return match ?: "ALL_CHANNELS"
    }

    /** Grid All Channels: Local → Sports → sisanya (selaras overlay pemutar). */
    fun sortLiveChannelsLocalSportsFirst(channels: List<Channel>): List<Channel> {
        fun catEq(ch: Channel, name: String) = ch.category.trim().equals(name, ignoreCase = true)
        val local = channels.filter { catEq(it, "Local") }
        val sports = channels.filter { catEq(it, "Sports") }
        val takenIds = (local + sports).map { it.id }.toSet()
        val rest = channels.filter { it.id !in takenIds }
        return local + sports + rest
    }

    /** Dipanggil dari ChannelListActivity saat user memilih channel — sinkron tab Live. */
    fun setPendingLiveGridCategoryTab(context: Context, categoryTabKey: String) {
        context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING_LIVE_GRID_CATEGORY_TAB, categoryTabKey).apply()
    }

    fun peekPendingLiveGridCategoryTab(context: Context): String? {
        return context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_LIVE_GRID_CATEGORY_TAB, null)
    }

    fun clearPendingLiveGridCategoryTab(context: Context) {
        context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .edit().remove(KEY_PENDING_LIVE_GRID_CATEGORY_TAB).apply()
    }

    /** Tab Live yang aktif saat user tap channel mem-buka player — dipakai ChannelListActivity overlay. */
    fun setLastLiveGridTabWhenOpeningPlayer(context: Context, liveScreenSelectedCategory: String) {
        context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_LIVE_GRID_TAB_FOR_PLAYER, liveScreenSelectedCategory).apply()
    }

    /** Tab Live terakhir saat buka player (tidak dihapus — dipakai zapping/nomor channel). */
    fun peekLastLiveGridTabWhenOpeningPlayer(context: Context): String? {
        return context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .getString(KEY_LAST_LIVE_GRID_TAB_FOR_PLAYER, null)
    }

    /**
     * Urutan channel untuk input nomor & CH± di player — sama dengan grid Live untuk tab terakhir
     * (dari grid atau setelah pilih channel dari overlay).
     */
    fun getChannelsOrderedForActiveLiveTab(context: Context): List<Channel> {
        val all = getAllChannels()
        val tab = peekLastLiveGridTabWhenOpeningPlayer(context)?.trim().orEmpty()
        if (tab.isEmpty() || tab.equals("ALL_CHANNELS", ignoreCase = true)) {
            return sortLiveChannelsLocalSportsFirst(all)
        }
        return all.filter { it.category.trim().equals(tab, ignoreCase = true) }
    }

    /** Keys overlay ChannelListActivity (sama urutan/kategori dengan PlayerChannelListOverlay). */
    fun getChannelListOverlayCategoryKeys(): List<String> {
        val allChannels = getAllChannels()
        val categories = allChannels
            .map { it.category.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .filterNot { it.contains("movie", ignoreCase = true) }
        val ordered = prioritizeLiveCategoryTabOrder(categories)
        return buildList {
            add("all")
            add("favorites")
            add("recent")
            addAll(ordered)
        }
    }

    fun getFilteredChannelsForOverlayCategoryKey(selectedKey: String): List<Channel> {
        val allChannels = getAllChannels()
        val favorites = getFavorites()
        return when (selectedKey) {
            "all" -> sortLiveChannelsLocalSportsFirst(allChannels)
            "favorites" -> favorites
            "recent" -> allChannels.take(10)
            else -> allChannels.filter { ch ->
                ch.category.trim().equals(selectedKey, ignoreCase = true)
            }
        }
    }

    /**
     * Tab kategori overlay saat dibuka: pakai tab grid Live yang disimpan saat buka player;
     * fallback ke group-title channel jika tidak ada.
     */
    /**
     * Tab grid Live yang harus dipakai player (CH±, nomor, buka ulang overlay) setelah user
     * memilih channel dari overlay — **jangan** ganti ke group-title channel bila tab overlay masih "Semua Channel".
     */
    fun liveGridTabKeyAfterOverlayChannelPick(overlayCategoryKey: String, channel: Channel): String {
        return when (overlayCategoryKey.trim()) {
            "all", "favorites", "recent" -> "ALL_CHANNELS"
            else -> {
                val keys = getLiveScreenCategoryTabKeys()
                keys.find {
                    it.equals(overlayCategoryKey, ignoreCase = true) && it != "ALL_CHANNELS"
                } ?: resolveLiveScreenCategoryTabForChannel(channel)
            }
        }
    }

    fun resolveInitialChannelListOverlayCategory(context: Context, channelId: Int): String {
        val keys = getChannelListOverlayCategoryKeys()
        val lastLive = peekLastLiveGridTabWhenOpeningPlayer(context)?.trim().orEmpty()

        if (lastLive.isNotEmpty()) {
            if (lastLive.equals("ALL_CHANNELS", ignoreCase = true)) {
                return "all"
            }
            val fromGrid = keys.find {
                it != "all" && it != "favorites" && it != "recent" &&
                    it.equals(lastLive, ignoreCase = true)
            }
            if (fromGrid != null) return fromGrid
        }

        val ch = getChannelById(channelId) ?: return "all"
        val cat = ch.category.trim()
        if (cat.isEmpty()) return "all"
        return keys.find {
            it != "all" && it != "favorites" && it != "recent" && it.equals(cat, ignoreCase = true)
        } ?: "all"
    }

    fun getInitialChannelListOverlayListIndex(channelId: Int, overlayCategoryKey: String): Int {
        val filtered = getFilteredChannelsForOverlayCategoryKey(overlayCategoryKey)
        val idx = filtered.indexOfFirst { it.id == channelId }
        return if (idx >= 0) idx else 0
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

    /**
     * Impor playlist JSON format v216 (mis. `http://iptv.mqlspot.my.id/v216.json`):
     * `{ "country_name", "country", "info": [ { "name", "hls", "logo", "country_name", "url_license", ... } ] }`
     */
    suspend fun importFromV216Json(jsonContent: String, source: String = "paste"): Int {
        return withContext(Dispatchers.IO) {
            importFromV216JsonBlocking(jsonContent, source)
        }
    }

    suspend fun importFromV216JsonUrl(url: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                val connection = (URL(url.trim()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val content = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (status !in 200..299 || content.isBlank()) {
                    android.util.Log.e("ChannelRepository", "importFromV216JsonUrl HTTP $status")
                    return@withContext 0
                }
                importFromV216JsonBlocking(content, url.trim())
            } catch (e: Exception) {
                android.util.Log.e("ChannelRepository", "importFromV216JsonUrl failed", e)
                0
            }
        }
    }

    private fun importFromV216JsonBlocking(
        jsonContent: String,
        source: String,
        context: Context? = null,
    ): Int {
        val trimmed = jsonContent.trim()
        if (trimmed.isEmpty()) return 0

        val root = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            android.util.Log.e("ChannelRepository", "Invalid v216 JSON", e)
            return 0
        }

        val info = root.optJSONArray("info") ?: run {
            android.util.Log.w("ChannelRepository", "v216 JSON: missing \"info\" array")
            return 0
        }

        return syncV216JsonChannelsBlocking(info, source, context)
    }

    /**
     * Sinkron penuh playlist v216: tambah / ubah / hapus channel yang tidak ada lagi di JSON.
     */
    /** Tanpa query window `?start=&end=` agar iNews dll. tetap cocok saat refresh JSON. */
    private fun streamUrlIdentity(url: String): String {
        val trimmed = url.trim()
        return if (com.sihiver.mqltv.playback.DrmPlaybackHelper.isDashMpdUrl(trimmed)) {
            trimmed.substringBefore('#').substringBefore('?')
        } else {
            trimmed
        }
    }

    private fun syncV216JsonChannelsBlocking(
        info: JSONArray,
        source: String,
        context: Context? = null,
    ): Int {
        val normalizedSource = source.trim().ifBlank { "v216-json" }
        val mergeIntoAccount = context != null && isAccountPlaylistJsonSource(context, normalizedSource)
        val accountPlaylistUrl = context?.let { AuthRepository.getResolvedPlaylistUrl(it).trim() }.orEmpty()

        val channelSourceTag = if (mergeIntoAccount) SOURCE_ACCOUNT_PLAYLIST else normalizedSource
        val incoming = parseV216JsonChannels(info, channelSourceTag)
        if (incoming.isEmpty()) return 0

        val incomingByIdentity = incoming.associateBy { streamUrlIdentity(it.url) }
        var changed = 0

        val beforeSize = customChannels.size
        customChannels.removeAll { ch ->
            ch.source == normalizedSource &&
                !incomingByIdentity.containsKey(streamUrlIdentity(ch.url))
        }
        val removedCount = beforeSize - customChannels.size
        if (removedCount > 0) changed += removedCount

        for (incomingCh in incoming) {
            val identity = streamUrlIdentity(incomingCh.url)
            var existingIndex = customChannels.indexOfFirst {
                it.source == channelSourceTag && streamUrlIdentity(it.url) == identity
            }
            // Channel dari M3U akun punya source __mql_account_playlist__ — merge DRM dari JSON
            if (existingIndex < 0 && mergeIntoAccount && context != null) {
                existingIndex = customChannels.indexOfFirst { ch ->
                    streamUrlIdentity(ch.url) == identity &&
                        belongsToAccountPlaylistImport(context, ch.source, accountPlaylistUrl)
                }
            }
            if (existingIndex >= 0) {
                val existing = customChannels[existingIndex]
                val updated = existing.copy(
                    url = incomingCh.url,
                    name = incomingCh.name,
                    category = incomingCh.category,
                    logo = incomingCh.logo.ifBlank { existing.logo },
                    drmLicenseUrl = incomingCh.drmLicenseUrl,
                    source = if (mergeIntoAccount) SOURCE_ACCOUNT_PLAYLIST else existing.source,
                )
                if (updated != existing) {
                    customChannels[existingIndex] = updated
                    changed++
                    if (incomingCh.drmLicenseUrl.isNotBlank()) {
                        android.util.Log.d(
                            "ChannelRepository",
                            "DRM merged for '${existing.name}': ${incomingCh.drmLicenseUrl.take(60)}...",
                        )
                    }
                }
            } else {
                addChannel(incomingCh)
                changed++
            }
        }

        // Hapus duplikat lama dengan source URL JSON jika sudah di-merge ke account playlist
        if (mergeIntoAccount) {
            val beforeDup = customChannels.size
            customChannels.removeAll { ch ->
                ch.source == normalizedSource &&
                    incomingByIdentity.containsKey(streamUrlIdentity(ch.url))
            }
            changed += beforeDup - customChannels.size
        }

        android.util.Log.d(
            "ChannelRepository",
            "v216 JSON sync: $changed change(s) (source=$normalizedSource, mergeAccount=$mergeIntoAccount, total=${incoming.size})",
        )
        return changed
    }

    private fun isAccountPlaylistJsonSource(context: Context, jsonSource: String): Boolean {
        val derived = deriveV216JsonUrl(context)?.trim().orEmpty()
        if (derived.isBlank()) return false
        return jsonSource.trim().equals(derived, ignoreCase = true)
    }

    private fun parseV216JsonChannels(info: JSONArray, normalizedSource: String): List<Channel> {
        val result = mutableListOf<Channel>()
        for (i in 0 until info.length()) {
            val item = info.optJSONObject(i) ?: continue
            val streamUrl = item.optString("hls", "").trim()
            if (streamUrl.isBlank()) continue

            result.add(
                com.sihiver.mqltv.playback.DrmPlaybackHelper.applyV216ImportOverrides(
                    Channel(
                        id = 0,
                        name = item.optString("name", "").trim().ifBlank { "Imported" },
                        url = streamUrl,
                        logo = resolveV216JsonLogo(item),
                        category = resolveV216JsonCategory(item),
                        source = normalizedSource,
                        drmLicenseUrl = com.sihiver.mqltv.playback.DrmPlaybackHelper.resolveV216JsonDrmLicenseUrl(item),
                    ),
                ),
            )
        }
        return result
    }

    /**
     * Semua URL yang harus di-refresh (M3U akun, prefs, dan sumber JSON dari channel lama).
     */
    fun collectPlaylistRefreshUrls(context: Context): List<String> {
        loadChannels(context)
        val authUrl = AuthRepository.getResolvedPlaylistUrl(context).trim()
        val loggedIn = AuthRepository.isLoggedIn(context)
        val skipSampleWhileAccount = loggedIn && authUrl.isNotBlank()

        val ordered = LinkedHashSet<String>()
        if (authUrl.isNotBlank()) {
            ordered.add(authUrl)
        }
        getPlaylistUrls(context).forEach { u ->
            val t = u.trim()
            if (t.isEmpty()) return@forEach
            if (skipSampleWhileAccount && t == DEFAULT_SAMPLE_PLAYLIST_URL) return@forEach
            ordered.add(t)
        }
        customChannels
            .map { it.source.trim() }
            .filter { isV216JsonSource(it) }
            .forEach { jsonSource ->
                ordered.add(jsonSource)
                addPlaylistUrl(context, jsonSource)
            }
        // Playlist JSON akun (DRM) — derive dari URL M3U login
        deriveV216JsonUrl(context)?.let { jsonUrl ->
            if (!ordered.contains(jsonUrl)) {
                ordered.add(jsonUrl)
                addPlaylistUrl(context, jsonUrl)
            }
        }
        return ordered.toList()
    }

    /**
     * Perbaiki channel yang masih menyimpan URL Widevine Verspective (bug impor lama)
     * dengan mengambil ulang ClearKey dari JSON v216.
     */
    suspend fun repairChannelDrmIfStaleVerspective(context: Context, channelId: Int): Boolean {
        return withContext(Dispatchers.IO) {
            repairChannelDrmIfStaleVerspectiveBlocking(context, channelId)
        }
    }

    fun repairChannelDrmIfStaleVerspectiveBlocking(context: Context, channelId: Int): Boolean {
        val channel = getChannelById(channelId) ?: return false
        if (!com.sihiver.mqltv.playback.DrmPlaybackHelper.needsVerspectiveDrmRepair(channel.drmLicenseUrl)) {
            return false
        }

        val jsonSources = buildList {
            channel.source.trim().takeIf { isV216JsonSource(it) }?.let { add(it) }
            addAll(getPlaylistUrls(context).filter { isV216JsonSource(it) })
            add("http://iptv.mqlspot.my.id/v216.json")
        }.distinct()

        android.util.Log.d(
            "ChannelRepository",
            "Repairing stale Verspective DRM for '${channel.name}' from ${jsonSources.size} JSON source(s)",
        )

        for (src in jsonSources) {
            try {
                val content = fetchTextUrl(src) ?: continue
                val info = JSONObject(content.trim()).optJSONArray("info") ?: continue
                var fixed = false
                for (i in 0 until info.length()) {
                    val item = info.optJSONObject(i) ?: continue
                    val streamUrl = item.optString("hls", "").trim()
                    if (streamUrl.isBlank()) continue
                    val correctDrm = com.sihiver.mqltv.playback.DrmPlaybackHelper.resolveV216JsonDrmLicenseUrl(item)
                    val logo = resolveV216JsonLogo(item)
                    val idx = customChannels.indexOfFirst { it.url == streamUrl }
                    if (idx < 0) continue
                    val existing = customChannels[idx]
                    if (!com.sihiver.mqltv.playback.DrmPlaybackHelper.needsVerspectiveDrmRepair(existing.drmLicenseUrl)) {
                        continue
                    }
                    customChannels[idx] = existing.copy(drmLicenseUrl = correctDrm, logo = logo)
                    fixed = true
                    android.util.Log.d(
                        "ChannelRepository",
                        "Repaired DRM for '${existing.name}' -> ClearKey",
                    )
                }
                if (fixed) {
                    saveChannels(context)
                    val updated = getChannelById(channelId)
                    return updated?.drmLicenseUrl?.contains("clearkey:", ignoreCase = true) == true
                }
            } catch (e: Exception) {
                android.util.Log.w("ChannelRepository", "DRM repair failed for $src", e)
            }
        }
        return false
    }

    private fun isV216JsonSource(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("http") && (u.endsWith(".json", ignoreCase = true) || u.contains("v216", ignoreCase = true))
    }

    private fun fetchTextUrl(url: String): String? {
        val connection = (URL(url.trim()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val content = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status in 200..299 && content.isNotBlank()) content else null
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveV216JsonLogo(item: JSONObject): String {
        val logo = item.optString("logo", "").trim()
        if (logo.isBlank() || logo.equals("none", ignoreCase = true)) {
            return ""
        }
        if (logo.startsWith("http://", ignoreCase = true) ||
            logo.startsWith("https://", ignoreCase = true)
        ) {
            return logo
        }
        return ""
    }

    private fun resolveV216JsonCategory(item: JSONObject): String {
        val countryName = item.optString("country_name", "").trim()
        if (countryName.isNotBlank() && !countryName.equals("none", ignoreCase = true)) {
            return countryName
        }
        val namespace = item.optString("namespace", "").trim()
        if (namespace.isNotBlank() && !namespace.equals("none", ignoreCase = true)) {
            return namespace
        }
        val alpha = item.optString("alpha_2_code", "").trim()
        if (alpha.isNotBlank() && !alpha.equals("none", ignoreCase = true)) {
            return alpha
        }
        return "Imported"
    }

    
    /**
     * Unduh ulang playlist dari URL.
     * @param forceFullFetch true (mis. tombol Refresh manual): jangan kirim If-None-Match / If-Modified-Since,
     *   supaya selalu dapat body terbaru — banyak server/CDN yang mengembalikan 304 atau metadata salah sehingga daftar tidak sinkron.
     *   Untuk auto-refresh periodik: gunakan false agar hemat bandwidth.
     */
    suspend fun refreshPlaylistFromServer(
        context: Context,
        url: String,
        forceFullFetch: Boolean = false,
    ) {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = url.trim()
                android.util.Log.d(
                    "ChannelRepository",
                    "Refreshing playlist from server: $normalizedUrl (forceFullFetch=$forceFullFetch)",
                )

                val authUrl = AuthRepository.getResolvedPlaylistUrl(context).trim()
                val isAccountPlaylist = authUrl.isNotBlank() && normalizedUrl == authUrl

                val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
                val etagKey = playlistCacheKey(normalizedUrl, "etag")
                val lastModifiedKey = playlistCacheKey(normalizedUrl, "lastmod")
                val hashKey = playlistCacheKey(normalizedUrl, "sha")

                val previousEtag = prefs.getString(etagKey, "")?.trim().orEmpty()
                val previousLastModified = prefs.getLong(lastModifiedKey, 0L)
                val previousHash = prefs.getString(hashKey, "")?.trim().orEmpty()

                // Fetch new playlist from server
                val connection = URL(normalizedUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (forceFullFetch) {
                    connection.setRequestProperty("Cache-Control", "no-cache")
                    connection.setRequestProperty("Pragma", "no-cache")
                }

                // Change-detection headers (best effort). Skip saat refresh manual — hindari 304 palsu / konten tidak sinkron.
                if (!forceFullFetch) {
                    if (previousEtag.isNotEmpty()) {
                        connection.setRequestProperty("If-None-Match", previousEtag)
                    }
                    if (previousLastModified > 0L) {
                        connection.ifModifiedSince = previousLastModified
                    }
                }

                val status = try {
                    connection.responseCode
                } catch (_: Exception) {
                    -1
                }

                if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    android.util.Log.d("ChannelRepository", "Playlist not modified (304): $normalizedUrl")
                    return@withContext
                }

                if (status == 401 || status == 403 || status == 404) {
                    android.util.Log.w(
                        "ChannelRepository",
                        "Playlist refresh got HTTP $status (expired/invalid)."
                    )
                    // Only mark the account session expired when the *account* playlist fails.
                    // The default sample/simple playlist can be unreachable in production and must not block the account.
                    if (isAccountPlaylist) {
                        android.util.Log.w("ChannelRepository", "Account playlist failed (HTTP $status). Marking expired.")
                        AuthRepository.markExpiredServer(context)
                    }
                    return@withContext
                }
                if (status !in 200..299) {
                    android.util.Log.e("ChannelRepository", "Playlist refresh failed: HTTP $status")
                    return@withContext
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val content = reader.readText()
                reader.close()

                // Persist cache headers (best effort)
                val newEtag = connection.getHeaderField("ETag")?.trim().orEmpty()
                val newLastModified = try {
                    connection.lastModified
                } catch (_: Exception) {
                    0L
                }

                val trimmedContent = content.trim()
                if (isV216JsonSource(normalizedUrl) || trimmedContent.startsWith("{")) {
                    val count = importFromV216JsonBlocking(trimmedContent, normalizedUrl, context)
                    saveChannels(context)
                    prefs.edit().apply {
                        putString(hashKey, sha256Hex(trimmedContent))
                        if (newEtag.isNotEmpty()) putString(etagKey, newEtag)
                        if (newLastModified > 0L) putLong(lastModifiedKey, newLastModified)
                        apply()
                    }
                    _channelsRevision.value = _channelsRevision.value + 1
                    if (count > 0) {
                        afterPlaylistDataChanged(context)
                    }
                    android.util.Log.d(
                        "ChannelRepository",
                        "v216 JSON playlist refreshed: $count change(s) ($normalizedUrl)",
                    )
                    return@withContext
                }

                val newHash = sha256Hex(content)
                if (previousHash.isNotEmpty() && newHash == previousHash) {
                    // Content identical: no need to re-parse or touch channels.
                    if (newEtag.isNotEmpty() || newLastModified > 0L) {
                        prefs.edit().apply {
                            if (newEtag.isNotEmpty()) putString(etagKey, newEtag)
                            if (newLastModified > 0L) putLong(lastModifiedKey, newLastModified)
                            apply()
                        }
                    }
                    android.util.Log.d("ChannelRepository", "Playlist content unchanged (hash): $normalizedUrl")
                    return@withContext
                }

                // Parse new playlist
                val channelSourceTag =
                    if (isAccountPlaylist) SOURCE_ACCOUNT_PLAYLIST else normalizedUrl
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
                                        source = channelSourceTag
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
                                source = channelSourceTag
                            )
                        )
                        currentName = ""
                        currentLogo = ""
                    }
                }

                // Compare with existing channels and update
                val existingChannels = if (isAccountPlaylist) {
                    customChannels.filter { belongsToAccountPlaylistImport(context, it.source, normalizedUrl) }
                } else {
                    customChannels.filter { it.source == normalizedUrl }
                }

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
                    val server = when {
                        isAccountPlaylist &&
                            belongsToAccountPlaylistImport(context, ch.source, normalizedUrl) ->
                            newByUrl[ch.url]
                        !isAccountPlaylist && ch.source == normalizedUrl -> newByUrl[ch.url]
                        else -> null
                    }
                    if (server == null) {
                        ch
                    } else {
                        val merged = ch.copy(
                            name = server.name,
                            logo = server.logo,
                            category = server.category,
                            source = if (isAccountPlaylist) SOURCE_ACCOUNT_PLAYLIST else ch.source,
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
                    afterPlaylistDataChanged(context)
                }

                // Update cached fingerprint after a successful parse (even if didChange is false).
                prefs.edit().apply {
                    putString(hashKey, newHash)
                    if (newEtag.isNotEmpty()) putString(etagKey, newEtag)
                    if (newLastModified > 0L) putLong(lastModifiedKey, newLastModified)
                    apply()
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

    /**
     * Sinkronkan semua playlist dari URL server (unduh ulang + merge ke channel lokal).
     * URL playlist akun login diproses dulu (biasanya server IPTV utama).
     * Setelah selesai memuat ulang dari disk dan menaikkan [channelsRevision] agar UI ikut terbarui.
     */
    /** Unduh playlist.json akun dan merge DRM ke channel M3U yang sudah ada. */
    suspend fun syncAccountPlaylistJsonDrm(context: Context) {
        val jsonUrl = deriveV216JsonUrl(context)?.trim().orEmpty()
        if (jsonUrl.isBlank()) return
        withContext(Dispatchers.IO) {
            refreshPlaylistFromServer(context, jsonUrl, forceFullFetch = true)
        }
        loadChannels(context)
        _channelsRevision.value = _channelsRevision.value + 1
    }

    suspend fun syncAllPlaylistsFromServer(context: Context, forceFullFetch: Boolean = true) {
        if (AuthRepository.isLoggedIn(context)) {
            try {
                AuthRepository.syncPlaylistUrlFromLoginApi(context)
            } catch (e: Exception) {
                android.util.Log.e(
                    "ChannelRepository",
                    "syncPlaylistUrlFromLoginApi gagal — lanjut unduh dari URL yang ada",
                    e,
                )
            }
        }

        val ordered = collectPlaylistRefreshUrls(context).toMutableList()

        // Playlist akun (M3U) tidak membawa info DRM. Derive URL v216.json dari server base URL
        // dan tambahkan ke daftar refresh agar DRM channel DASH (RCTI, GTV, dll.) terisi otomatis.
        deriveV216JsonUrl(context)?.let { v216Url ->
            if (!ordered.contains(v216Url)) {
                ordered.add(v216Url)
                addPlaylistUrl(context, v216Url)
                android.util.Log.d("ChannelRepository", "Auto-adding v216 JSON: $v216Url")
            }
        }

        if (ordered.isEmpty()) {
            loadChannels(context)
            return
        }
        android.util.Log.d(
            "ChannelRepository",
            "syncAllPlaylists: refreshing ${ordered.size} URL(s) (json=${ordered.count { isV216JsonSource(it) }})",
        )
        withContext(Dispatchers.IO) {
            ordered.forEach { url ->
                refreshPlaylistFromServer(context, url, forceFullFetch)
            }
        }
        loadChannels(context)
        _channelsRevision.value = _channelsRevision.value + 1
        afterPlaylistDataChanged(context)
    }

    /**
     * Derive URL playlist JSON (v216 dengan DRM) dari URL playlist M3U akun.
     * Server mql_manager punya dua endpoint:
     *   - `/public/users/{appKey}/playlist.m3u` → M3U, TANPA DRM
     *   - `/public/users/{appKey}/playlist.json` → JSON v216, DENGAN DRM
     * App hanya menyimpan URL M3U — cukup ganti suffix untuk mendapat JSON.
     * Contoh: `.../playlist.m3u` → `.../playlist.json`
     */
    private fun deriveV216JsonUrl(context: Context): String? {
        val authUrl = AuthRepository.getResolvedPlaylistUrl(context).trim()
        if (authUrl.isNotBlank()) {
            // Endpoint JSON server mql_manager: ganti .m3u → .json atau ?format=json
            if (authUrl.contains("/playlist.m3u", ignoreCase = true)) {
                return authUrl.replace("/playlist.m3u", "/playlist.json", ignoreCase = true)
            }
            if (authUrl.contains(".m3u", ignoreCase = true)) {
                return authUrl.replace(".m3u", ".json", ignoreCase = true)
            }
        }
        // Fallback: derive dari server base URL (host tanpa port + /v216.json)
        val base = AuthRepository.getServerBaseUrl(context).trim().ifBlank { return null }
        return try {
            val u = URL(base)
            val host = u.host.ifBlank { return null }
            val scheme = u.protocol.ifBlank { "http" }
            "$scheme://$host/v216.json"
        } catch (_: Exception) {
            null
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
                .putStringSet(KEY_PLAYLIST_URLS, HashSet(currentSet))
                .apply()
        }

        if (AuthRepository.isLoggedIn(context)) {
            val accountUrl = AuthRepository.getResolvedPlaylistUrl(context).trim()
            if (accountUrl.isNotBlank() && currentSet.remove(DEFAULT_SAMPLE_PLAYLIST_URL)) {
                prefs.edit().putStringSet(KEY_PLAYLIST_URLS, HashSet(currentSet)).apply()
            }
        }

        val normalized = currentSet.map { it.trim() }.filter { it.isNotBlank() }.toSet()

        // Order requirement:
        // 1) Default "simple/sample" playlist (if present)
        // 2) Account playlist URL (if present)
        // 3) Others
        val ordered = ArrayList<String>(normalized.size)

        if (DEFAULT_SAMPLE_PLAYLIST_URL.isNotBlank() && normalized.contains(DEFAULT_SAMPLE_PLAYLIST_URL)) {
            ordered.add(DEFAULT_SAMPLE_PLAYLIST_URL)
        }

        val accountUrl = AuthRepository.getResolvedPlaylistUrl(context).trim()
        if (accountUrl.isNotBlank() && normalized.contains(accountUrl) && !ordered.contains(accountUrl)) {
            ordered.add(accountUrl)
        }

        // Remaining URLs (stable-ish ordering for UI/refresh runs)
        normalized
            .asSequence()
            .filterNot { it == DEFAULT_SAMPLE_PLAYLIST_URL || it == accountUrl }
            .sorted()
            .forEach { ordered.add(it) }

        return ordered
    }

    fun addPlaylistUrl(context: Context, url: String) {
        val normalized = url.trim()
        if (normalized.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
        val currentSet = (prefs.getStringSet(KEY_PLAYLIST_URLS, emptySet()) ?: emptySet()).toMutableSet()
        currentSet.add(normalized)
        prefs.edit()
            .remove(KEY_PLAYLIST_URL_LEGACY)
            .putStringSet(KEY_PLAYLIST_URLS, HashSet(currentSet))
            .apply()
    }

    fun removePlaylistUrl(context: Context, url: String) {
        val normalized = url.trim()
        if (normalized.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
        val currentSet = (prefs.getStringSet(KEY_PLAYLIST_URLS, emptySet()) ?: emptySet()).toMutableSet()
        if (!currentSet.remove(normalized)) return
        prefs.edit()
            .remove(KEY_PLAYLIST_URL_LEGACY)
            .putStringSet(KEY_PLAYLIST_URLS, HashSet(currentSet))
            .apply()
    }

    /**
     * User yang sudah login dengan playlist akun tidak perlu playlist sampel bawaan (192.168…),
     * supaya sinkronisasi dan grid hanya mengikuti M3U server (mis. 15 channel).
     */
    fun removeDefaultSamplePlaylistForLoggedInUser(context: Context) {
        if (!AuthRepository.isLoggedIn(context)) return
        val sample = DEFAULT_SAMPLE_PLAYLIST_URL.trim()
        if (sample.isBlank()) return
        removePlaylistUrl(context, sample)
        val removedAny = customChannels.removeAll { it.source == sample }
        if (removedAny) {
            saveChannels(context)
        }
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

        // Simpan sebagai JSON array agar field dengan koma (drmLicenseUrl ClearKey JSON) tidak terpotong.
        val arr = JSONArray()
        for (ch in customChannels) {
            arr.put(
                JSONObject()
                    .put("id", ch.id)
                    .put("name", ch.name)
                    .put("url", ch.url)
                    .put("logo", ch.logo)
                    .put("category", ch.category)
                    .put("source", ch.source)
                    .put("drm", ch.drmLicenseUrl)
            )
        }
        prefs.edit()
            .putString("custom_channels_json", arr.toString())
            .putInt("next_id", nextId)
            .putBoolean("samples_cleared", sampleChannelsCleared)
            .apply()

        loadRecentlyWatched(context)
        pruneRecentlyWatchedInMemory(context)
        _channelsRevision.value = _channelsRevision.value + 1
    }

    /**
     * Setelah playlist di-update: buang ID terakhir ditonton yang channel-nya sudah dihapus,
     * lalu sinkronkan ulang kartu di beranda Android TV.
     */
    fun afterPlaylistDataChanged(context: Context) {
        loadRecentlyWatched(context)
        val removed = pruneRecentlyWatchedInMemory(context)
        if (removed > 0) {
            android.util.Log.d(
                "ChannelRepository",
                "Removed $removed stale recently-watched ID(s) after playlist update",
            )
        }
        com.sihiver.mqltv.tv.TvHomeRecommendations.syncAsync(context)
    }
    
    fun loadChannels(context: Context) {
        val prefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)
        sampleChannelsCleared = prefs.getBoolean("samples_cleared", false)
        nextId = prefs.getInt("next_id", 100)
        customChannels.clear()

        // Format baru: JSON array (aman terhadap koma dalam drmLicenseUrl)
        val jsonStr = prefs.getString("custom_channels_json", "").orEmpty().trim()
        if (jsonStr.isNotEmpty()) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    customChannels.add(
                        Channel(
                            id = obj.optInt("id", 0),
                            name = obj.optString("name", ""),
                            url = obj.optString("url", ""),
                            logo = obj.optString("logo", ""),
                            category = obj.optString("category", ""),
                            source = obj.optString("source", ""),
                            drmLicenseUrl = obj.optString("drm", ""),
                        )
                    )
                }
                return
            } catch (_: Exception) {
                // Fall through ke format lama jika JSON korup
            }
        }

        // Format lama (migrasi): comma-delimited — hanya dibaca jika format baru belum ada.
        // Setelah dibaca, langsung disimpan ulang dalam format baru.
        val legacyStr = prefs.getString("custom_channels", "").orEmpty()
        if (legacyStr.isNotEmpty()) {
            legacyStr.split("|").forEach { channelStr ->
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
                            drmLicenseUrl = if (parts.size >= 7) parts[6] else "",
                        )
                    )
                }
            }
            // Migrasi: simpan ulang ke format JSON baru sekarang
            saveChannels(context)
        }
    }
    
    /**
     * Pastikan URL playlist ter-resolve akun ada di prefs (mis. setelah data lama tanpa KEY_PLAYLIST_URLS).
     */
    fun ensureAccountPlaylistUrlStored(context: Context) {
        if (!AuthRepository.isLoggedIn(context)) return
        val url = AuthRepository.getResolvedPlaylistUrl(context).trim()
        if (url.isNotBlank()) addPlaylistUrl(context, url)
    }

    /**
     * Hapus semua channel dari cache lokal. **Tidak** memanggil [clearPlaylistUrls] — konfigurasi URL
     * playlist tetap dipakai tombol refresh / sinkron agar grid bisa diisi ulang dari server.
     */
    fun clearAllChannels(context: Context) {
        customChannels.clear()
        sampleChannels.clear()
        sampleChannelsCleared = true
        nextId = 100
        saveChannels(context)
        ensureAccountPlaylistUrlStored(context)
        android.util.Log.d("ChannelRepository", "All channels cleared (playlist URLs prefs kept)")
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
        com.sihiver.mqltv.tv.TvHomeRecommendations.syncAsync(context)
    }
    
    fun getRecentlyWatched(): List<Channel> {
        val allChannels = getAllChannels()
        return recentlyWatchedIds.mapNotNull { id ->
            allChannels.find { it.id == id }
        }
    }

    /**
     * Hapus ID terakhir ditonton yang tidak lagi ada di daftar channel (setelah hapus dari playlist).
     * @return jumlah ID yang dibuang
     */
    private fun pruneRecentlyWatchedInMemory(context: Context): Int {
        if (recentlyWatchedIds.isEmpty()) return 0
        val validIds = getAllChannels().map { it.id }.toSet()
        val before = recentlyWatchedIds.size
        recentlyWatchedIds.retainAll(validIds)
        val removed = before - recentlyWatchedIds.size
        if (removed > 0) {
            saveRecentlyWatched(context)
        }
        return removed
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
