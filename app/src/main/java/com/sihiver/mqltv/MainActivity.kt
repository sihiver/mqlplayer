package com.sihiver.mqltv

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.*
import androidx.compose.material3.Card as Material3Card
import androidx.compose.material3.CardDefaults as Material3CardDefaults
import androidx.compose.material3.Text as Material3Text
import coil.compose.AsyncImage
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.repository.ChannelRepository
import com.sihiver.mqltv.ui.theme.MQLTVTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make status bar transparent with light icons (for dark theme)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false // Light icons for dark theme
        }
        
        // Load saved channels
        ChannelRepository.loadChannels(this)
        
        // Auto-refresh playlist on launch
        lifecycleScope.launch {
            try {
                val playlistUrl = ChannelRepository.getPlaylistUrl(this@MainActivity)
                if (playlistUrl.isNotEmpty()) {
                    android.util.Log.d("MainActivity", "Auto-refreshing playlist from: $playlistUrl")
                    ChannelRepository.refreshPlaylistFromServer(this@MainActivity, playlistUrl)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Auto-refresh failed", e)
            }
        }
        
        setContent {
            MQLTVTheme {
                var selectedTab by remember { mutableStateOf(0) }
                var isRefreshing by remember { mutableStateOf(false) }
                var showExitDialog by remember { mutableStateOf(false) }
                val isTvDevice = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

                BackHandler(enabled = !showExitDialog) {
                    showExitDialog = true
                }

                if (showExitDialog) {
                    BackHandler {
                        showExitDialog = false
                    }

                    AlertDialog(
                        onDismissRequest = { showExitDialog = false },
                        title = { Material3Text("Keluar aplikasi?") },
                        text = { Material3Text("Tekan Keluar untuk menutup aplikasi.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showExitDialog = false
                                    this@MainActivity.finish()
                                }
                            ) {
                                Material3Text("Keluar")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExitDialog = false }) {
                                Material3Text("Batal")
                            }
                        }
                    )
                }
                
                // Periodic refresh every 30 minutes
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(30 * 60 * 1000L) // 30 minutes
                        try {
                            val playlistUrl = ChannelRepository.getPlaylistUrl(this@MainActivity)
                            if (playlistUrl.isNotEmpty()) {
                                android.util.Log.d("MainActivity", "Periodic refresh from: $playlistUrl")
                                isRefreshing = true
                                ChannelRepository.refreshPlaylistFromServer(this@MainActivity, playlistUrl)
                                isRefreshing = false
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Periodic refresh failed", e)
                            isRefreshing = false
                        }
                    }
                }
                
                val content: @Composable () -> Unit = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF000000))
                    ) {
                        when (selectedTab) {
                            0 -> LiveChannelsScreen(
                                onChannelClick = { channel ->
                                    try {
                                        // Add to recently watched before playing
                                        ChannelRepository.addToRecentlyWatched(this@MainActivity, channel.id)

                                        // Check which player to use from settings
                                        val prefs = getSharedPreferences("video_settings", android.content.Context.MODE_PRIVATE)
                                        val playerType = prefs.getString("player_type", "ExoPlayer") ?: "ExoPlayer"

                                        val intent = if (playerType == "VLC") {
                                            Intent(this@MainActivity, PlayerActivityVLC::class.java)
                                        } else {
                                            Intent(this@MainActivity, PlayerActivityExo::class.java)
                                        }
                                        intent.putExtra("CHANNEL_ID", channel.id)
                                        startActivity(intent)
                                    } catch (e: Exception) {
                                        android.util.Log.e("MainActivity", "Error starting PlayerActivity", e)
                                    }
                                }
                            )
                            1 -> MovieChannelsScreen(
                                onChannelClick = { channel ->
                                    try {
                                        ChannelRepository.addToRecentlyWatched(this@MainActivity, channel.id)

                                        val prefs = getSharedPreferences("video_settings", android.content.Context.MODE_PRIVATE)
                                        val playerType = prefs.getString("player_type", "ExoPlayer") ?: "ExoPlayer"

                                        val intent = if (playerType == "VLC") {
                                            Intent(this@MainActivity, PlayerActivityVLC::class.java)
                                        } else {
                                            Intent(this@MainActivity, PlayerActivityExo::class.java)
                                        }
                                        intent.putExtra("CHANNEL_ID", channel.id)
                                        startActivity(intent)
                                    } catch (e: Exception) {
                                        android.util.Log.e("MainActivity", "Error starting PlayerActivity", e)
                                    }
                                }
                            )
                            3 -> CenterMessage("Series - Coming Soon")
                            4 -> SettingsScreen(
                                onClearPlaylist = {
                                    ChannelRepository.clearAllChannels(this@MainActivity)
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "Playlist cleared",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }
                }

                if (isTvDevice) {
                    // TV: menu pindah ke kiri (kecil saat awal, lebar saat difokuskan)
                    val scope = rememberCoroutineScope()
                    var sidebarExpanded by remember { mutableStateOf(false) }
                    var collapseJob by remember { mutableStateOf<Job?>(null) }
                    val sidebarWidth by animateDpAsState(
                        targetValue = if (sidebarExpanded) 220.dp else 72.dp,
                        label = "tvSidebarWidth"
                    )

                    fun requestExpand() {
                        collapseJob?.cancel()
                        sidebarExpanded = true
                    }

                    fun requestCollapseLater() {
                        collapseJob?.cancel()
                        collapseJob = scope.launch {
                            delay(250)
                            sidebarExpanded = false
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF000000))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(sidebarWidth)
                                .background(Color(0xFF1A1A1A))
                                .padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            @Composable
                            fun TvMenuItem(
                                index: Int,
                                label: String,
                                icon: @Composable () -> Unit,
                                selected: Boolean,
                                onClick: () -> Unit
                            ) {
                                var focused by remember { mutableStateOf(false) }
                                Material3Card(
                                    onClick = onClick,
                                    modifier = Modifier
                                        .padding(horizontal = 10.dp)
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .onFocusChanged {
                                            focused = it.isFocused
                                            if (it.isFocused) requestExpand() else requestCollapseLater()
                                        }
                                        .focusable(),
                                    colors = Material3CardDefaults.cardColors(
                                        containerColor = when {
                                            focused -> Color(0xFF2A2A2A)
                                            selected -> Color(0xFF222222)
                                            else -> Color(0xFF1A1A1A)
                                        }
                                    ),
                                    border = if (focused) BorderStroke(2.dp, Color(0xFFE50914)) else null,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        icon()
                                        if (sidebarExpanded) {
                                            Material3Text(
                                                text = label,
                                                color = if (selected || focused) Color.White else Color.Gray,
                                                fontSize = 14.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            TvMenuItem(
                                index = 0,
                                label = "Live",
                                icon = {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Live",
                                        tint = if (selectedTab == 0) Color(0xFFE50914) else Color.White
                                    )
                                },
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 }
                            )
                            TvMenuItem(
                                index = 1,
                                label = "Movie",
                                icon = {
                                    Icon(
                                        Icons.Default.Movie,
                                        contentDescription = "Movie",
                                        tint = if (selectedTab == 1) Color(0xFFE50914) else Color.White
                                    )
                                },
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 }
                            )
                            TvMenuItem(
                                index = 2,
                                label = "Add",
                                icon = {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Add",
                                        tint = Color(0xFFE50914)
                                    )
                                },
                                selected = false,
                                onClick = {
                                    val intent = Intent(this@MainActivity, AddChannelActivity::class.java)
                                    startActivity(intent)
                                }
                            )
                            TvMenuItem(
                                index = 3,
                                label = "Series",
                                icon = {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Series",
                                        tint = if (selectedTab == 3) Color(0xFFE50914) else Color.White
                                    )
                                },
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 }
                            )
                            TvMenuItem(
                                index = 4,
                                label = "Settings",
                                icon = {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = "Settings",
                                        tint = if (selectedTab == 4) Color(0xFFE50914) else Color.White
                                    )
                                },
                                selected = selectedTab == 4,
                                onClick = { selectedTab = 4 }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                        ) {
                            content()
                        }
                    }
                } else {
                    // Phone/Tablet: menu tetap di bawah
                    Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            NavigationBar(
                                containerColor = Color(0xFF1A1A1A),
                                contentColor = Color.White
                            ) {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Live", tint = if (selectedTab == 0) Color(0xFFE50914) else Color.Gray) },
                                    label = { Material3Text("Live", color = if (selectedTab == 0) Color(0xFFE50914) else Color.Gray) },
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Movie, contentDescription = "Movie", tint = if (selectedTab == 1) Color(0xFFE50914) else Color.Gray) },
                                    label = { Material3Text("Movie", color = if (selectedTab == 1) Color(0xFFE50914) else Color.Gray) },
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Add, contentDescription = "Add", tint = Color(0xFFE50914)) },
                                    label = { },
                                    selected = false,
                                    onClick = {
                                        val intent = Intent(this@MainActivity, AddChannelActivity::class.java)
                                        startActivity(intent)
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Search, contentDescription = "Series", tint = if (selectedTab == 3) Color(0xFFE50914) else Color.Gray) },
                                    label = { Material3Text("Series", color = if (selectedTab == 3) Color(0xFFE50914) else Color.Gray) },
                                    selected = selectedTab == 3,
                                    onClick = { selectedTab = 3 }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = if (selectedTab == 4) Color(0xFFE50914) else Color.Gray) },
                                    label = { Material3Text("Settings", color = if (selectedTab == 4) Color(0xFFE50914) else Color.Gray) },
                                    selected = selectedTab == 4,
                                    onClick = { selectedTab = 4 }
                                )
                            }
                        }
                    ) { paddingValues ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Reload channels when returning to this activity
        ChannelRepository.loadChannels(this)
    }
}

