package com.sihiver.mqltv.ui.player

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.repository.ChannelRepository

/** Shared channel list overlay for both Exo and VLC players. */
data class PlayerChannelListNavState(
    val showChannelList: MutableState<Boolean>,
    val showSidebar: MutableState<Boolean>,
    val selectedCategory: MutableState<String>,
    val selectedListIndex: MutableState<Int>,
    val selectedSidebarIndex: MutableState<Int>,
)

fun handlePlayerChannelListKeyEvent(
    event: KeyEvent,
    currentChannelId: Int,
    nav: PlayerChannelListNavState,
    onPlayChannel: (Channel) -> Unit,
): Boolean {
    val keyCode = event.keyCode

    // When overlay is visible, consume DPAD_CENTER/ENTER on both DOWN+UP to prevent double actions.
    if (nav.showChannelList.value) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            23 -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val allChannels = ChannelRepository.getAllChannels()
                    val favoriteChannels = ChannelRepository.getFavorites()
                    val categories = allChannels
                        .map { it.category }
                        .distinct()
                        .filter { it.isNotEmpty() }

                    val sidebarItems = mutableListOf<String>().apply {
                        add("all")
                        add("favorites")
                        add("recent")
                        addAll(categories)
                    }

                    if (nav.showSidebar.value) {
                        if (nav.selectedSidebarIndex.value in sidebarItems.indices) {
                            nav.selectedCategory.value = sidebarItems[nav.selectedSidebarIndex.value]
                            nav.selectedListIndex.value = 0
                            nav.showSidebar.value = false
                        }
                    } else {
                        val filteredChannels = when (nav.selectedCategory.value) {
                            "all" -> allChannels
                            "favorites" -> favoriteChannels
                            "recent" -> allChannels.take(10)
                            else -> allChannels.filter { it.category == nav.selectedCategory.value }
                        }
                        if (nav.selectedListIndex.value in filteredChannels.indices) {
                            val selectedChannel = filteredChannels[nav.selectedListIndex.value]
                            nav.showChannelList.value = false
                            nav.showSidebar.value = false
                            onPlayChannel(selectedChannel)
                        }
                    }
                }
                return true
            }
        }
    }

    // Handle only ACTION_DOWN for other keys.
    if (event.action != KeyEvent.ACTION_DOWN) return false

    val allChannels = ChannelRepository.getAllChannels()
    val currentIndex = allChannels.indexOfFirst { it.id == currentChannelId }

    if (nav.showChannelList.value) {
        if (nav.showSidebar.value) {
            val categories = allChannels
                .map { it.category }
                .distinct()
                .filter { it.isNotEmpty() }
            val sidebarCount = 3 + categories.size // all, favorites, recent + categories

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (nav.selectedSidebarIndex.value > 0) nav.selectedSidebarIndex.value -= 1
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (nav.selectedSidebarIndex.value < sidebarCount - 1) nav.selectedSidebarIndex.value += 1
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    nav.showSidebar.value = false
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    nav.showSidebar.value = false
                    return true
                }
            }
        } else {
            val favoriteChannels = ChannelRepository.getFavorites()
            val filteredChannels = when (nav.selectedCategory.value) {
                "all" -> allChannels
                "favorites" -> favoriteChannels
                "recent" -> allChannels.take(10)
                else -> allChannels.filter { it.category == nav.selectedCategory.value }
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (nav.selectedListIndex.value > 0) nav.selectedListIndex.value -= 1
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (nav.selectedListIndex.value < filteredChannels.size - 1) nav.selectedListIndex.value += 1
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    nav.showSidebar.value = true
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    nav.showChannelList.value = false
                    nav.showSidebar.value = false
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    nav.showChannelList.value = false
                    nav.showSidebar.value = false
                    return true
                }
            }
        }
    } else {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (currentIndex > 0) onPlayChannel(allChannels[currentIndex - 1])
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (currentIndex >= 0 && currentIndex < allChannels.size - 1) onPlayChannel(allChannels[currentIndex + 1])
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_TAB,
            23 -> {
                nav.selectedListIndex.value = if (currentIndex >= 0) currentIndex else 0
                nav.selectedCategory.value = "all"
                nav.selectedSidebarIndex.value = 0
                nav.showSidebar.value = false
                nav.showChannelList.value = true
                return true
            }
        }
    }

    return false
}

