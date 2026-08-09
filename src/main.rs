mod adapter;
mod engine;
mod network;
mod protocol;
mod sync;
mod windows_adapter;

use crate::adapter::MediaSession;
use crate::engine::{Engine, SessionEvent};
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
    #[arg(short, long)]
    room: String,

    /// Coordinator server WebSocket address (e.g. 127.0.0.1:4000)
    #[arg(short, long, default_value = "127.0.0.1:4000")]
    coordinator: String,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    println!("=== Lumi Sync Engine (WebSocket Phase 4) ===");
    println!("Connecting to Coordinator: {}", args.coordinator);
    println!("Joining Room: {}", args.room);

    // Network setup
    let (tx, rx) = mpsc::channel(100);
    let transport = WebSocketTransport::connect(&args.coordinator, &args.room, tx).await?;
    let transport = std::sync::Arc::new(transport);

    // Initialize the engine
    let engine = Engine::new()?;

    // Sync Engine setup
    let last_network_event = std::sync::Arc::new(std::sync::Mutex::new(None::<(std::time::Instant, EventType)>));
    let last_network_event_clone = last_network_event.clone();
    let sync_engine = SyncEngine::new(rx, engine.get_active_session_arc(), last_network_event_clone);
    tokio::spawn(async move {
        sync_engine.run().await;
    });

    // List available sessions initially
    let mut sessions = engine.list_sessions()?;
    if sessions.is_empty() {
        println!("No active media sessions detected on the system. Start a media player first.");
    } else {
        println!("\nActive Sessions detected:");
        for (i, session) in sessions.iter().enumerate() {
            let meta_str = match session.get_metadata() {
                Ok(meta) => format!("\"{}\" by {}", meta.title, meta.artist),
                Err(_) => "No metadata".to_string(),
            };
            println!("{}: {} [{}]", i, session.get_source_app_id(), meta_str);
        }
    }

    // Set up sessions change notifier
    engine.on_sessions_changed(|| {
        println!("\n[System Notification] Active session list changed! (Option 1 to refresh list)");
    })?;

    loop {
        println!("\nActions:");
        println!("1. List/Refresh active sessions");
        println!("2. Select and Bind to a session");
        println!("3. Control currently bound session (Play/Pause/Seek)");
        println!("4. Exit");
        print!("Choose action: ");
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
                    println!("No sessions to bind.");
                    continue;
                }
                println!("\nSelect session index to bind:");
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
                        println!("Binding to session: {}", app_id);

                        // Bind and specify event log action
                        let transport_clone = transport.clone();
                        let rt_handle = tokio::runtime::Handle::current();
                        let last_timeline = std::sync::Arc::new(std::sync::Mutex::new(None::<(std::time::Instant, std::time::Duration)>));
                        let last_network_event_cb = last_network_event.clone();

                        engine.bind_session(selected, move |event| {
                            match event {
                                SessionEvent::PlaybackStatusChanged(id, status) => {
                                    println!("\n[Bound Event] [{}] Playback Status: {:?}", id, status);
                                    let event_type = match status {
                                        crate::adapter::PlaybackStatus::Playing => EventType::Play,
                                        crate::adapter::PlaybackStatus::Paused => EventType::Pause,
                                        _ => return,
                                    };

                                    // Deduplicate loopback echos from network commands
                                    let elapsed_opt = {
                                        let last = last_network_event_cb.lock().unwrap();
                                        last.as_ref().map(|(time, ev)| (time.elapsed(), ev.clone()))
                                    };
                                    if let Some((elapsed, ev)) = elapsed_opt {
                                        if ev == event_type && elapsed < std::time::Duration::from_secs(2) {
                                            println!("[Bound Event] Ignoring feedback echo loop for {:?}", event_type);
                                            return;
                                        }
                                    }

                                    let network_event = LumiEvent::new(event_type.clone(), "windows-device-1", 0.0, None);
                                    let t = transport_clone.clone();
                                    rt_handle.spawn(async move {
                                        let _ = t.send_event(&network_event).await;
                                    });
                                }
                                SessionEvent::MetadataChanged(id, meta) => {
                                    println!(
                                        "\n[Bound Event] [{}] Title: \"{}\", Artist: \"{}\"",
                                        id, meta.title, meta.artist
                                    );
                                }
                                SessionEvent::TimelineChanged(id, timeline) => {
                                    println!(
                                        "\n[Bound Event] [{}] Position: {:.1}s / {:.1}s",
                                        id,
                                        timeline.position.as_secs_f64(),
                                        timeline.duration.as_secs_f64()
                                    );

                                    let now = std::time::Instant::now();
                                    let mut last = last_timeline.lock().unwrap();
                                    let mut is_manual_seek = false;

                                    if let Some((last_time, last_pos)) = *last {
                                        let elapsed = now.duration_since(last_time);
                                        let expected_pos = last_pos + elapsed;
                                        let diff = if timeline.position > expected_pos {
                                            timeline.position - expected_pos
                                        } else {
                                            expected_pos - timeline.position
                                        };
                                        // If the difference is greater than 1.5s, it is a manual seek
                                        if diff > std::time::Duration::from_millis(1500) {
                                            is_manual_seek = true;
                                        }
                                    }
                                    *last = Some((now, timeline.position));

                                    if is_manual_seek {
                                        // Deduplicate loopback echos from network commands
                                        let elapsed_opt = {
                                            let last_net = last_network_event_cb.lock().unwrap();
                                            last_net.as_ref().map(|(time, ev)| (time.elapsed(), ev.clone()))
                                        };
                                        if let Some((elapsed, ev)) = elapsed_opt {
                                            if ev == EventType::Seek && elapsed < std::time::Duration::from_secs(2) {
                                                println!("[Bound Event] Ignoring feedback echo loop for Seek");
                                                return;
                                            }
                                        }

                                        println!("[Bound Event] Manual seek detected! Syncing position: {:.1}s", timeline.position.as_secs_f64());
                                        let network_event = LumiEvent::new(
                                            EventType::Seek,
                                            "windows-device-1",
                                            timeline.position.as_secs_f64(),
                                            None,
                                        );
                                        let t = transport_clone.clone();
                                        rt_handle.spawn(async move {
                                            let _ = t.send_event(&network_event).await;
                                        });
                                    }
                                }
                            }
                        })?;
                        println!("Session successfully bound! Events will print here automatically.");
                    } else {
                        println!("Invalid index.");
                    }
                } else {
                    println!("Please enter a valid number.");
                }
            }
            "3" => {
                if let Some(session) = engine.get_bound_session() {
                    println!("\nBound Session Actions ({}):", session.get_source_app_id());
                    println!("1. Play");
                    println!("2. Pause");
                    println!("3. Seek (seconds)");
                    print!("Choose control: ");
                    io::stdout().flush()?;

                    let mut ctrl_choice = String::new();
                    io::stdin().read_line(&mut ctrl_choice)?;
                    
                    let transport_clone = transport.clone();

                    let send_event = move |event_type: EventType, pos: f64| {
                        let event = LumiEvent::new(event_type.clone(), "windows-device-1", pos, None);
                        let t_clone = transport_clone.clone();
                        tokio::spawn(async move {
                            if let Err(err) = t_clone.send_event(&event).await {
                                    eprintln!("Failed to send WebSocket event: {}", err);
                            }
                        });
                    };

                    match ctrl_choice.trim() {
                        "1" => {
                            if let Err(e) = session.play() {
                                println!("Error sending Play command: {:?}", e);
                            } else {
                                send_event(EventType::Play, 0.0);
                                println!("Sent Play command globally.");
                            }
                        }
                        "2" => {
                            if let Err(e) = session.pause() {
                                println!("Error sending Pause command: {:?}", e);
                            } else {
                                send_event(EventType::Pause, 0.0);
                                println!("Sent Pause command globally.");
                            }
                        }
                        "3" => {
                            print!("Enter seek position (seconds): ");
                            io::stdout().flush()?;
                            let mut seek_pos = String::new();
                            io::stdin().read_line(&mut seek_pos)?;
                            if let Ok(secs) = seek_pos.trim().parse::<u64>() {
                                let pos_f64 = secs as f64;
                                if let Err(e) = session.seek(Duration::from_secs(secs)) {
                                    println!("Error sending Seek command: {:?}", e);
                                } else {
                                    send_event(EventType::Seek, pos_f64);
                                    println!("Sent Seek command globally to {}s.", secs);
                                }
                            } else {
                                println!("Invalid input.");
                            }
                        }
                        _ => println!("Invalid control option."),
                    }
                } else {
                    println!("No session is currently bound. Please bind a session first (Option 2).");
                }
            }
            "4" => {
                println!("Exiting Sync Engine...");
                break;
            }
            _ => println!("Invalid option."),
        }
    }

    Ok(())
}
