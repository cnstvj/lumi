package com.example.mediasessiontest.ui

import android.media.session.MediaController
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediasessiontest.data.SyncStatus
import com.example.mediasessiontest.data.TelemetryState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserUiView(
    coordinatorAddress: String,
    onCoordinatorAddressChange: (String) -> Unit,
    roomCode: String,
    onRoomCodeChange: (String) -> Unit,
    mediaControllers: List<MediaController>,
    selectedController: MediaController?,
    onSelectController: (MediaController) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    telemetryState: TelemetryState,
    onToggleMode: () -> Unit
) {
    val darkBackground = Color(0xFF0F172A)
    val cardBackground = Color(0xFF1E293B)
    val accentColor = Color(0xFF6366F1)
    val emeraldColor = Color(0xFF10B981)
    val amberColor = Color(0xFBF59E0B)
    val textPrimary = Color(0xFFF8FAFC)
    val textSecondary = Color(0xFF94A3B8)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBackground)
            .padding(16.dp)
    ) {
        // --- Top Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF818CF8), Color(0xFFC084FC))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "LUMI",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    ),
                    color = textPrimary
                )
            }

            // Mode Selector Pill
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onToggleMode() },
                color = cardBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "User Mode",
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "USER VIEW",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = textPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Switch",
                        tint = textSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- Room Connection Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBackground),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sync Room",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = textPrimary
                    )

                    // Sync Status Badge
                    val (statusColor, statusText) = when (telemetryState.syncStatus) {
                        SyncStatus.SYNCED -> Pair(emeraldColor, "Synced")
                        SyncStatus.CORRECTING_DRIFT -> Pair(amberColor, "Syncing...")
                        SyncStatus.CONNECTING -> Pair(Color(0xFF3B82F6), "Connecting...")
                        SyncStatus.DISCONNECTED -> Pair(Color(0xFFEF4444), "Offline")
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(statusColor.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = statusColor
                        )
                        if (telemetryState.currentRttMs > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "⚡ ${telemetryState.currentRttMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = textSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = coordinatorAddress,
                    onValueChange = onCoordinatorAddressChange,
                    label = { Text("Server URL", color = textSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = roomCode,
                    onValueChange = onRoomCodeChange,
                    label = { Text("Room Code", color = textSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Active Media Player Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBackground),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Active Media Source",
                    style = MaterialTheme.typography.labelLarge,
                    color = textSecondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (mediaControllers.isEmpty()) {
                    Text(
                        text = "No active media session found. Open YouTube, VLC, or Spotify.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF59E0B),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    val currentTitle = selectedController?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Track"
                    val currentArtist = selectedController?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: selectedController?.packageName ?: "Unknown Artist"

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { expanded = true },
                            color = Color(0xFF0F172A),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = currentTitle,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                        color = textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = currentArtist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select App",
                                    tint = textSecondary
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            mediaControllers.forEach { controller ->
                                val title = controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: controller.packageName
                                DropdownMenuItem(
                                    text = { Text(title) },
                                    onClick = {
                                        onSelectController(controller)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Media Control Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onSeekBy(-10000L) },
                            enabled = selectedController != null,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                        ) {
                            Text("-10s", color = textPrimary, fontWeight = FontWeight.Bold)
                        }

                        val isPlaying = selectedController?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                        FloatingActionButton(
                            onClick = {
                                if (isPlaying) onPause() else onPlay()
                            },
                            containerColor = accentColor,
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Button(
                            onClick = { onSeekBy(10000L) },
                            enabled = selectedController != null,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                        ) {
                            Text("+10s", color = textPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
