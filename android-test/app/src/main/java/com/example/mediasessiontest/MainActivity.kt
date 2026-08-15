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
        var roomCode by remember { mutableStateOf(Constants.DEFAULT_ROOM_CODE) }
        val coordinatorAddress = Constants.DEFAULT_COORDINATOR_URL
        var networkStatus by remember { mutableStateOf("Idle") }
        var isHost by remember { mutableStateOf(false) }
        var isJoined by remember { mutableStateOf(false) }
        var currentPing by remember { mutableStateOf(0L) }
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
        LaunchedEffect(isJoined, coordinatorAddress, roomCode) {
            if (!isJoined) {
                networkClient?.stop()
                networkClient = null
                isHost = false
                networkStatus = "Disconnected"
                currentPing = 0L
                return@LaunchedEffect
            }
            networkClient?.stop()
            if (coordinatorAddress.isNotBlank() && roomCode.isNotBlank()) {
                networkClient = LumiNetworkClient(
                    coordinatorAddress,
                    roomCode,
                    onStatusChanged = { status ->
                        networkStatus = status
                    },
                    onHostStatusChanged = { host ->
                        isHost = host
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                if (host) "You are now HOST" else "You are now FOLLOWER",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onPingMeasured = { ping ->
                        currentPing = ping
                    }
                ) { eventType, position, targetPlaying, timestamp ->
                    val controller = selectedController
                    if (controller != null) {
                        val state = controller.playbackState
                        val localPlaying = state != null && state.state == PlaybackState.STATE_PLAYING
                        val localPos = (state?.position ?: 0L) / 1000.0

                        // Calculate latency (fallback 50ms)
                        val nowMs = System.currentTimeMillis()
                        val latency = if (nowMs > timestamp) (nowMs - timestamp) / 1000.0 else 0.050
                        val expectedRemotePos = if (targetPlaying) position + latency else position
                        val drift = localPos - expectedRemotePos

                        if (eventType == "STATE") {
                            // STATE: soft drift correction ONLY (no play/pause/seek)
                            if (targetPlaying && localPlaying) {
                                if (Math.abs(drift) > 0.250) {
                                    val targetRate = if (drift > 0) 0.95f else 1.05f
                                    Log.d("LumiSync", "Drift: ${drift}s -> speed $targetRate")
                                    controller.transportControls.setPlaybackSpeed(targetRate)
                                } else if (Math.abs(drift) < 0.080) {
                                    controller.transportControls.setPlaybackSpeed(1.0f)
                                }
                            }
                        } else {
                            // Explicit commands (PLAY, PAUSE, SEEK): apply state + position
                            if (targetPlaying != localPlaying) {
                                if (targetPlaying) {
                                    Log.d("LumiSync", "Syncing state -> PLAY")
                                    controller.transportControls.play()
                                } else {
                                    Log.d("LumiSync", "Syncing state -> PAUSE")
                                    controller.transportControls.pause()
                                }
                            }

                            if (Math.abs(drift) > 1.5) {
                                Log.d("LumiSync", "Large drift (${drift}s). Hard seek -> $expectedRemotePos")
                                controller.transportControls.seekTo((expectedRemotePos * 1000).toLong())
                            }
                        }
                    }

                    if (eventType != "STATE") {
                        Toast.makeText(this@MainActivity, "Received $eventType", Toast.LENGTH_SHORT).show()
                    }
                }
                networkClient?.start()
            }
        }

        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, networkClient) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    val client = networkClient
                    if (client != null && !networkStatus.contains("Connected")) {
                        Log.d("LumiLifecycle", "App resumed. Proactively triggering reconnect.")
                        client.start()
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        // Periodic state sender coroutine on Android (every 5 seconds)
        LaunchedEffect(selectedController, networkClient, isHost) {
            while (true) {
                kotlinx.coroutines.delay(5000)
                val controller = selectedController
                val client = networkClient
                if (controller != null && client != null && isHost) {
                    val state = controller.playbackState
                    if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                        val currentPos = state.position
                        client.sendEvent("STATE", currentPos / 1000.0, true)
                    }
                }
            }
        }

        // Handle auto-broadcasting of local OS media session changes
        LaunchedEffect(selectedController, networkClient, isHost) {
            currentCallback?.let { selectedController?.unregisterCallback(it) }
            val callback = object : MediaController.Callback() {
                private var lastState = PlaybackState.STATE_NONE
                private var lastStatePosition = -1L
                private var lastStateUpdateTime = 0L

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (!isHost) return
                    state?.let {
                        val now = System.currentTimeMillis()
                        val currentPos = it.position

                        if (it.state == lastState) {
                            return
                        }
                        lastState = it.state

                        val eventType = when (it.state) {
                            PlaybackState.STATE_PLAYING -> "PLAY"
                            PlaybackState.STATE_PAUSED -> "PAUSE"
                            else -> null
                        }

                        var isManualSeek = false

                        if (lastStatePosition != -1L) {
                            val elapsed = now - lastStateUpdateTime
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
                                val isPlaying = it.state == PlaybackState.STATE_PLAYING
                                Log.d("LumiNetwork", "Broadcasting local phone seek: ${currentPos / 1000.0}")
                                networkClient?.sendEvent("SEEK", currentPos / 1000.0, isPlaying)
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
                                val isPlaying = eventType == "PLAY"
                                Log.d("LumiNetwork", "Broadcasting local OS media event: $eventType")
                                networkClient?.sendEvent(eventType, currentPos / 1000.0, isPlaying)
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
            com.example.mediasessiontest.ui.UserUiView(
                roomCode = roomCode,
                onRoomCodeChange = { roomCode = it },
                isJoined = isJoined,
                onJoinChanged = { isJoined = it },
                currentPing = currentPing,
                coordinatorAddress = coordinatorAddress,
                isHost = isHost,
                mediaControllers = activeControllers,
                selectedController = selectedController,
                onSelectController = { selectedController = it },
                onPlay = {
                    val currentPos = selectedController?.playbackState?.position ?: 0L
                    selectedController?.transportControls?.play()
                    coroutineScope.launch {
                        networkClient?.lastReceivedEventType = "PLAY"
                        networkClient?.lastReceivedEventTime = System.currentTimeMillis()
                        networkClient?.sendEvent("PLAY", currentPos / 1000.0, true)
                    }
                },
                onPause = {
                    val currentPos = selectedController?.playbackState?.position ?: 0L
                    selectedController?.transportControls?.pause()
                    coroutineScope.launch {
                        networkClient?.lastReceivedEventType = "PAUSE"
                        networkClient?.lastReceivedEventTime = System.currentTimeMillis()
                        networkClient?.sendEvent("PAUSE", currentPos / 1000.0, false)
                    }
                },
                onSeekBy = { deltaMs ->
                    val currentPos = selectedController?.playbackState?.position ?: 0L
                    val newPos = (currentPos + deltaMs).coerceAtLeast(0L)
                    val isPlaying = selectedController?.playbackState?.state == PlaybackState.STATE_PLAYING
                    selectedController?.transportControls?.seekTo(newPos)
                    coroutineScope.launch {
                        networkClient?.sendEvent("SEEK", newPos / 1000.0, isPlaying)
                    }
                }
            )
        }
    }
}
