package com.example.mediasessiontest

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import org.json.JSONObject
import java.util.UUID

class LumiNetworkClient(
    private val coordinatorAddress: String, // e.g. "127.0.0.1:4000" or "lumi.fly.dev"
    private val roomCode: String,
    private val onStatusChanged: (String) -> Unit,
    private val onEventReceived: (String, Double) -> Unit // eventType, position
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    private var pingTimer: java.util.Timer? = null

    // Track last received network events to prevent loopback/echo storms
    @Volatile var lastReceivedEventTime = 0L
    @Volatile var lastReceivedEventType = ""

    fun start() {
        var url = coordinatorAddress.trim()
        if (url.startsWith("https://")) {
            url = url.replace("https://", "wss://")
        } else if (url.startsWith("http://")) {
            url = url.replace("http://", "ws://")
        } else if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "wss://$url" // Default to secure WSS for Render/production compatibility
        }

        Log.d("LumiNetwork", "Connecting to WebSocket: $url")
        onStatusChanged("Connecting...")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("LumiNetwork", "WebSocket Opened")
                onStatusChanged("Connected")

                // Join room
                val joinMsg = JSONObject().apply {
                    put("action", "join")
                    put("room", roomCode)
                }
                webSocket.send(joinMsg.toString())
                Log.d("LumiNetwork", "Sent join room action: $roomCode")
                onStatusChanged("Connected (Room $roomCode)")

                // Start ping loop to measure RTT every 10 seconds
                pingTimer?.cancel()
                pingTimer = java.util.Timer().apply {
                    scheduleAtFixedRate(object : java.util.TimerTask() {
                        override fun run() {
                            try {
                                val pingMsg = JSONObject().apply {
                                    put("action", "ping")
                                    put("timestamp", System.currentTimeMillis())
                                }
                                webSocket.send(pingMsg.toString())
                            } catch (e: Exception) {
                                Log.e("LumiNetwork", "Failed to send ping", e)
                            }
                        }
                    }, 5000, 10000)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("LumiNetwork", "Received: $text")
                try {
                    val json = JSONObject(text)
                    
                    // Check for pong
                    if (json.optString("action") == "pong") {
                        val sentTime = json.optLong("timestamp", 0)
                        if (sentTime > 0) {
                            val rtt = System.currentTimeMillis() - sentTime
                            Log.d("LumiNetwork", "RTT: $rtt ms")
                            onStatusChanged("Connected (RTT: $rtt ms)")
                        }
                        return
                    }

                    val eventType = json.getString("type")
                    val position = json.optDouble("position", 0.0)

                    lastReceivedEventTime = System.currentTimeMillis()
                    lastReceivedEventType = eventType

                    onEventReceived(eventType, position)
                } catch (e: Exception) {
                    Log.e("LumiNetwork", "Error parsing event text: $text", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("LumiNetwork", "Closing: $code / $reason")
                pingTimer?.cancel()
                onStatusChanged("Disconnected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("LumiNetwork", "WebSocket Failure", t)
                pingTimer?.cancel()
                onStatusChanged("Failure: ${t.message}")
            }
        })
    }

    fun sendEvent(eventType: String, position: Double) {
        val ws = webSocket
        if (ws == null) {
            Log.e("LumiNetwork", "WebSocket is not connected.")
            return
        }

        try {
            val json = JSONObject().apply {
                put("type", eventType)
                put("device_id", "android-device-1")
                put("event_id", UUID.randomUUID().toString())
                put("timestamp", System.currentTimeMillis())
                put("position", position)
            }
            val text = json.toString()
            ws.send(text)
            Log.d("LumiNetwork", "Sent event: $text")
        } catch (e: Exception) {
            Log.e("LumiNetwork", "Failed to send event", e)
        }
    }

    fun stop() {
        pingTimer?.cancel()
        pingTimer = null
        webSocket?.close(1000, "App closed")
        webSocket = null
        client.dispatcher.executorService.shutdown()
    }
}
