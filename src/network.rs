use crate::protocol::LumiEvent;
use std::sync::Arc;
use tokio::sync::Mutex;
use tokio_tungstenite::tungstenite::Message;
use futures_util::{SinkExt, StreamExt};
use tokio::sync::mpsc;

pub struct WebSocketTransport {
    ws_tx: Arc<Mutex<futures_util::stream::SplitSink<tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>, Message>>>,
}

impl WebSocketTransport {
    pub async fn connect(
        coordinator_addr: &str,
        room_code: &str,
        event_sender: mpsc::Sender<LumiEvent>,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        // Resolve WS/WSS URL schema
        let mut url = coordinator_addr.trim().to_string();
        if url.starts_with("https://") {
            url = url.replace("https://", "wss://");
        } else if url.starts_with("http://") {
            url = url.replace("http://", "ws://");
        } else if !url.starts_with("ws://") && !url.starts_with("wss://") {
            url = format!("wss://{}", url); // Default to secure WSS for Render/production
        }

        let (ws_stream, _) = tokio_tungstenite::connect_async(&url).await?;

        let (mut ws_tx, mut ws_rx) = ws_stream.split();

        // Send JOIN room handshake frame immediately
        let join_payload = serde_json::json!({
            "action": "join",
            "room": room_code
        });
        ws_tx.send(Message::Text(join_payload.to_string())).await?;

        let ws_tx = Arc::new(Mutex::new(ws_tx));

        // Spawn background ping loop to measure RTT
        let ws_tx_clone = ws_tx.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(std::time::Duration::from_secs(10)).await;
                let timestamp = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis() as u64;
                let ping = serde_json::json!({
                    "action": "ping",
                    "timestamp": timestamp
                });
                let mut lock = ws_tx_clone.lock().await;
                if lock.send(Message::Text(ping.to_string())).await.is_err() {
                    break;
                }
            }
        });

        // Spawn read loop task
        tokio::spawn(async move {
            while let Some(Ok(msg)) = ws_rx.next().await {
                if let Message::Text(text) = msg {
                    // Check for pong response
                    if let Ok(client_msg) = serde_json::from_str::<serde_json::Value>(&text) {
                        if let Some(action) = client_msg.get("action").and_then(|v| v.as_str()) {
                            if action == "pong" {
                                continue;
                            }
                        }
                    }

                    if let Ok(event) = serde_json::from_str::<LumiEvent>(&text) {
                        if event_sender.send(event).await.is_err() {
                            break; // channel closed
                        }
                    }
                }
            }
        });

        Ok(Self { ws_tx })
    }

    pub async fn send_event(&self, event: &LumiEvent) -> std::io::Result<()> {
        if let Ok(text) = serde_json::to_string(event) {
            let mut lock = self.ws_tx.lock().await;
            if let Err(e) = lock.send(Message::Text(text)).await {
                eprintln!("[Network] Failed to send WebSocket message: {}", e);
                return Err(std::io::Error::new(std::io::ErrorKind::ConnectionAborted, e));
            }
        }
        Ok(())
    }
}
