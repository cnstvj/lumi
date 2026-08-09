use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tokio::net::{TcpListener, TcpStream};
use tokio_tungstenite::tungstenite::Message;
use futures_util::{SinkExt, StreamExt};

// Representing a client's sender half of the WebSocket connection using Tokio's async Mutex
type Tx = futures_util::stream::SplitSink<tokio_tungstenite::WebSocketStream<TcpStream>, Message>;

struct Room {
    peers: HashMap<usize, Arc<tokio::sync::Mutex<Tx>>>,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    let port = std::env::var("PORT")
        .unwrap_or_else(|_| args.get(1).map(|s| s.as_str().to_string()).unwrap_or_else(|| "4000".to_string()));
    let bind_addr = format!("0.0.0.0:{}", port);

    let listener = TcpListener::bind(&bind_addr).await?;
    println!("Lumi WebSocket Coordinator running on port {}...", port);

    let rooms: Arc<Mutex<HashMap<String, Room>>> = Arc::new(Mutex::new(HashMap::new()));
    let next_peer_id = Arc::new(std::sync::atomic::AtomicUsize::new(1));

    while let Ok((stream, addr)) = listener.accept().await {
        let rooms_clone = rooms.clone();
        let peer_id = next_peer_id.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        
        tokio::spawn(async move {
            match tokio_tungstenite::accept_async(stream).await {
                Ok(ws_stream) => {
                    println!("New WebSocket connection from {}", addr);
                    let (ws_tx, mut ws_rx) = ws_stream.split();
                    
                    let mut current_room: Option<String> = None;
                    let tx_arc = Arc::new(tokio::sync::Mutex::new(ws_tx));

                    while let Some(Ok(msg)) = ws_rx.next().await {
                        if let Message::Text(text) = msg {
                            // Parse message to inspect for room joining
                            if let Ok(client_msg) = serde_json::from_str::<serde_json::Value>(&text) {
                                if let Some(action) = client_msg.get("action").and_then(|v| v.as_str()) {
                                    if action == "join" {
                                        if let Some(room_name) = client_msg.get("room").and_then(|v| v.as_str()) {
                                            let room_name = room_name.to_string();
                                            current_room = Some(room_name.clone());
                                            
                                            let mut rooms_lock = rooms_clone.lock().unwrap();
                                            let room = rooms_lock.entry(room_name.clone()).or_insert_with(|| Room {
                                                peers: HashMap::new(),
                                            });
                                            room.peers.insert(peer_id, tx_arc.clone());
                                            println!("Peer {} joined room: {}", addr, room_name);
                                        }
                                        continue;
                                    }
                                }

                                // Otherwise, if it is in a room, broadcast the event text to all other peers in the room
                                if let Some(ref room_name) = current_room {
                                    let mut peers_to_send = Vec::new();
                                    {
                                        let rooms_lock = rooms_clone.lock().unwrap();
                                        if let Some(room) = rooms_lock.get(room_name) {
                                            for (&id, peer_tx) in room.peers.iter() {
                                                if id != peer_id {
                                                    peers_to_send.push(peer_tx.clone());
                                                }
                                            }
                                        }
                                    }

                                    // Send frame to all other peers
                                    for peer_tx in peers_to_send {
                                        let mut lock = peer_tx.lock().await;
                                        let _ = lock.send(Message::Text(text.clone())).await;
                                    }
                                }
                            }
                        }
                    }

                    // Connection closed, clean up room
                    if let Some(ref room_name) = current_room {
                        let mut rooms_lock = rooms_clone.lock().unwrap();
                        if let Some(room) = rooms_lock.get_mut(room_name) {
                            room.peers.remove(&peer_id);
                            println!("Peer {} left room: {}", addr, room_name);
                        }
                    }
                }
                Err(e) => {
                    eprintln!("Error during WebSocket handshake: {}", e);
                }
            }
        });
    }

    Ok(())
}
