package com.example.mediasessiontest.ui

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserUiView(
    roomCode: String,
    onRoomCodeChange: (String) -> Unit,
    isJoined: Boolean,
    onJoinChanged: (Boolean) -> Unit,
    currentPing: Long,
    coordinatorAddress: String,
    isHost: Boolean,
    mediaControllers: List<MediaController>,
    selectedController: MediaController?,
    onSelectController: (MediaController) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit
) {
    val darkBackground = Color(0xFF0F172A)
    val cardBackground = Color(0xFF1E293B)
    val accentColor = Color(0xFF6366F1)
    val emeraldColor = Color(0xFF10B981)
    val textPrimary = Color(0xFFF8FAFC)
    val textSecondary = Color(0xFF94A3B8)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBackground)
    ) {
        if (!isJoined) {
            // --- JOIN ROOM SCREEN ---
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF818CF8), Color(0xFFC084FC))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "LUMI SYNC",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 4.sp
                    ),
                    color = textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Synchronize media playback across all your devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(40.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBackground),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Enter Room Code",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = textPrimary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = roomCode,
                            onValueChange = onRoomCodeChange,
                            placeholder = { Text("LUMI-XXXX") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary,
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = Color(0xFF334155)
                            )
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { onJoinChanged(true) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                        ) {
                            Text(
                                "Join Room",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }
                }
            }
        } else {
            // --- ROOM SCREEN (isJoined == true) ---
            
            // Extract OS Media Thumbnail for Background
            val metadata = selectedController?.metadata
            val artBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

            if (artBitmap != null) {
                Image(
                    bitmap = artBitmap.asImageBitmap(),
                    contentDescription = "Media Background Artwork",
                    modifier = Modifier.fillMaxSize().alpha(0.12f),
                    contentScale = ContentScale.Crop
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "LUMI ROOM",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            ),
                            color = textPrimary
                        )
                        Text(
                            text = roomCode,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                            color = accentColor
                        )
                    }

                    Button(
                        onClick = { onJoinChanged(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Leave Room", color = Color.White)
                    }
                }

                // Stats Dashboard Row
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBackground.copy(alpha = 0.85f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("PING", style = MaterialTheme.typography.labelSmall, color = textSecondary)
                            Text("${currentPing} ms", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = emeraldColor)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("COORDINATOR", style = MaterialTheme.typography.labelSmall, color = textSecondary)
                            val hostIp = coordinatorAddress.substringAfter("://").substringBefore("/")
                            Text(hostIp, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Available Media Players list
                Text(
                    text = "Select Active Media Player:",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBackground.copy(alpha = 0.7f))
                ) {
                    if (mediaControllers.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No active media sessions detected.", color = textSecondary)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(mediaControllers) { controller ->
                                val isSelected = selectedController?.packageName == controller.packageName
                                val appName = controller.packageName.substringAfterLast(".")
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) accentColor.copy(alpha = 0.25f) else Color.Transparent)
                                        .clickable { onSelectController(controller) }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = if (isSelected) accentColor else textSecondary
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = appName.uppercase(),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isSelected) textPrimary else textSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- CONTROLLER CARD AND SEEKBAR ---
                if (selectedController != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBackground.copy(alpha = 0.9f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "No Title"
                            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"

                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Interactive Responsive Seekbar
                            val state = selectedController.playbackState
                            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                            
                            // Track local dragging position
                            var localSliderValue by remember { mutableStateOf(0f) }
                            var isDragging by remember { mutableStateOf(false) }

                            // Continuously query position to animate seekbar progress
                            var currentPlaybackPosition by remember { mutableStateOf(0L) }
                            LaunchedEffect(selectedController, state) {
                                while (true) {
                                    currentPlaybackPosition = selectedController.playbackState?.position ?: 0L
                                    if (!isDragging) {
                                        localSliderValue = currentPlaybackPosition.toFloat()
                                    }
                                    delay(500)
                                }
                            }

                            Slider(
                                value = localSliderValue,
                                onValueChange = {
                                    isDragging = true
                                    localSliderValue = it
                                },
                                onValueChangeFinished = {
                                    isDragging = false
                                    selectedController.transportControls.seekTo(localSliderValue.toLong())
                                },
                                valueRange = 0f..(if (duration > 0) duration.toFloat() else 100f),
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = accentColor,
                                    activeTrackColor = accentColor,
                                    inactiveTrackColor = Color(0xFF334155)
                                )
                            )

                            // Playback Timers
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formatDuration(localSliderValue.toLong()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textSecondary
                                )
                                Text(
                                    text = formatDuration(duration),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textSecondary
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Large responsive play/pause buttons
                            val isPlaying = state != null && state.state == PlaybackState.STATE_PLAYING
                             Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                             ) {
                                 IconButton(
                                     onClick = onSkipPrevious,
                                     modifier = Modifier.size(48.dp)
                                 ) {
                                     Icon(
                                         imageVector = Icons.Default.KeyboardArrowLeft,
                                         contentDescription = "Skip Previous",
                                         tint = textPrimary,
                                         modifier = Modifier.size(28.dp)
                                     )
                                 }

                                 Spacer(modifier = Modifier.width(8.dp))

                                 IconButton(
                                     onClick = { onSeekBy(-10000L) },
                                     modifier = Modifier.size(48.dp)
                                 ) {
                                     Icon(
                                         imageVector = Icons.Default.Refresh,
                                         contentDescription = "Rewind 10s",
                                         tint = textPrimary,
                                         modifier = Modifier.size(28.dp)
                                     )
                                 }

                                 Spacer(modifier = Modifier.width(12.dp))

                                 Box(
                                     modifier = Modifier
                                         .size(64.dp)
                                         .clip(CircleShape)
                                         .background(accentColor)
                                         .clickable {
                                             if (isPlaying) onPause() else onPlay()
                                         },
                                     contentAlignment = Alignment.Center
                                 ) {
                                     Icon(
                                         imageVector = if (isPlaying) Icons.Default.Menu
                                             else Icons.Default.PlayArrow,
                                         contentDescription = if (isPlaying) "Pause" else "Play",
                                         tint = Color.White,
                                         modifier = Modifier.size(32.dp)
                                     )
                                 }

                                 Spacer(modifier = Modifier.width(12.dp))

                                 IconButton(
                                     onClick = { onSeekBy(10000L) },
                                     modifier = Modifier.size(48.dp)
                                 ) {
                                     Icon(
                                         imageVector = Icons.Default.PlayArrow,
                                         contentDescription = "Forward 10s",
                                         tint = textPrimary,
                                         modifier = Modifier.size(28.dp)
                                     )
                                 }

                                 Spacer(modifier = Modifier.width(8.dp))

                                 IconButton(
                                     onClick = onSkipNext,
                                     modifier = Modifier.size(48.dp)
                                 ) {
                                     Icon(
                                         imageVector = Icons.Default.KeyboardArrowRight,
                                         contentDescription = "Skip Next",
                                         tint = textPrimary,
                                         modifier = Modifier.size(28.dp)
                                     )
                                 }
                             }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBackground.copy(alpha = 0.8f))
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("Please select a media player above to control.", color = textSecondary)
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}
