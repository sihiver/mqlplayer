package com.sihiver.mqltv

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import coil.compose.AsyncImage
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.repository.ChannelRepository
import com.sihiver.mqltv.ui.theme.MQLTVTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load saved channels
        ChannelRepository.loadChannels(this)
        
        setContent {
            MQLTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    ChannelListScreen(
                        onChannelClick = { channel ->
                            try {
                                android.util.Log.d("MainActivity", "Channel clicked: ${channel.name} (ID: ${channel.id}, URL: ${channel.url})")
                                
                                val intent = Intent(this@MainActivity, PlayerActivity::class.java)
                                intent.putExtra("CHANNEL_ID", channel.id)
                                
                                android.util.Log.d("MainActivity", "Starting PlayerActivity with channel ID: ${channel.id}")
                                startActivity(intent)
                                
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Error starting PlayerActivity", e)
                                android.widget.Toast.makeText(
                                    this@MainActivity, 
                                    "Error: ${e.message}", 
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        onAddChannelClick = {
                            val intent = Intent(this@MainActivity, AddChannelActivity::class.java)
                            startActivity(intent)
                        }
                    )
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelListScreen(
    onChannelClick: (Channel) -> Unit,
    onAddChannelClick: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTV = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    
    // Load channels on first composition
    DisposableEffect(Unit) {
        ChannelRepository.loadChannels(context)
        android.util.Log.d("MainActivity", "Channels loaded in DisposableEffect")
        onDispose { }
    }
    
    var channels by remember { mutableStateOf(ChannelRepository.getAllChannels()) }
    var refreshKey by remember { mutableStateOf(0) }
    
    // Listen to lifecycle events to refresh when returning from AddChannelActivity
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                android.util.Log.d("MainActivity", "Activity resumed, refreshing channels")
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Refresh channels whenever refreshKey changes
    LaunchedEffect(refreshKey) {
        ChannelRepository.loadChannels(context)
        channels = ChannelRepository.getAllChannels()
        android.util.Log.d("MainActivity", "Loaded ${channels.size} channels:")
        channels.forEach { channel ->
            android.util.Log.d("MainActivity", "  - ${channel.name} (ID: ${channel.id})")
        }
    }
    
    // Determine grid columns based on device type and orientation
    val gridColumns = if (isTV) {
        4
    } else {
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2
    }
    
    val contentPadding = if (isTV) 32.dp else 16.dp
    val headerFontSize = if (isTV) 36.sp else 24.sp
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(contentPadding)
    ) {
        // Header with Add button and Clear button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "IPTV Channels",
                fontSize = headerFontSize,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clear Playlist Button
                Box(
                    modifier = Modifier
                        .height(if (isTV) 56.dp else 48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFFFF5252))
                        .clickable { 
                            // Clear all channels
                            ChannelRepository.clearAllChannels(context)
                            refreshKey++
                            android.widget.Toast.makeText(
                                context,
                                "Playlist cleared",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Clear",
                        fontSize = if (isTV) 18.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                
                // Add Channel Button
                Box(
                    modifier = Modifier
                        .size(if (isTV) 56.dp else 48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00BCD4))
                        .clickable { onAddChannelClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        fontSize = if (isTV) 32.sp else 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
        
        // Channel Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(channels) { channel ->
                ChannelCard(
                    channel = channel,
                    onClick = { 
                        android.util.Log.d("MainActivity", "ChannelCard clicked: ${channel.name} (ID: ${channel.id})")
                        onChannelClick(channel) 
                    },
                    isTV = isTV
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