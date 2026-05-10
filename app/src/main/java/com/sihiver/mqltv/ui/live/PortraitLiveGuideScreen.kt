package com.sihiver.mqltv.ui.live

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text as Material3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.playback.LiveExoPlayerFactory
import com.sihiver.mqltv.repository.ChannelRepository
import com.sihiver.mqltv.service.PresenceManager
import kotlinx.coroutines.launch
import java.util.Locale

private val PortraitLiveBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF0A2A63),
        Color(0xFF081F4B),
        Color(0xFF061736),
    )
)

private fun portraitCategoryLabel(rawCategory: String): String {
    if (rawCategory == "ALL_CHANNELS") return "All Channels"
    val normalized = rawCategory
        .trim()
        .replace("_", " ")
        .replace("-", " ")
        .replace(Regex("\\s+"), " ")
    val lower = normalized.lowercase(Locale.getDefault())
    return when (lower) {
        "kids" -> "Kids"
        "knowledge" -> "Knowledge"
        "local" -> "Local"
        "religious" -> "Religious"
        "news" -> "News"
        "sports" -> "Sports"
        "entertainment" -> "Entertainment"
        "event" -> "Event"
        else -> normalized.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
                }
            }
    }
}

private fun resolveInitialLiveCategory(context: Context): String {
    val peek = ChannelRepository.peekLastLiveGridTabWhenOpeningPlayer(context)?.trim().orEmpty()
    if (peek.isEmpty()) return "ALL_CHANNELS"
    if (peek.equals("ALL_CHANNELS", ignoreCase = true)) return "ALL_CHANNELS"
    val keys = ChannelRepository.getLiveScreenCategoryTabKeys()
    return keys.find { it.equals(peek, ignoreCase = true) } ?: "ALL_CHANNELS"
}

/**
 * Halaman potret: video di atas, info channel, panduan daftar + filter (desain seperti referensi Hulu mobile).
 */
