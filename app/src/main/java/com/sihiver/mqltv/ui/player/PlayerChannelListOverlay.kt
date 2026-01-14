package com.sihiver.mqltv.ui.player

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sihiver.mqltv.model.Channel
import com.sihiver.mqltv.repository.ChannelRepository

/** Shared channel list overlay for both Exo and VLC players. */
data class PlayerChannelListNavState(
    val showChannelList: MutableState<Boolean>,
    val headerSelected: MutableState<Boolean>,
    /** -1: moved left, +1: moved right, 0: none */
    val categoryNavDirection: MutableState<Int>,
    val selectedCategory: MutableState<String>,
    val selectedListIndex: MutableState<Int>,
)

private fun buildCategoryKeys(allChannels: List<Channel>): List<String> {
    val categories = allChannels
        .map { it.category }
        .distinct()
        .filter { it.isNotEmpty() }

    return buildList {
        add("all")
        add("favorites")
        add("recent")
        addAll(categories)
    }
}

private fun categoryLabel(key: String): String {
    return when {
        key == "all" -> "Semua Channel"
        key == "favorites" -> "Favorit"
        key == "recent" -> "Terakhir Ditonton"
        key.trim().equals("event", ignoreCase = true) -> "EVENTS"
        key.trim().equals("movie", ignoreCase = true) -> "MOVIES"
        else -> key
    }
}

