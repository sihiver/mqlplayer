package com.sihiver.mqltv

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.repository.ChannelRepository
import com.sihiver.mqltv.ui.theme.MQLTVTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddChannelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MQLTVTheme {
                AddChannelScreen(
                    onChannelAdded = {
                        ChannelRepository.saveChannels(this)
                        finish()
                    },
                    onCancel = {
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChannelScreen(
    onChannelAdded: () -> Unit,
    onCancel: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Manual", "Import M3U")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Text(
                    text = "Add Channel",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF2A2A2A)
            )
        )
        
        // Tab Row
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF2A2A2A),
            contentColor = Color(0xFF00BCD4)
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            color = if (selectedTab == index) Color(0xFF00BCD4) else Color.Gray
                        )
                    }
                )
            }
        }
        
        // Content
        when (selectedTab) {
            0 -> ManualAddChannel(onChannelAdded, onCancel)
            1 -> ImportM3UScreen(onChannelAdded, onCancel)
        }
    }
}

@Composable
fun ManualAddChannel(
    onChannelAdded: () -> Unit,
    onCancel: () -> Unit
) {
    var channelName by remember { mutableStateOf("") }
    var channelUrl by remember { mutableStateOf("") }
    var channelCategory by remember { mutableStateOf("Custom") }
    var channelLogo by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Add Channel Manually",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        // Channel Name
        OutlinedTextField(
            value = channelName,
            onValueChange = { channelName = it },
            label = { Text("Channel Name", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF00BCD4),
                unfocusedBorderColor = Color.Gray
            )
        )
        
        // Channel URL
        OutlinedTextField(
            value = channelUrl,
            onValueChange = { channelUrl = it },
            label = { Text("Stream URL (HLS/m3u8)", color = Color.Gray) },
            placeholder = { Text("http://192.168.1.100:8080/stream.m3u8", color = Color.DarkGray) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF00BCD4),
                unfocusedBorderColor = Color.Gray
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        
        // Category
        OutlinedTextField(
            value = channelCategory,
            onValueChange = { channelCategory = it },
            label = { Text("Category", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF00BCD4),
                unfocusedBorderColor = Color.Gray
            )
        )
        
        // Logo URL (Optional)
        OutlinedTextField(
            value = channelLogo,
            onValueChange = { channelLogo = it },
            label = { Text("Logo URL (Optional)", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF00BCD4),
                unfocusedBorderColor = Color.Gray
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Gray
                )
            ) {
                Text("Cancel")
            }
            
            Button(
                onClick = {
                    if (channelName.isNotBlank() && channelUrl.isNotBlank()) {
                        val channel = Channel(
                            id = 0, // Will be assigned by repository
                            name = channelName,
                            url = channelUrl,
                            logo = channelLogo,
                            category = channelCategory
                        )
                        ChannelRepository.addChannel(channel)
                        onChannelAdded()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = channelName.isNotBlank() && channelUrl.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00BCD4)
                )
            ) {
                Text("Add Channel")
            }
        }
    }
}

@Composable
fun ImportM3UScreen(
    onChannelAdded: () -> Unit,
    onCancel: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var m3uUrl by remember { mutableStateOf("") }
    var m3uContent by remember { mutableStateOf("") }
    var pickedFileUri by remember { mutableStateOf<Uri?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var importMethod by remember { mutableStateOf("url") } // "url" | "paste" | "file"
    val scope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                pickedFileUri = uri
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Ignore if persist permission not granted
                }
            }
        }
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Import M3U Playlist",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        // Method Selection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { importMethod = "url" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (importMethod == "url") Color(0xFF00BCD4) else Color.Gray
                )
            ) {
                Text("From URL")
            }
            
            Button(
                onClick = { importMethod = "paste" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (importMethod == "paste") Color(0xFF00BCD4) else Color.Gray
                )
            ) {
                Text("Paste Content")
            }

            Button(
                onClick = { importMethod = "file" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (importMethod == "file") Color(0xFF00BCD4) else Color.Gray
                )
            ) {
                Text("From File")
            }
        }
        
        if (importMethod == "url") {
            // M3U URL
            OutlinedTextField(
                value = m3uUrl,
                onValueChange = { m3uUrl = it },
                label = { Text("M3U URL", color = Color.Gray) },
                placeholder = { Text("http://example.com/playlist.m3u", color = Color.DarkGray) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00BCD4),
                    unfocusedBorderColor = Color.Gray
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        } else if (importMethod == "paste") {
            // M3U Content
            OutlinedTextField(
                value = m3uContent,
                onValueChange = { m3uContent = it },
                label = { Text("Paste M3U Content", color = Color.Gray) },
                placeholder = { Text("#EXTM3U\n#EXTINF:-1,Channel Name\nhttp://...", color = Color.DarkGray) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00BCD4),
                    unfocusedBorderColor = Color.Gray
                ),
                maxLines = 10
            )
        } else {
            // From File
            Button(
                onClick = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "application/x-mpegURL",
                            "application/vnd.apple.mpegurl",
                            "audio/x-mpegurl",
                            "text/plain",
                            "*/*"
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00BCD4)
                )
            ) {
                Text(if (pickedFileUri == null) "Choose .m3u/.m3u8 file" else "Change file")
            }

            if (pickedFileUri != null) {
                Text(
                    text = "Selected: ${pickedFileUri}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }
        
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = Color(0xFF00BCD4)
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Gray
                )
            ) {
                Text("Cancel")
            }
            
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        try {
                            val count = when (importMethod) {
                                "url" -> {
                                    // Save playlist URL for auto-refresh
                                    ChannelRepository.addPlaylistUrl(context, m3uUrl)
                                    ChannelRepository.importFromM3UUrl(m3uUrl)
                                }
                                "paste" -> {
                                    ChannelRepository.importFromM3U(m3uContent)
                                }
                                else -> {
                                    val uri = pickedFileUri
                                    if (uri == null) {
                                        0
                                    } else {
                                        val content = withContext(Dispatchers.IO) {
                                            context.contentResolver.openInputStream(uri)
                                                ?.bufferedReader()
                                                ?.use { it.readText() }
                                                .orEmpty()
                                        }
                                        ChannelRepository.importFromM3U(content, source = uri.toString())
                                    }
                                }
                            }
                            
                            android.util.Log.d("AddChannelActivity", "Import completed: $count channels added")
                            
                            if (count > 0) {
                                // Force save channels immediately
                                ChannelRepository.saveChannels(context)
                                android.util.Log.d("AddChannelActivity", "Channels saved to SharedPreferences")
                                
                                if (importMethod == "url") {
                                    android.util.Log.d("AddChannelActivity", "Playlist URL saved for auto-refresh: $m3uUrl")
                                }
                                
                                // Show toast
                                android.widget.Toast.makeText(
                                    context,
                                    "$count channels imported successfully",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                
                                onChannelAdded()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "No channels found in M3U",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            android.widget.Toast.makeText(
                                context,
                                "Import gagal: ${e.message ?: "unknown error"}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isLoading && (
                    (importMethod == "url" && m3uUrl.isNotBlank()) ||
                    (importMethod == "paste" && m3uContent.isNotBlank()) ||
                    (importMethod == "file" && pickedFileUri != null)
                ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00BCD4)
                )
            ) {
                Text("Import")
            }
        }
    }
}