@Composable
fun PortraitLiveGuideScreen(
    initialChannelId: Int,
    onFullscreen: (Channel) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val presenceManager = remember { PresenceManager(context.applicationContext) }
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onClose)

    DisposableEffect(Unit) {
        onDispose {
            presenceManager.sendOfflinePresence()
        }
    }

    var channels by remember { mutableStateOf(ChannelRepository.getAllChannels()) }
    val channelsRevision by ChannelRepository.channelsRevision.collectAsState(initial = 0)
    var refreshKey by remember { mutableStateOf(0) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchResults by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var searchResultsTitle by remember { mutableStateOf("Search") }
    var isRefreshing by remember { mutableStateOf(false) }
    var guideFilterMenuExpanded by remember { mutableStateOf(false) }

    var selectedCategory by remember { mutableStateOf(resolveInitialLiveCategory(context)) }
    var playingChannel by remember {
        mutableStateOf(ChannelRepository.getChannelById(initialChannelId))
    }

    LaunchedEffect(refreshKey, channelsRevision) {
        ChannelRepository.loadChannels(context)
        ChannelRepository.loadRecentlyWatched(context)
        ChannelRepository.loadFavorites(context)
        channels = ChannelRepository.getAllChannels()
        val pending = ChannelRepository.peekPendingLiveGridCategoryTab(context)
        if (pending != null) {
            val keys = ChannelRepository.getLiveScreenCategoryTabKeys()
            val resolved = keys.find { it.equals(pending, ignoreCase = true) }
            if (resolved != null) {
                selectedCategory = resolved
                ChannelRepository.clearPendingLiveGridCategoryTab(context)
            }
        }
        ChannelRepository.getChannelById(initialChannelId)?.let { playingChannel = it }
    }

    val categoryTabs = remember(channels) {
        ChannelRepository.getLiveScreenCategoryTabKeys()
    }

    val filteredChannels = remember(channels, selectedCategory) {
        if (selectedCategory == "ALL_CHANNELS") {
            ChannelRepository.sortLiveChannelsLocalSportsFirst(channels)
        } else {
            channels.filter { it.category.trim().equals(selectedCategory, ignoreCase = true) }
        }
    }

    LaunchedEffect(filteredChannels, initialChannelId) {
        val byIntent = ChannelRepository.getChannelById(initialChannelId)
        if (byIntent != null && filteredChannels.any { it.id == byIntent.id }) {
            playingChannel = byIntent
        } else if (playingChannel != null && filteredChannels.none { it.id == playingChannel!!.id }) {
            playingChannel = filteredChannels.firstOrNull()
        } else if (playingChannel == null && filteredChannels.isNotEmpty()) {
            playingChannel = filteredChannels.first()
        }
    }

    val prefs = remember {
        context.getSharedPreferences("video_settings", Context.MODE_PRIVATE)
    }

    if (showSearchResults) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PortraitLiveBackgroundBrush)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showSearchResults = false }) {
                    Material3Text("← Kembali", color = Color(0xFF00BCD4))
                }
                Material3Text(searchResultsTitle, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.size(48.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(searchResults) { index, ch ->
                    GuideChannelRow(
                        channel = ch,
                        rank = index + 1,
                        isSelected = playingChannel?.id == ch.id,
                        onClick = {
                            ChannelRepository.addToRecentlyWatched(context, ch.id)
                            ChannelRepository.setLastLiveGridTabWhenOpeningPlayer(context, selectedCategory)
                            playingChannel = ch
                            showSearchResults = false
                        }
                    )
                }
            }
        }
        return
    }

    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Material3Text(text = "Cari Channel", color = Color.White) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    label = { androidx.compose.material3.Text("Nama channel") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val q = searchQuery.trim()
                        searchResults = if (q.isBlank()) emptyList() else channels.filter {
                            it.name.contains(q, ignoreCase = true)
                        }
                        searchResultsTitle = if (q.isBlank()) "Search" else "Search: $q"
                        showSearchDialog = false
                        showSearchResults = true
                    }
                ) {
                    Material3Text("OK", color = Color(0xFF00BCD4))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSearchDialog = false }) {
                    Material3Text("Batal", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PortraitLiveBackgroundBrush)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Video lebar penuh rasio 16:9 (tanpa header atas)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
        ) {
            PortraitInlinePlayer(
                channel = playingChannel,
                presenceManager = presenceManager,
                accelerationSetting = prefs.getString("acceleration", "HW (Hardware)") ?: "HW (Hardware)"
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup", tint = Color.White)
                }
                IconButton(
                    enabled = playingChannel != null,
                    onClick = {
                        val ch = playingChannel ?: return@IconButton
                        ChannelRepository.addToRecentlyWatched(context, ch.id)
                        ChannelRepository.setLastLiveGridTabWhenOpeningPlayer(context, selectedCategory)
                        onFullscreen(ch)
                    }
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "Layar penuh", tint = Color.White)
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Material3Text(
                    "MENONTON LIVE",
                    color = Color(0xFF00D4AA),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .padding(top = 6.dp),
                    progress = { 1f },
                    color = Color(0xFF00D4AA),
                    trackColor = Color(0x33FFFFFF),
                )
            }
        }

        playingChannel?.let { current ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    if (current.logo.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(current.logo)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Material3Text(
                            current.name.take(3).uppercase(Locale.getDefault()),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF123067)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Material3Text(
                        current.category.ifBlank { "Live TV" },
                        color = Color(0xFFB0BEC5),
                        fontSize = 12.sp
                    )
                    Material3Text(
                        current.name,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Material3Text(
                "GUIDE",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showSearchDialog = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Cari", tint = Color.White)
                }
                IconButton(
                    onClick = {
                        if (isRefreshing) return@IconButton
                        isRefreshing = true
                        scope.launch {
                            try {
                                ChannelRepository.getPlaylistUrls(context).forEach { url ->
                                    ChannelRepository.refreshPlaylistFromServer(context, url)
                                }
                                refreshKey++
                            } finally {
                                isRefreshing = false
                            }
                        }
                    }
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                }
                Box {
                    OutlinedButton(onClick = { guideFilterMenuExpanded = true }) {
                        Material3Text(
                            portraitCategoryLabel(selectedCategory),
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(
                        expanded = guideFilterMenuExpanded,
                        onDismissRequest = { guideFilterMenuExpanded = false }
                    ) {
                        categoryTabs.forEach { cat ->
                            DropdownMenuItem(
                                text = { Material3Text(portraitCategoryLabel(cat)) },
                                onClick = {
                                    selectedCategory = cat
                                    guideFilterMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Color(0x22FFFFFF))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(filteredChannels) { index, channel ->
                GuideChannelRow(
                    channel = channel,
                    rank = index + 1,
                    isSelected = playingChannel?.id == channel.id,
                    onClick = {
                        ChannelRepository.addToRecentlyWatched(context, channel.id)
                        ChannelRepository.setLastLiveGridTabWhenOpeningPlayer(context, selectedCategory)
                        playingChannel = channel
                    }
                )
            }
        }
    }
}

@Composable
private fun PortraitInlinePlayer(
    channel: Channel?,
    presenceManager: PresenceManager,
    accelerationSetting: String,
) {
    val context = LocalContext.current

    val playerView = remember(context) {
        PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            keepScreenOn = true
        }
    }

    DisposableEffect(channel?.id) {
        if (channel == null || channel.url.isBlank()) {
            playerView.player = null
            presenceManager.stopHeartbeat()
            return@DisposableEffect onDispose { }
        }

        val exo = LiveExoPlayerFactory.createExoPlayer(
            context = context,
            channel = channel,
            presenceManager = presenceManager,
            accelerationSetting = accelerationSetting,
        )
        playerView.player = exo
        onDispose {
            playerView.player = null
            presenceManager.stopHeartbeat()
            exo.release()
        }
    }

    if (channel == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101010)),
            contentAlignment = Alignment.Center
        ) {
            Material3Text(
                "Memuat…",
                color = Color(0xFF90A4AE),
                fontSize = 14.sp
            )
        }
    } else {
        AndroidView(
            factory = { playerView },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun GuideChannelRow(
    channel: Channel,
    rank: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val bg = if (isSelected) Color(0x3327AEAE) else Color(0xFF1E2530)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Material3Text(
            "$rank",
            color = Color(0xFF78909C),
            fontSize = 13.sp,
            modifier = Modifier.padding(end = 4.dp)
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (channel.logo.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(channel.logo)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Material3Text(
                    channel.name.take(2).uppercase(Locale.getDefault()),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF123067)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Material3Text(
                "Live • ${channel.category.ifBlank { "TV" }}",
                color = Color(0xFFB0BEC5),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Material3Text(
                channel.name.ifBlank { "Channel $rank" },
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(3.dp),
                progress = { 0.35f },
                color = Color(0xFF00D4AA),
                trackColor = Color(0x22FFFFFF),
            )
        }
    }
}
