mod adapter;
mod engine;
mod handler;
mod network;
mod protocol;
mod sync;
mod windows_adapter;

use crate::adapter::MediaSession;
use crate::engine::Engine;
use crate::handler::create_session_handler;
use crate::network::WebSocketTransport;
use crate::protocol::{EventType, LumiEvent};
use crate::sync::SyncEngine;
use clap::Parser;
use std::io::{self, Write};
use std::time::Duration;
use tokio::sync::mpsc;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Room code to join for synchronization
    #[arg(short, long, default_value = "456")]
    room: String,

    /// Coordinator server WebSocket address
    #[arg(short, long, default_value = "wss://lumi-connector.onrender.com")]
    coordinator: String,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    println!("=== Lumi Sync Engine ===");
    println!("Coordinator: {}", args.coordinator);
    println!("Room: {}", args.room);

    // Network setup
    let (tx, rx) = mpsc::channel(100);
    let transport = WebSocketTransport::connect(&args.coordinator, &args.room, tx).await?;
    let transport = std::sync::Arc::new(transport);

    // Initialize the engine
    let engine = std::sync::Arc::new(Engine::new()?);

    // Sync Engine setup
    let last_network_event = std::sync::Arc::new(std::sync::Mutex::new(None::<(std::time::Instant, EventType)>));
    let sync_engine = SyncEngine::new(rx, engine.get_active_session_arc(), last_network_event.clone());
    tokio::spawn(async move {
        sync_engine.run().await;
    });

    let mut sessions = engine.list_sessions()?;
    if sessions.is_empty() {
        println!("No active media sessions detected.");
    } else {
        println!("\nActive Sessions:");
        for (i, session) in sessions.iter().enumerate() {
            let meta_str = match session.get_metadata() {
                Ok(meta) => format!("\"{}\" by {}", meta.title, meta.artist),
                Err(_) => "No metadata".to_string(),
            };
            println!("{}: {} [{}]", i, session.get_source_app_id(), meta_str);
        }
    }

    engine.on_sessions_changed(|| {
        println!("\n[System] Sessions list updated. (Choose Option 1 to refresh)");
    })?;

    loop {
        println!("\nMenu:");
        println!("1. List/Refresh sessions");
        println!("2. Bind to a session");
        println!("3. Control session (Play/Pause/Seek)");
        println!("4. Exit");
        print!("Choice: ");
        io::stdout().flush()?;

        let mut choice = String::new();
        io::stdin().read_line(&mut choice)?;
        match choice.trim() {
            "1" => {
                sessions = engine.list_sessions()?;
                println!("\nActive Sessions:");
                for (i, session) in sessions.iter().enumerate() {
                    let meta_str = match session.get_metadata() {
                        Ok(meta) => format!("\"{}\" by {}", meta.title, meta.artist),
                        Err(_) => "No metadata".to_string(),
                    };
                    println!("{}: {} [{}]", i, session.get_source_app_id(), meta_str);
                }
            }
            "2" => {
                sessions = engine.list_sessions()?;
                if sessions.is_empty() {
                    println!("No active sessions found.");
                    continue;
                }
                println!("\nSelect session to bind:");
                for (i, s) in sessions.iter().enumerate() {
                    println!("{}: {}", i, s.get_source_app_id());
                }
                print!("Index: ");
                io::stdout().flush()?;

                let mut idx_str = String::new();
                io::stdin().read_line(&mut idx_str)?;
                if let Ok(idx) = idx_str.trim().parse::<usize>() {
                    if idx < sessions.len() {
                        let selected = sessions.remove(idx);
                        let app_id = selected.get_id();
                        println!("Bound to: {}", app_id);

                        let handler = create_session_handler(
                            engine.clone(),
                            transport.clone(),
                            last_network_event.clone(),
                        );
                        engine.bind_session(selected, handler)?;
                    } else {
                        println!("Index out of range.");
                    }
                } else {
                    println!("Invalid input.");
                }
            }
            "3" => {
                if let Some(session) = engine.get_bound_session() {
                    println!("\nControl Actions ({}):", session.get_source_app_id());
                    println!("1. Play");
                    println!("2. Pause");
                    println!("3. Seek (seconds)");
                    print!("Choose action: ");
                    io::stdout().flush()?;

                    let mut ctrl_choice = String::new();
                    io::stdin().read_line(&mut ctrl_choice)?;

                    let transport_clone = transport.clone();
                    let last_network_event_send = last_network_event.clone();

                    let send_event = move |event_type: EventType, pos: f64, playing: Option<bool>| {
                        {
                            let mut last_net = last_network_event_send.lock().unwrap();
                            *last_net = Some((std::time::Instant::now(), event_type.clone()));
                        }
                        let event = LumiEvent::new(event_type.clone(), "windows-device-1", pos, playing);
                        let t_clone = transport_clone.clone();
                        tokio::spawn(async move {
                            let _ = t_clone.send_event(&event).await;
                        });
                    };

                    match ctrl_choice.trim() {
                        "1" => {
                            let pos = session.get_timeline_properties().map(|p| p.position.as_secs_f64()).unwrap_or(0.0);
                            if session.play().is_ok() {
                                send_event(EventType::Play, pos, Some(true));
                            }
                        }
                        "2" => {
                            let pos = session.get_timeline_properties().map(|p| p.position.as_secs_f64()).unwrap_or(0.0);
                            if session.pause().is_ok() {
                                send_event(EventType::Pause, pos, Some(false));
                            }
                        }
                        "3" => {
                            print!("Enter position (s): ");
                            io::stdout().flush()?;
                            let mut seek_pos = String::new();
                            io::stdin().read_line(&mut seek_pos)?;
                            if let Ok(secs) = seek_pos.trim().parse::<u64>() {
                                let pos_f64 = secs as f64;
                                if session.seek(Duration::from_secs(secs)).is_ok() {
                                    let is_playing = if let Ok(info) = session.get_raw().GetPlaybackInfo() {
                                        matches!(
                                            info.PlaybackStatus(),
                                            Ok(windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing)
                                        )
                                    } else {
                                        false
                                    };
                                    send_event(EventType::Seek, pos_f64, Some(is_playing));
                                }
                            } else {
                                println!("Invalid value.");
                            }
                        }
                        _ => println!("Unknown action."),
                    }
                } else {
                    println!("No session bound.");
                }
            }
            "4" => {
                break;
            }
            _ => println!("Invalid selection."),
        }
    }

    Ok(())
}