@Composable
fun PlayerChannelListOverlay(
    nav: PlayerChannelListNavState,
    currentChannelId: Int,
    onPlayChannel: (Channel) -> Unit,
) {
    val allChannels: List<Channel> = remember { ChannelRepository.getAllChannels() }
    val favoriteChannels: List<Channel> = remember { ChannelRepository.getFavorites() }
    val categories: List<String> = remember {
        val base = allChannels
            .map { ch -> ch.category }
            .distinct()
            .filter { cat -> cat.isNotEmpty() }

        val eventCategory = base.firstOrNull { it.trim().equals("event", ignoreCase = true) }
        if (eventCategory == null) base else listOf(eventCategory) + base.filterNot { it == eventCategory }
    }

    val filteredChannels: List<Channel> = remember(nav.selectedCategory.value) {
        when (nav.selectedCategory.value) {
            "all" -> allChannels
            "favorites" -> favoriteChannels
            "recent" -> allChannels.take(10)
            else -> allChannels.filter { ch -> ch.category == nav.selectedCategory.value }
        }
    }

    val listState = rememberLazyListState()
    val sidebarListState = rememberLazyListState()

    val sidebarItems = remember(categories) {
        mutableListOf<Pair<String, String>>().apply {
            add("all" to "Semua Channel")
            add("favorites" to "Favorit")
            add("recent" to "Terakhir Ditonton")
            categories.forEach { cat ->
                add(
                    cat to when {
                        cat.trim().equals("event", ignoreCase = true) -> "EVENTS"
                        cat.trim().equals("movie", ignoreCase = true) -> "MOVIES"
                        else -> cat
                    }
                )
            }
        }
    }

    LaunchedEffect(nav.showChannelList.value, nav.selectedListIndex.value, nav.selectedCategory.value) {
        if (nav.showChannelList.value && nav.selectedListIndex.value >= 0 && nav.selectedListIndex.value < filteredChannels.size) {
            listState.animateScrollToItem(maxOf(0, nav.selectedListIndex.value - 2))
        }
    }

    if (!nav.showChannelList.value) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier
                    .width(if (nav.showSidebar.value) 550.dp else 380.dp)
                    .fillMaxHeight()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.9f))
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (nav.showSidebar.value) {
                        Column(
                            modifier = Modifier
                                .width(170.dp)
                                .fillMaxHeight()
                                .background(Color(0xFF252525))
                        ) {
                            LazyColumn(state = sidebarListState, modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(sidebarItems) { index, (key, label) ->
                                    val isSelected = nav.selectedCategory.value == key
                                    val isSidebarSelected = index == nav.selectedSidebarIndex.value

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                when {
                                                    isSidebarSelected -> Color(0xFF424242)
                                                    isSelected -> Color(0xFF1976D2)
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .clickable {
                                                nav.selectedCategory.value = key
                                                nav.selectedListIndex.value = 0
                                                nav.selectedSidebarIndex.value = index
                                                nav.showSidebar.value = false
                                            }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = when (key) {
                                                "all" -> Icons.AutoMirrored.Filled.List
                                                "favorites" -> Icons.Filled.Favorite
                                                "recent" -> Icons.Filled.History
                                                else -> Icons.Filled.PlayArrow
                                            },
                                            contentDescription = null,
                                            tint = if (isSelected) Color.White else Color.Gray,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = label,
                                            color = if (isSelected) Color.White else Color.Gray,
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(Color.Gray.copy(alpha = 0.3f))
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { nav.showSidebar.value = !nav.showSidebar.value }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = when (nav.selectedCategory.value) {
                                        "all" -> Icons.AutoMirrored.Filled.List
                                        "favorites" -> Icons.Filled.Favorite
                                        "recent" -> Icons.Filled.History
                                        else -> Icons.Filled.PlayArrow
                                    },
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when (nav.selectedCategory.value) {
                                        "all" -> "Semua Channel"
                                        "favorites" -> "Favorit"
                                        "recent" -> "Terakhir Ditonton"
                                        else -> nav.selectedCategory.value
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = " (${filteredChannels.size})",
                                    color = Color.Gray,
                                    fontSize = 14.sp
                                )
                                Icon(
                                    imageVector = if (nav.showSidebar.value) Icons.Default.KeyboardArrowLeft else Icons.Default.KeyboardArrowRight,
                                    contentDescription = "Toggle sidebar",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    nav.showChannelList.value = false
                                    nav.showSidebar.value = false
                                }
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }

                        Divider(color = Color.Gray.copy(alpha = 0.3f))

                        if (filteredChannels.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(text = "Tidak ada channel", color = Color.Gray, fontSize = 14.sp)
                            }
                        } else {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(filteredChannels) { index, item ->
                                    val isCurrentChannel = item.id == currentChannelId
                                    val isSelected = index == nav.selectedListIndex.value
                                    val isFavorite = favoriteChannels.any { fav -> fav.id == item.id }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                when {
                                                    isSelected && isCurrentChannel -> Color(0xFF1976D2)
                                                    isSelected -> Color(0xFF424242)
                                                    isCurrentChannel -> Color(0xFF2196F3)
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .clickable {
                                                nav.selectedListIndex.value = index
                                                nav.showChannelList.value = false
                                                nav.showSidebar.value = false
                                                onPlayChannel(item)
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = (index + 1).toString(),
                                            color = if (isSelected) Color.White else Color.Gray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.width(30.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.name,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                maxLines = 1
                                            )
                                            if (item.category.isNotEmpty() && nav.selectedCategory.value == "all") {
                                                Text(
                                                    text = item.category,
                                                    color = Color.Gray,
                                                    fontSize = 11.sp,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                        if (isFavorite) {
                                            Icon(
                                                imageVector = Icons.Filled.Favorite,
                                                contentDescription = null,
                                                tint = Color.Red,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        if (isCurrentChannel) {
                                            Text(text = "▶", color = Color.White, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable {
                        nav.showChannelList.value = false
                        nav.showSidebar.value = false
                    }
            )
        }
    }
}