@Composable
fun CenterMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center
    ) {
        Material3Text(
            text = message,
            fontSize = 20.sp,
            color = Color.Gray
        )
    }
}

@Composable
fun SettingsScreen(onClearPlaylist: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("video_settings", Context.MODE_PRIVATE)
    
    var showOrientationDialog by remember { mutableStateOf(false) }
    var showAccelerationDialog by remember { mutableStateOf(false) }
    var showAspectRatioDialog by remember { mutableStateOf(false) }
    var showPlayerDialog by remember { mutableStateOf(false) }
    
    val orientationOptions = listOf("Auto", "Portrait", "Landscape", "Sensor Landscape")
    val accelerationOptions = listOf("HW (Hardware)", "HW+ (Hardware+)", "SW (Software)")
    val aspectRatioOptions = listOf("Fit", "Fill", "Zoom", "16:9", "4:3")
    val playerOptions = listOf("ExoPlayer", "VLC")
    
    val currentOrientation = prefs.getString("orientation", "Sensor Landscape") ?: "Sensor Landscape"
    val currentAcceleration = prefs.getString("acceleration", "HW (Hardware)") ?: "HW (Hardware)"
    val currentAspectRatio = prefs.getString("aspect_ratio", "Fit") ?: "Fit"
    val currentPlayer = prefs.getString("player_type", "ExoPlayer") ?: "ExoPlayer"
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Material3Text(
            text = "Settings",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        // Video Preferences Section
        Material3Text(
            text = "Video Preferences",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF00BCD4),
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
        )
        
        // Player Type Setting
        Material3Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showPlayerDialog = true },
            colors = Material3CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Material3Text(
                    text = "Player",
                    fontSize = 16.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Material3Text(
                    text = currentPlayer,
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        
        // Orientation Setting
        Material3Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showOrientationDialog = true },
            colors = Material3CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Material3Text(
                    text = "Orientasi Layar",
                    fontSize = 16.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Material3Text(
                    text = currentOrientation,
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        
        // Acceleration Mode Setting
        Material3Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAccelerationDialog = true },
            colors = Material3CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Material3Text(
                    text = "Mode Akselerasi",
                    fontSize = 16.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Material3Text(
                    text = currentAcceleration,
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        
        // Aspect Ratio Setting
        Material3Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAspectRatioDialog = true },
            colors = Material3CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Material3Text(
                    text = "Rasio Aspek",
                    fontSize = 16.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Material3Text(
                    text = currentAspectRatio,
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        
        // Playlist Section
        Material3Text(
            text = "Playlist",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF00BCD4),
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )
        
        Material3Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClearPlaylist() },
            colors = Material3CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Material3Text(
                    text = "Clear Playlist",
                    fontSize = 16.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Material3Text(
                    text = "›",
                    fontSize = 24.sp,
                    color = Color(0xFFE50914)
                )
            }
        }
    }
    
    // Orientation Dialog
    if (showOrientationDialog) {
        AlertDialog(
            onDismissRequest = { showOrientationDialog = false },
            title = { Material3Text("Pilih Orientasi Layar", color = Color.White) },
            text = {
                Column {
                    orientationOptions.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    prefs.edit().putString("orientation", option).apply()
                                    showOrientationDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = option == currentOrientation,
                                onClick = {
                                    prefs.edit().putString("orientation", option).apply()
                                    showOrientationDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFF00BCD4)
                                )
                            )
                            Material3Text(
                                text = option,
                                color = Color.White,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOrientationDialog = false }) {
                    Material3Text("Tutup", color = Color(0xFF00BCD4))
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }
    
    // Acceleration Dialog
    if (showAccelerationDialog) {
        AlertDialog(
            onDismissRequest = { showAccelerationDialog = false },
            title = { Material3Text("Pilih Mode Akselerasi", color = Color.White) },
            text = {
                Column {
                    accelerationOptions.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    prefs.edit().putString("acceleration", option).apply()
                                    showAccelerationDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = option == currentAcceleration,
                                onClick = {
                                    prefs.edit().putString("acceleration", option).apply()
                                    showAccelerationDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFF00BCD4)
                                )
                            )
                            Material3Text(
                                text = option,
                                color = Color.White,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccelerationDialog = false }) {
                    Material3Text("Tutup", color = Color(0xFF00BCD4))
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }
    
    // Aspect Ratio Dialog
    if (showAspectRatioDialog) {
        AlertDialog(
            onDismissRequest = { showAspectRatioDialog = false },
            title = { Material3Text("Pilih Rasio Aspek", color = Color.White) },
            text = {
                Column {
                    aspectRatioOptions.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    prefs.edit().putString("aspect_ratio", option).apply()
                                    showAspectRatioDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = option == currentAspectRatio,
                                onClick = {
                                    prefs.edit().putString("aspect_ratio", option).apply()
                                    showAspectRatioDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFF00BCD4)
                                )
                            )
                            Material3Text(
                                text = option,
                                color = Color.White,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAspectRatioDialog = false }) {
                    Material3Text("Tutup", color = Color(0xFF00BCD4))
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }
    
    // Player Selection Dialog
    if (showPlayerDialog) {
        AlertDialog(
            onDismissRequest = { showPlayerDialog = false },
            title = { Material3Text("Pilih Player", color = Color.White) },
            text = {
                Column {
                    playerOptions.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    prefs.edit().putString("player_type", option).apply()
                                    showPlayerDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = option == currentPlayer,
                                onClick = {
                                    prefs.edit().putString("player_type", option).apply()
                                    showPlayerDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFF00BCD4)
                                )
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Material3Text(
                                    text = option,
                                    color = Color.White
                                )
                                Material3Text(
                                    text = if (option == "VLC") "Lebih stabil untuk 1080p" else "Default player",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlayerDialog = false }) {
                    Material3Text("Tutup", color = Color(0xFF00BCD4))
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }
}

@Composable
fun LiveChannelsScreen(onChannelClick: (Channel) -> Unit) {
    val context = LocalContext.current
    var channels by remember { mutableStateOf(ChannelRepository.getAllChannels()) }
    var refreshKey by remember { mutableStateOf(0) }
    var showAllCategory by remember { mutableStateOf<String?>(null) }
    var showAllRecent by remember { mutableStateOf(false) }
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    val initialFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }
    
    // Listen to lifecycle events
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Refresh channels
    LaunchedEffect(refreshKey) {
        ChannelRepository.loadChannels(context)
        ChannelRepository.loadRecentlyWatched(context)
        ChannelRepository.loadFavorites(context)
        channels = ChannelRepository.getAllChannels()
    }
    
    val categories = remember(channels) {
        ChannelRepository.getAllCategories().filterNot { it.contains("movie", ignoreCase = true) }
    }
    
    val favorites = remember(refreshKey) {
        ChannelRepository.getFavorites()
    }
    
    val recentlyWatched = remember(refreshKey) {
        ChannelRepository.getRecentlyWatched()
    }
    
    // Show full list when "See all" is clicked
    if (showAllRecent) {
        FullChannelListScreen(
            title = "Recently Watched",
            channels = recentlyWatched,
            onChannelClick = onChannelClick,
            onBack = { showAllRecent = false }
        )
        return
    }
    
    showAllCategory?.let { category ->
        val categoryChannels = if (category == "Favorites") {
            ChannelRepository.getFavorites()
        } else {
            ChannelRepository.getChannelsByCategory(category)
        }
        FullChannelListScreen(
            title = category,
            channels = categoryChannels,
            onChannelClick = onChannelClick,
            onBack = { showAllCategory = null }
        )
        return
    }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .statusBarsPadding()
    ) {
        // Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Material3Text(
                    text = "MQL TV",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Favorites",
                        tint = Color(0xFFE50914),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        
        // Favorites Section
        item {
            if (favorites.isNotEmpty()) {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Material3Text(
                            text = "Favorites",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        TextButton(onClick = { showAllCategory = "Favorites" }) {
                            Material3Text(
                                text = "See all",
                                fontSize = 14.sp,
                                color = Color(0xFF2196F3)
                            )
                        }
                    }
                    
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(favorites) { index, channel ->
                            ChannelCardCompact(
                                channel = channel,
                                onClick = onChannelClick,
                                modifier = if (isTv && !initialFocusRequested && index == 0) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                },
                                onFavoriteClick = { 
                                    ChannelRepository.toggleFavorite(context, channel.id)
                                    refreshKey++
                                },
                                isFavorite = true
                            )
                        }
                    }

                    LaunchedEffect(isTv, favorites.size) {
                        if (isTv && !initialFocusRequested && favorites.isNotEmpty()) {
                            initialFocusRequester.requestFocus()
                            initialFocusRequested = true
                        }
                    }
                }
            }
        }
        
        // Recently Watched Section
        item {
            if (recentlyWatched.isNotEmpty()) {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Material3Text(
                            text = "Recently Watched",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        TextButton(onClick = { showAllRecent = true }) {
                            Material3Text(
                                text = "See all",
                                fontSize = 14.sp,
                                color = Color(0xFF2196F3)
                            )
                        }
                    }
                    
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(recentlyWatched) { index, channel ->
                            ChannelCardCompact(
                                channel = channel,
                                onClick = onChannelClick,
                                modifier = if (isTv && !initialFocusRequested && favorites.isEmpty() && index == 0) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                },
                                onFavoriteClick = { 
                                    ChannelRepository.toggleFavorite(context, channel.id)
                                    refreshKey++
                                },
                                isFavorite = ChannelRepository.isFavorite(channel.id)
                            )
                        }
                    }

                    LaunchedEffect(isTv, favorites.size, recentlyWatched.size) {
                        if (isTv && !initialFocusRequested && favorites.isEmpty() && recentlyWatched.isNotEmpty()) {
                            initialFocusRequester.requestFocus()
                            initialFocusRequested = true
                        }
                    }
                }
            }
        }
        
        // Category Sections
        items(categories) { category ->
            val categoryChannels = ChannelRepository.getChannelsByCategory(category)
            
            if (categoryChannels.isNotEmpty()) {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Material3Text(
                            text = category,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        TextButton(onClick = { showAllCategory = category }) {
                            Material3Text(
                                text = "See all",
                                fontSize = 14.sp,
                                color = Color(0xFF2196F3)
                            )
                        }
                    }
                    
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(categoryChannels) { index, channel ->
                            ChannelCardCompact(
                                channel = channel,
                                onClick = onChannelClick,
                                modifier = if (isTv && !initialFocusRequested && favorites.isEmpty() && recentlyWatched.isEmpty() && index == 0) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                },
                                onFavoriteClick = { 
                                    ChannelRepository.toggleFavorite(context, channel.id)
                                    refreshKey++
                                },
                                isFavorite = ChannelRepository.isFavorite(channel.id)
                            )
                        }
                    }

                    LaunchedEffect(isTv, favorites.size, recentlyWatched.size, categoryChannels.size) {
                        if (
                            isTv &&
                            !initialFocusRequested &&
                            favorites.isEmpty() &&
                            recentlyWatched.isEmpty() &&
                            categoryChannels.isNotEmpty()
                        ) {
                            initialFocusRequester.requestFocus()
                            initialFocusRequested = true
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MovieChannelsScreen(onChannelClick: (Channel) -> Unit) {
    val context = LocalContext.current
    var channels by remember { mutableStateOf(ChannelRepository.getAllChannels()) }
    var refreshKey by remember { mutableStateOf(0) }
    var showAll by remember { mutableStateOf(false) }

    // Refresh channels
    LaunchedEffect(refreshKey) {
        ChannelRepository.loadChannels(context)
        ChannelRepository.loadRecentlyWatched(context)
        ChannelRepository.loadFavorites(context)
        channels = ChannelRepository.getAllChannels()
    }

    val movieChannels = remember(channels) {
        channels.filter { it.category.contains("movie", ignoreCase = true) }
    }

    if (showAll) {
        FullChannelListScreen(
            title = "Movie",
            channels = movieChannels,
            onChannelClick = onChannelClick,
            onBack = { showAll = false }
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .statusBarsPadding()
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Material3Text(
                    text = "Movie",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (movieChannels.isNotEmpty()) {
                    TextButton(onClick = { showAll = true }) {
                        Material3Text(
                            text = "See all",
                            fontSize = 14.sp,
                            color = Color(0xFF2196F3)
                        )
                    }
                }
            }
        }

        if (movieChannels.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Material3Text(
                        text = "Tidak ada channel Movie",
                        color = Color.Gray,
                        fontSize = 16.sp
                    )
                }
            }
        } else {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(movieChannels.take(20)) { channel ->
                        ChannelCardCompact(
                            channel = channel,
                            onClick = onChannelClick,
                            onFavoriteClick = {
                                ChannelRepository.toggleFavorite(context, channel.id)
                                refreshKey++
                            },
                            isFavorite = ChannelRepository.isFavorite(channel.id)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FullChannelListScreen(
    title: String,
    channels: List<Channel>,
    onChannelClick: (Channel) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Material3Text(
                text = "‹",
                fontSize = 32.sp,
                color = Color.White,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 16.dp)
            )
            Column {
                Material3Text(
                    text = title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Material3Text(
                    text = "${channels.size} channels",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        }
        
        // Grid of channels
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(channels) { channel ->
                ChannelListItem(channel, onChannelClick)
            }
        }
    }
}

@Composable
fun ChannelListItem(channel: Channel, onClick: (Channel) -> Unit) {
    Material3Card(
        onClick = { onClick(channel) },
        modifier = Modifier.fillMaxWidth(),
        colors = Material3CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo or icon
            if (channel.logo.isNotEmpty()) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = channel.name,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1565C0)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Channel info
            Column(modifier = Modifier.weight(1f)) {
                Material3Text(
                    text = channel.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (channel.category.isNotEmpty()) {
                    Material3Text(
                        text = channel.category,
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
            }
            
            // Arrow
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = Color(0xFF2196F3),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun ChannelCardCompact(
    channel: Channel,
    onClick: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    onFavoriteClick: () -> Unit = {},
    isFavorite: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    val cardWidth = if (isTv) 180.dp else 120.dp
    val cardHeight = if (isTv) 200.dp else 140.dp
    
    Material3Card(
        onClick = { onClick(channel) },
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            onClick(channel)
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .focusable(),
        shape = RoundedCornerShape(8.dp),
        colors = Material3CardDefaults.cardColors(
            containerColor = Color(0xFF2A2A2A)
        ),
        border = if (isFocused) BorderStroke(3.dp, Color(0xFFE50914)) else null
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Logo/Icon - always show placeholder behind
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        when {
                            channel.category.contains("sport", ignoreCase = true) -> Color(0xFF1B5E20)
                            channel.category.contains("news", ignoreCase = true) -> Color(0xFFB71C1C)
                            channel.category.contains("movie", ignoreCase = true) -> Color(0xFF4A148C)
                            channel.category.contains("kids", ignoreCase = true) -> Color(0xFFFF6F00)
                            channel.category.contains("music", ignoreCase = true) -> Color(0xFF00838F)
                            else -> Color(0xFF1565C0)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Show first letter of channel name as fallback
                Material3Text(
                    text = channel.name.take(2).uppercase(),
                    fontSize = if (isTv) 34.sp else 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
            
            // Logo image on top
            if (channel.logo.isNotEmpty()) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = channel.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            
            // Focus indicator overlay
            if (isFocused) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.1f))
                )
            }
            
            // Favorite heart icon (top-left) - clickable
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "Favorite",
                tint = if (isFavorite) Color(0xFFE50914) else Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(20.dp)
                    .clickable { onFavoriteClick() }
            )
            
            // Channel name overlay (bottom)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(if (isFocused) Color(0xFFE50914).copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            ) {
                Material3Text(
                    text = channel.name,
                    fontSize = if (isTv) 14.sp else 12.sp,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelCard(
    channel: Channel,
    onClick: () -> Unit,
    isTV: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    
    if (isTV) {
        // Use TV Material3 Card for TV
        Card(
            onClick = {
                android.util.Log.d("ChannelCard", "TV Card onClick triggered for: ${channel.name}")
                onClick()
            },
            modifier = Modifier
                .aspectRatio(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable(),
            shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
            colors = CardDefaults.colors(
                containerColor = if (isFocused) Color(0xFF3A3A3A) else Color(0xFF2A2A2A),
                focusedContainerColor = Color(0xFF3A3A3A)
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(width = 3.dp, color = Color(0xFF00BCD4))
                )
            ),
            scale = CardDefaults.scale(focusedScale = 1.05f)
        ) {
            ChannelCardContent(channel)
        }
    } else {
        // Use regular Material3 Card for smartphone
        Material3Card(
            onClick = {
                android.util.Log.d("ChannelCard", "Material3 Card onClick triggered for: ${channel.name}")
                onClick()
            },
            modifier = Modifier.aspectRatio(1f),
            shape = RoundedCornerShape(12.dp),
            colors = Material3CardDefaults.cardColors(
                containerColor = Color(0xFF2A2A2A)
            )
        ) {
            ChannelCardContent(channel)
        }
    }
}

@Composable
fun ChannelCardContent(channel: Channel) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Channel Logo/Thumbnail
        if (channel.logo.isNotEmpty()) {
            AsyncImage(
                model = channel.logo,
                contentDescription = channel.name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }
        
        // Overlay gradient and text
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Color.Black.copy(alpha = 0.5f),
                    RoundedCornerShape(12.dp)
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Category badge
            Text(
                text = channel.category,
                fontSize = 12.sp,
                color = Color(0xFF00BCD4),
                modifier = Modifier
                    .background(
                        Color(0xFF00BCD4).copy(alpha = 0.2f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            
            // Channel name
            Text(
                text = channel.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start
            )
        }
    }
}