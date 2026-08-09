package com.example.mediasessiontest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mediasessiontest.theme.MediaSessionTestTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var mediaSessionManager: MediaSessionManager
    private var activeControllers by mutableStateOf<List<MediaController>>(emptyList())
    private var selectedController by mutableStateOf<MediaController?>(null)
    private var networkClient: LumiNetworkClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

        setContent {
            MediaSessionTestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSessions()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkClient?.stop()
    }

    private fun refreshSessions() {
        try {
            val componentName = ComponentName(this, LumiNotificationListenerService::class.java)
            activeControllers = mediaSessionManager.getActiveSessions(componentName)
        } catch (e: SecurityException) {
            // Permission not granted
            activeControllers = emptyList()
        }
    }

    @Composable
    fun MainScreen() {
        var hasPermission by remember { mutableStateOf(false) }
        var roomCode by remember { mutableStateOf("") }
        var coordinatorAddress by remember { mutableStateOf("127.0.0.1:4000") }
        var networkStatus by remember { mutableStateOf("Idle") }
        val coroutineScope = rememberCoroutineScope()

        var currentCallback by remember { mutableStateOf<MediaController.Callback?>(null) }

        // Check permission by attempting to fetch sessions
        LaunchedEffect(activeControllers) {
            hasPermission = try {
                val componentName = ComponentName(this@MainActivity, LumiNotificationListenerService::class.java)
                mediaSessionManager.getActiveSessions(componentName)
                true
            } catch (e: SecurityException) {
                false
            }
        }

        // Initialize and connect WebSocket client
        LaunchedEffect(coordinatorAddress, roomCode) {
            networkClient?.stop()
            if (coordinatorAddress.isNotBlank() && roomCode.isNotBlank()) {
                networkClient = LumiNetworkClient(
                    coordinatorAddress,
                    roomCode,
                    { status -> networkStatus = status }
                ) { eventType, position ->
                    when (eventType) {
                        "PLAY" -> selectedController?.transportControls?.play()
                        "PAUSE" -> selectedController?.transportControls?.pause()
                        "SEEK" -> selectedController?.transportControls?.seekTo((position * 1000).toLong())
                    }
                    Toast.makeText(this@MainActivity, "Received $eventType", Toast.LENGTH_SHORT).show()
                }
                networkClient?.start()
            }
        }

        // Handle auto-broadcasting of local OS media session changes
        LaunchedEffect(selectedController, networkClient) {
            currentCallback?.let { selectedController?.unregisterCallback(it) }
            val callback = object : MediaController.Callback() {
                private var lastStatePosition = -1L
                private var lastStateUpdateTime = 0L

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    state?.let {
                        // Check for Play/Pause changes
                        val eventType = when (it.state) {
                            PlaybackState.STATE_PLAYING -> "PLAY"
                            PlaybackState.STATE_PAUSED -> "PAUSE"
                            else -> null
                        }

                        // Check for Seek changes
                        val currentPos = it.position
                        val now = android.os.SystemClock.elapsedRealtime()
                        var isManualSeek = false

                        if (lastStatePosition != -1L) {
                            val elapsed = now - lastStateUpdateTime
                            // Expected position if playing, otherwise static
                            val speed = if (it.state == PlaybackState.STATE_PLAYING) it.playbackSpeed else 0f
                            val expectedPos = lastStatePosition + (elapsed * speed).toLong()
                            val diff = Math.abs(currentPos - expectedPos)
                            if (diff > 2500) { // 2.5 seconds threshold to identify manual seeks
                                isManualSeek = true
                            }
                        }
                        
                        lastStatePosition = currentPos
                        lastStateUpdateTime = now

                        if (isManualSeek) {
                            coroutineScope.launch {
                                Log.d("LumiNetwork", "Broadcasting local phone seek: ${currentPos / 1000.0}")
                                networkClient?.sendEvent("SEEK", currentPos / 1000.0)
                            }
                        } else if (eventType != null) {
                            // Deduplicate loops if this was triggered by a recent network command
                            val client = networkClient
                            if (client != null) {
                                val elapsed = System.currentTimeMillis() - client.lastReceivedEventTime
                                if (eventType == client.lastReceivedEventType && elapsed < 2000) {
                                    Log.d("LumiNetwork", "Ignoring echo event: $eventType")
                                    return
                                }
                            }
                            coroutineScope.launch {
                                Log.d("LumiNetwork", "Broadcasting local OS media event: $eventType")
                                networkClient?.sendEvent(eventType, currentPos / 1000.0)
                            }
                        }
                    }
                }
            }
            selectedController?.registerCallback(callback)
            currentCallback = callback
        }

        if (!hasPermission) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Notification Access Required", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))
                Text("To list active media sessions, the app needs Notification Access.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) {
                    Text("Grant Permission")
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp)
            ) {
                // Network status
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Network Status:", style = MaterialTheme.typography.titleSmall)
                        Text(networkStatus, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Network config
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = coordinatorAddress,
                        onValueChange = { coordinatorAddress = it },
                        label = { Text("Coordinator WebSocket Address (IP:PORT)") },
                        modifier = Modifier.weight(1.5f)
                    )
                    OutlinedTextField(
                        value = roomCode,
                        onValueChange = { roomCode = it },
                        label = { Text("Room Code") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Active Media Sessions", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { refreshSessions() }) {
                        Text("Refresh")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (activeControllers.isEmpty()) {
                    Text("No active media sessions found.")
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(activeControllers) { controller ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedController = controller }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (selectedController == controller),
                                    onClick = { selectedController = controller }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(controller.packageName, style = MaterialTheme.typography.bodyLarge)
                                    val metadata = controller.metadata
                                    val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Title"
                                    val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
                                    Text("$title - $artist", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { 
                            selectedController?.transportControls?.play() 
                            coroutineScope.launch {
                                networkClient?.sendEvent("PLAY", 0.0)
                            }
                        },
                        enabled = selectedController != null
                    ) {
                        Text("Play")
                    }
                    Button(
                        onClick = { 
                            selectedController?.transportControls?.pause() 
                            coroutineScope.launch {
                                networkClient?.sendEvent("PAUSE", 0.0)
                            }
                        },
                        enabled = selectedController != null
                    ) {
                        Text("Pause")
                    }
                }
            }
        }
    }
}