fun handlePlayerChannelListKeyEvent(
    event: KeyEvent,
    currentChannelId: Int,
    nav: PlayerChannelListNavState,
    onPlayChannel: (Channel) -> Unit,
): Boolean {
    val keyCode = event.keyCode

    fun cycleCategory(delta: Int) {
        val allChannels = ChannelRepository.getAllChannels()
        val keys = buildCategoryKeys(allChannels)
        if (keys.isEmpty()) return

        nav.categoryNavDirection.value = when {
            delta < 0 -> -1
            delta > 0 -> 1
            else -> 0
        }

        val current = nav.selectedCategory.value
        val currentIndex = keys.indexOf(current).let { if (it >= 0) it else 0 }
        val newIndex = (currentIndex + delta).mod(keys.size)
        nav.selectedCategory.value = keys[newIndex]
        nav.selectedListIndex.value = 0
    }

    // When overlay is visible, consume DPAD_CENTER/ENTER on both DOWN+UP to prevent double actions.
    if (nav.showChannelList.value) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            23 -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    // Header is "selected" -> OK does nothing (prevents accidental play)
                    if (nav.headerSelected.value) {
                        return true
                    }

                    val allChannels = ChannelRepository.getAllChannels()
                    val favoriteChannels = ChannelRepository.getFavorites()
                    val filteredChannels = when (nav.selectedCategory.value) {
                        "all" -> allChannels
                        "favorites" -> favoriteChannels
                        "recent" -> allChannels.take(10)
                        else -> allChannels.filter { it.category == nav.selectedCategory.value }
                    }
                    if (nav.selectedListIndex.value in filteredChannels.indices) {
                        val selectedChannel = filteredChannels[nav.selectedListIndex.value]
                        nav.showChannelList.value = false
                        nav.headerSelected.value = false
                        nav.categoryNavDirection.value = 0
                        onPlayChannel(selectedChannel)
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
        val favoriteChannels = ChannelRepository.getFavorites()
        val filteredChannels = when (nav.selectedCategory.value) {
            "all" -> allChannels
            "favorites" -> favoriteChannels
            "recent" -> allChannels.take(10)
            else -> allChannels.filter { it.category == nav.selectedCategory.value }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (nav.headerSelected.value) cycleCategory(delta = -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (nav.headerSelected.value) cycleCategory(delta = +1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (nav.headerSelected.value) {
                    return true
                }

                // Move focus to header when at top of list
                if (nav.selectedListIndex.value <= 0) {
                    nav.headerSelected.value = true
                    return true
                }

                nav.selectedListIndex.value -= 1
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (nav.headerSelected.value) {
                    nav.headerSelected.value = false
                    return true
                }

                if (nav.selectedListIndex.value < filteredChannels.size - 1) nav.selectedListIndex.value += 1
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                nav.showChannelList.value = false
                nav.headerSelected.value = false
                nav.categoryNavDirection.value = 0
                return true
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
                nav.headerSelected.value = false
                nav.categoryNavDirection.value = 0
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
    val categoryKeys: List<String> = remember { buildCategoryKeys(allChannels) }

    val filteredChannels: List<Channel> = remember(nav.selectedCategory.value) {
        when (nav.selectedCategory.value) {
            "all" -> allChannels
            "favorites" -> favoriteChannels
            "recent" -> allChannels.take(10)
            else -> allChannels.filter { ch -> ch.category == nav.selectedCategory.value }
        }
    }

    val listState = rememberLazyListState()
    val selectedCategoryIndex = remember(nav.selectedCategory.value, categoryKeys) {
        categoryKeys.indexOf(nav.selectedCategory.value).let { if (it >= 0) it else 0 }
    }

    // Pulse animation when changing category via LEFT/RIGHT while header is selected.
    val leftPulse = remember { Animatable(1f) }
    val rightPulse = remember { Animatable(1f) }
    val lastCategory = remember { mutableStateOf(nav.selectedCategory.value) }
    val pulseInitialized = remember { mutableStateOf(false) }

    LaunchedEffect(nav.selectedCategory.value, nav.headerSelected.value) {
        val prev = lastCategory.value
        val current = nav.selectedCategory.value
        val changed = prev != current
        lastCategory.value = current

        if (!pulseInitialized.value) {
            pulseInitialized.value = true
            return@LaunchedEffect
        }

        if (nav.headerSelected.value && changed) {
            when (nav.categoryNavDirection.value) {
                -1 -> {
                    leftPulse.snapTo(1f)
                    leftPulse.animateTo(1.15f, tween(durationMillis = 90))
                    leftPulse.animateTo(1f, tween(durationMillis = 160))
                }
                1 -> {
                    rightPulse.snapTo(1f)
                    rightPulse.animateTo(1.15f, tween(durationMillis = 90))
                    rightPulse.animateTo(1f, tween(durationMillis = 160))
                }
            }

            // Clear direction after anim so non-dpad changes won't wrongly pulse.
            nav.categoryNavDirection.value = 0
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
                    .width(420.dp)
                    .fillMaxHeight()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.9f))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .background(
                                if (nav.headerSelected.value) Color(0xFF1976D2).copy(alpha = 0.25f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val baseArrowSize = if (nav.headerSelected.value) 26f else 22f
                        val leftSize = (baseArrowSize * if (nav.headerSelected.value) leftPulse.value else 1f).dp
                        val rightSize = (baseArrowSize * if (nav.headerSelected.value) rightPulse.value else 1f).dp

                        IconButton(
                            onClick = {
                                if (categoryKeys.isNotEmpty()) {
                                    nav.categoryNavDirection.value = -1
                                    val prev = (selectedCategoryIndex - 1).mod(categoryKeys.size)
                                    nav.selectedCategory.value = categoryKeys[prev]
                                    nav.selectedListIndex.value = 0
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowLeft,
                                contentDescription = "Prev category",
                                tint = if (nav.headerSelected.value) Color.White else Color.Gray,
                                modifier = Modifier
                                    .background(
                                        if (nav.headerSelected.value) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                                        CircleShape
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (nav.headerSelected.value) Color.White.copy(alpha = 0.55f) else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .padding(2.dp)
                                    .size(leftSize)
                            )
                        }

                        Text(
                            text = categoryLabel(nav.selectedCategory.value),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = {
                                if (categoryKeys.isNotEmpty()) {
                                    nav.categoryNavDirection.value = 1
                                    val next = (selectedCategoryIndex + 1).mod(categoryKeys.size)
                                    nav.selectedCategory.value = categoryKeys[next]
                                    nav.selectedListIndex.value = 0
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowRight,
                                contentDescription = "Next category",
                                tint = if (nav.headerSelected.value) Color.White else Color.Gray,
                                modifier = Modifier
                                    .background(
                                        if (nav.headerSelected.value) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                                        CircleShape
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (nav.headerSelected.value) Color.White.copy(alpha = 0.55f) else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .padding(2.dp)
                                    .size(rightSize)
                            )
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
                                val isSelected = !nav.headerSelected.value && index == nav.selectedListIndex.value
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

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable {
                        nav.showChannelList.value = false
                    }
            )
        }
    }
}
