# Lumi Codebase Structure

This document outlines the modularized structure and entire source code reference of the Lumi Windows client application.

---

## Directory Layout

```text
c:/PVR/lumi/
├── Cargo.toml
├── src/
│   ├── main.rs            # Application entrypoint & GUI app boots
│   ├── constants.rs       # Central application config & threshold constants
│   ├── gui.rs             # egui/eframe GUI implementation (User controls and settings)
│   ├── adapter.rs         # Abstract Trait defining media session interfaces
│   ├── windows_adapter.rs # Concrete implementation of MediaSession using Windows SDK
│   ├── engine.rs          # Manages sessions & coordinates OS media events
│   ├── handler.rs         # Session callback router & loopback echo/drift debouncer
│   ├── network.rs         # WebSocket connection manager & ping/pong executor
│   ├── sync.rs            # Unified sync engine handling incoming states
│   └── protocol.rs        # JSON serialization schemas
```

---

## Module Breakdown

### 1. [main.rs](file:///c:/PVR/lumi/src/main.rs)
Bootstraps the GUI application thread and spawns the native `eframe` window.

### 1b. [gui.rs](file:///c:/PVR/lumi/src/gui.rs)
Builds the instant-mode Graphical User Interface, manages connection parameters, binds to media sessions, and presents Host/Follower playback controls.

### 2. [adapter.rs](file:///c:/PVR/lumi/src/adapter.rs)
Defines abstract data types (`PlaybackStatus`, `MediaMetadata`, `TimelineProperties`) and the core `MediaSession` trait that wraps underlying OS APIs.

### 3. [windows_adapter.rs](file:///c:/PVR/lumi/src/windows_adapter.rs)
Uses the native `windows` crate bindings to query and update the Windows Global System Media Transport Controls (GSMTC). Implements the `MediaSession` trait.

### 4. [engine.rs](file:///c:/PVR/lumi/src/engine.rs)
Monitors System Media Transport Controls, detects sessions changes, and binds to active media players. Connects event callbacks (`PlaybackStatusChanged`, `MetadataChanged`, `TimelineChanged`) to bound sessions.

### 5. [handler.rs](file:///c:/PVR/lumi/src/handler.rs)
Generates the core media callback handler. Decouples timing logic, filters network echos, executes periodic background state updates, and triggers manual seek broadcasts.

### 6. [network.rs](file:///c:/PVR/lumi/src/network.rs)
Wraps `tokio-tungstenite` to connect to the coordinator, handles room joining handshakes, runs a background RTT check loop, and coordinates reading and sending JSON signals.

### 7. [sync.rs](file:///c:/PVR/lumi/src/sync.rs)
Runs a background listener that processes incoming WebSocket signals (`PLAY`, `PAUSE`, `SEEK`, `STATE`) through a unified latency-corrected drift correction state machine.

### 8. [protocol.rs](file:///c:/PVR/lumi/src/protocol.rs)
Defines target network events and commands using `serde` serialization to ensure structural compatibility with Android/coordinator clients.

---

## Full Codebase Reference

### [main.rs](file:///c:/PVR/lumi/src/main.rs)
```rust
mod adapter;
mod engine;
mod network;
mod protocol;
mod sync;
mod windows_adapter;
mod handler;

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
```

### [adapter.rs](file:///c:/PVR/lumi/src/adapter.rs)
```rust
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlaybackStatus {
    Playing,
    Paused,
    Stopped,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaMetadata {
    pub title: String,
    pub artist: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TimelineProperties {
    pub position: Duration,
    pub duration: Duration,
}

pub trait MediaSession: Send + Sync {
    fn get_id(&self) -> String;
    fn get_source_app_id(&self) -> String;
    fn get_metadata(&self) -> Result<MediaMetadata, Box<dyn std::error::Error>>;

    fn play(&self) -> Result<(), Box<dyn std::error::Error>>;
    fn pause(&self) -> Result<(), Box<dyn std::error::Error>>;
    fn seek(&self, position: Duration) -> Result<(), Box<dyn std::error::Error>>;
    fn get_timeline_properties(&self) -> Result<TimelineProperties, Box<dyn std::error::Error>>;
    fn set_playback_rate(&self, rate: f64) -> Result<(), Box<dyn std::error::Error>>;
}
```

### [windows_adapter.rs](file:///c:/PVR/lumi/src/windows_adapter.rs)
```rust
use crate::adapter::{MediaMetadata, MediaSession, TimelineProperties};
use std::time::Duration;
use windows::Media::Control::GlobalSystemMediaTransportControlsSession;

pub struct WindowsMediaSession {
    session: GlobalSystemMediaTransportControlsSession,
}

impl WindowsMediaSession {
    pub fn new(session: GlobalSystemMediaTransportControlsSession) -> Self {
        Self { session }
    }

    pub fn get_raw(&self) -> &GlobalSystemMediaTransportControlsSession {
        &self.session
    }
}

impl MediaSession for WindowsMediaSession {
    fn get_id(&self) -> String {
        self.session
            .SourceAppUserModelId()
            .map(|s| s.to_string())
            .unwrap_or_else(|_| "Unknown".to_string())
    }

    fn get_source_app_id(&self) -> String {
        self.get_id()
    }

    fn get_metadata(&self) -> Result<MediaMetadata, Box<dyn std::error::Error>> {
        let props = self.session.TryGetMediaPropertiesAsync()?.get()?;
        Ok(MediaMetadata {
            title: props.Title()?.to_string(),
            artist: props.Artist()?.to_string(),
        })
    }

    fn play(&self) -> Result<(), Box<dyn std::error::Error>> {
        self.session.TryPlayAsync()?.get()?;
        Ok(())
    }

    fn pause(&self) -> Result<(), Box<dyn std::error::Error>> {
        self.session.TryPauseAsync()?.get()?;
        Ok(())
    }

    fn seek(&self, position: Duration) -> Result<(), Box<dyn std::error::Error>> {
        let ticks = (position.as_nanos() / 100) as i64;
        self.session.TryChangePlaybackPositionAsync(ticks)?.get()?;
        Ok(())
    }

    fn get_timeline_properties(&self) -> Result<TimelineProperties, Box<dyn std::error::Error>> {
        let timeline = self.session.GetTimelineProperties()?;
        let pos = timeline.Position()?;
        let end = timeline.EndTime()?;
        let position = std::time::Duration::from_nanos((pos.Duration * 100) as u64);
        let duration = std::time::Duration::from_nanos((end.Duration * 100) as u64);
        Ok(TimelineProperties { position, duration })
    }

    fn set_playback_rate(&self, rate: f64) -> Result<(), Box<dyn std::error::Error>> {
        self.session.TryChangePlaybackRateAsync(rate)?.get()?;
        Ok(())
    }
}
```

### [engine.rs](file:///c:/PVR/lumi/src/engine.rs)
```rust
use crate::adapter::{
    MediaMetadata, MediaSession, PlaybackStatus, TimelineProperties,
};
use crate::windows_adapter::WindowsMediaSession;
use std::sync::{Arc, Mutex};
use windows::Foundation::TypedEventHandler;
use windows::Media::Control::{
    GlobalSystemMediaTransportControlsSession,
    GlobalSystemMediaTransportControlsSessionManager, MediaPropertiesChangedEventArgs,
    PlaybackInfoChangedEventArgs, SessionsChangedEventArgs, TimelinePropertiesChangedEventArgs,
};

struct BoundTokens {
    session: GlobalSystemMediaTransportControlsSession,
    playback_info_token: windows::Foundation::EventRegistrationToken,
    media_properties_token: windows::Foundation::EventRegistrationToken,
    timeline_properties_token: windows::Foundation::EventRegistrationToken,
}

pub struct Engine {
    manager: GlobalSystemMediaTransportControlsSessionManager,
    active_session: Arc<Mutex<Option<WindowsMediaSession>>>,
    bound_tokens: Mutex<Option<BoundTokens>>,
}

impl Engine {
    pub fn new() -> Result<Self, Box<dyn std::error::Error>> {
        let manager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync()?.get()?;
        Ok(Self {
            manager,
            active_session: Arc::new(Mutex::new(None)),
            bound_tokens: Mutex::new(None),
        })
    }

    pub fn list_sessions(&self) -> Result<Vec<WindowsMediaSession>, Box<dyn std::error::Error>> {
        let sessions = self.manager.GetSessions()?;
        let mut list = Vec::new();
        for i in 0..sessions.Size()? {
            let session = sessions.GetAt(i)?;
            list.push(WindowsMediaSession::new(session));
        }
        Ok(list)
    }

    pub fn bind_session<F>(&self, session: WindowsMediaSession, on_event: F) -> Result<(), Box<dyn std::error::Error>>
    where
        F: Fn(SessionEvent) + Send + Sync + 'static,
    {
        // 0. Unregister previous session callbacks if bound
        {
            let mut tokens_lock = self.bound_tokens.lock().unwrap();
            if let Some(tokens) = tokens_lock.take() {
                let _ = tokens.session.RemovePlaybackInfoChanged(tokens.playback_info_token);
                let _ = tokens.session.RemoveMediaPropertiesChanged(tokens.media_properties_token);
                let _ = tokens.session.RemoveTimelinePropertiesChanged(tokens.timeline_properties_token);
            }
        }

        let raw = session.get_raw().clone();
        let app_id = session.get_id();
        
        {
            let mut active = self.active_session.lock().unwrap();
            *active = Some(session);
        }

        let on_event = Arc::new(on_event);

        // 1. Playback info change handler
        let app_id_clone = app_id.clone();
        let on_event_clone = on_event.clone();
        let playback_info_token = raw.PlaybackInfoChanged(&TypedEventHandler::new(
            move |session: &Option<GlobalSystemMediaTransportControlsSession>,
                  _args: &Option<PlaybackInfoChangedEventArgs>| {
                if let Some(session) = session {
                    if let Ok(info) = session.GetPlaybackInfo() {
                        if let Ok(status) = info.PlaybackStatus() {
                            let mapped_status = match status {
                                windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing => PlaybackStatus::Playing,
                                windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackStatus::Paused => PlaybackStatus::Paused,
                                windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackStatus::Stopped
                                | windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackStatus::Closed => PlaybackStatus::Stopped,
                                _ => PlaybackStatus::Unknown,
                            };
                            on_event_clone(SessionEvent::PlaybackStatusChanged(app_id_clone.clone(), mapped_status));
                        }
                    }
                }
                Ok(())
            }
        ))?;

        // 2. Media properties change handler
        let app_id_clone = app_id.clone();
        let on_event_clone = on_event.clone();
        let media_properties_token = raw.MediaPropertiesChanged(&TypedEventHandler::new(
            move |session: &Option<GlobalSystemMediaTransportControlsSession>,
                  _args: &Option<MediaPropertiesChangedEventArgs>| {
                if let Some(session) = session {
                    if let Ok(async_op) = session.TryGetMediaPropertiesAsync() {
                        if let Ok(props) = async_op.get() {
                            let title = props.Title().map(|s| s.to_string()).unwrap_or_default();
                            let artist = props.Artist().map(|s| s.to_string()).unwrap_or_default();
                            on_event_clone(SessionEvent::MetadataChanged(
                                app_id_clone.clone(),
                                MediaMetadata { title, artist }
                            ));
                        }
                    }
                }
                Ok(())
            }
        ))?;

        // 3. Timeline properties change handler
        let app_id_clone = app_id.clone();
        let on_event_clone = on_event.clone();
        let is_streaming = is_streaming_platform(&app_id);
        let last_update = Arc::new(Mutex::new(None::<(std::time::Instant, std::time::Duration)>));

        let timeline_properties_token = raw.TimelinePropertiesChanged(&TypedEventHandler::new(
            move |session: &Option<GlobalSystemMediaTransportControlsSession>,
                  _args: &Option<TimelinePropertiesChangedEventArgs>| {
                if let Some(session) = session {
                    if let Ok(timeline) = session.GetTimelineProperties() {
                        if let (Ok(pos), Ok(end)) = (timeline.Position(), timeline.EndTime()) {
                            let position = std::time::Duration::from_nanos((pos.Duration * 100) as u64);
                            let duration = std::time::Duration::from_nanos((end.Duration * 100) as u64);
                            
                            let now = std::time::Instant::now();
                            let mut last = last_update.lock().unwrap();
                            let should_update = if is_streaming {
                                true
                            } else if let Some((last_time, last_pos)) = *last {
                                let time_elapsed = now.duration_since(last_time);
                                
                                // Query if the player is currently playing
                                let is_playing = if let Ok(info) = session.GetPlaybackInfo() {
                                    matches!(
                                        info.PlaybackStatus(),
                                        Ok(windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing)
                                    )
                                } else {
                                    false
                                };

                                let expected_pos = if is_playing {
                                    last_pos + time_elapsed
                                } else {
                                    last_pos
                                };

                                let pos_diff = if position > expected_pos {
                                    position - expected_pos
                                } else {
                                    expected_pos - position
                                };
                                time_elapsed >= std::time::Duration::from_secs(5) || pos_diff >= std::time::Duration::from_millis(200)
                            } else {
                                true
                            };

                            if should_update {
                                *last = Some((now, position));
                                on_event_clone(SessionEvent::TimelineChanged(
                                    app_id_clone.clone(),
                                    TimelineProperties { position, duration }
                                ));
                            }
                        }
                    }
                }
                Ok(())
            }
        ))?;

        // Store new registration tokens
        {
            let mut tokens_lock = self.bound_tokens.lock().unwrap();
            *tokens_lock = Some(BoundTokens {
                session: raw,
                playback_info_token,
                media_properties_token,
                timeline_properties_token,
            });
        }

        Ok(())
    }

    pub fn get_bound_session(&self) -> Option<WindowsMediaSession> {
        let active = self.active_session.lock().unwrap();
        active.as_ref().map(|s| WindowsMediaSession::new(s.get_raw().clone()))
    }

    pub fn get_active_session_arc(&self) -> Arc<Mutex<Option<WindowsMediaSession>>> {
        self.active_session.clone()
    }

    pub fn on_sessions_changed<F>(&self, on_changed: F) -> Result<(), Box<dyn std::error::Error>>
    where
        F: Fn() + Send + Sync + 'static,
    {
        let on_changed = Arc::new(on_changed);
        self.manager.SessionsChanged(&TypedEventHandler::new(
            move |_sender: &Option<GlobalSystemMediaTransportControlsSessionManager>,
                  _args: &Option<SessionsChangedEventArgs>| {
                on_changed();
                Ok(())
            }
        ))?;
        Ok(())
    }
}

fn is_streaming_platform(app_id: &str) -> bool {
    let lower = app_id.to_lowercase();
    lower.contains("chrome")
        || lower.contains("edge")
        || lower.contains("firefox")
        || lower.contains("opera")
        || lower.contains("brave")
        || lower.contains("youtube")
        || lower.contains("netflix")
        || lower.contains("primevideo")
        || lower.contains("hotstar")
}

#[allow(dead_code)]
pub enum SessionEvent {
    PlaybackStatusChanged(String, PlaybackStatus),
    MetadataChanged(String, MediaMetadata),
    TimelineChanged(String, TimelineProperties),
}
```

### [handler.rs](file:///c:/PVR/lumi/src/handler.rs)
```rust
use crate::engine::SessionEvent;
use crate::protocol::{EventType, LumiEvent};
use crate::adapter::{PlaybackStatus, MediaSession};
use crate::network::WebSocketTransport;
use crate::engine::Engine;
use std::sync::{Arc, Mutex};
use std::time::{Instant, Duration};

pub fn create_session_handler(
    engine: Arc<Engine>,
    transport: Arc<WebSocketTransport>,
    last_network_event: Arc<Mutex<Option<(Instant, EventType)>>>,
) -> impl Fn(SessionEvent) + Send + Sync + 'static {
    let last_timeline = Arc::new(Mutex::new(None::<(Instant, Duration)>));
    let last_state_send_time = Arc::new(Mutex::new(Instant::now() - Duration::from_secs(5)));
    let last_playback_status = Arc::new(Mutex::new(PlaybackStatus::Unknown));
    let rt_handle = tokio::runtime::Handle::current();

    move |event| {
        match event {
            SessionEvent::PlaybackStatusChanged(_, status) => {
                let should_send = {
                    let mut last = last_playback_status.lock().unwrap();
                    if *last != status {
                        *last = status;
                        true
                    } else {
                        false
                    }
                };
                if !should_send {
                    return;
                }

                let event_type = match status {
                    PlaybackStatus::Playing => EventType::Play,
                    PlaybackStatus::Paused => EventType::Pause,
                    _ => return,
                };

                // Deduplicate loopback echos from network commands
                let elapsed_opt = {
                    let last = last_network_event.lock().unwrap();
                    last.as_ref().map(|(time, ev)| (time.elapsed(), ev.clone()))
                };
                if let Some((elapsed, ev)) = elapsed_opt {
                    if ev == event_type && elapsed < Duration::from_secs(2) {
                        return;
                    }
                }

                let position = if let Some(s) = engine.get_bound_session() {
                    s.get_timeline_properties().map(|p| p.position.as_secs_f64()).unwrap_or(0.0)
                } else {
                    0.0
                };

                let target_playing = Some(event_type == EventType::Play);
                let network_event = LumiEvent::new(event_type, "windows-device-1", position, target_playing);
                let t = transport.clone();
                rt_handle.spawn(async move {
                    let _ = t.send_event(&network_event).await;
                });
            }
            SessionEvent::MetadataChanged(_, _) => {}
            SessionEvent::TimelineChanged(_, timeline) => {
                let now = Instant::now();

                // Periodically send STATE event to sync drift (every 5 seconds)
                let is_playing = if let Some(s) = engine.get_bound_session() {
                    if let Ok(info) = s.get_raw().GetPlaybackInfo() {
                        matches!(
                            info.PlaybackStatus(),
                            Ok(windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing)
                        )
                    } else {
                        false
                    }
                } else {
                    false
                };

                if is_playing {
                    let mut last_send = last_state_send_time.lock().unwrap();
                    if now.duration_since(*last_send) >= Duration::from_secs(5) {
                        *last_send = now;
                        let state_event = LumiEvent::new(
                            EventType::State,
                            "windows-device-1",
                            timeline.position.as_secs_f64(),
                            Some(true),
                        );
                        let t = transport.clone();
                        rt_handle.spawn(async move {
                            let _ = t.send_event(&state_event).await;
                        });
                    }
                }

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
                    if diff > Duration::from_millis(1500) {
                        is_manual_seek = true;
                    }
                }
                *last = Some((now, timeline.position));

                if is_manual_seek {
                    // Deduplicate loopback echos from network commands
                    let elapsed_opt = {
                        let last_net = last_network_event.lock().unwrap();
                        last_net.as_ref().map(|(time, ev)| (time.elapsed(), ev.clone()))
                    };
                    if let Some((elapsed, ev)) = elapsed_opt {
                        if ev == EventType::Seek && elapsed < Duration::from_secs(2) {
                            return;
                        }
                    }

                    let network_event = LumiEvent::new(
                        EventType::Seek,
                        "windows-device-1",
                        timeline.position.as_secs_f64(),
                        Some(is_playing),
                    );
                    let t = transport.clone();
                    rt_handle.spawn(async move {
                        let _ = t.send_event(&network_event).await;
                    });
                }
            }
        }
    }
}
```

### [network.rs](file:///c:/PVR/lumi/src/network.rs)
```rust
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
```

### [sync.rs](file:///c:/PVR/lumi/src/sync.rs)
```rust
use crate::adapter::MediaSession;
use crate::protocol::{EventType, LumiEvent};
use crate::windows_adapter::WindowsMediaSession;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;

pub struct SyncEngine {
    receiver: mpsc::Receiver<LumiEvent>,
    active_session: Arc<Mutex<Option<WindowsMediaSession>>>,
    last_network_event: Arc<Mutex<Option<(std::time::Instant, EventType)>>>,
}

impl SyncEngine {
    pub fn new(
        receiver: mpsc::Receiver<LumiEvent>,
        active_session: Arc<Mutex<Option<WindowsMediaSession>>>,
        last_network_event: Arc<Mutex<Option<(std::time::Instant, EventType)>>>,
    ) -> Self {
        Self {
            receiver,
            active_session,
            last_network_event,
        }
    }

    pub async fn run(mut self) {
        while let Some(event) = self.receiver.recv().await {
            // Record this network event to deduplicate loopback echos
            {
                let mut last = self.last_network_event.lock().unwrap();
                *last = Some((std::time::Instant::now(), event.event_type.clone()));
            }

            let session_opt = {
                let lock = self.active_session.lock().unwrap();
                lock.as_ref().map(|s| WindowsMediaSession::new(s.get_raw().clone()))
            };

            if let Some(session) = session_opt {
                let local_playing = if let Ok(info) = session.get_raw().GetPlaybackInfo() {
                    matches!(
                        info.PlaybackStatus(),
                        Ok(windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing)
                    )
                } else {
                    false
                };

                let local_pos = if let Ok(props) = session.get_timeline_properties() {
                    props.position.as_secs_f64()
                } else {
                    0.0
                };

                // Estimate one-way network latency using timestamps (fallback 50ms)
                let now_ms = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis() as u64;
                let latency = if now_ms > event.timestamp {
                    (now_ms - event.timestamp) as f64 / 1000.0
                } else {
                    0.050
                };

                let target_playing = event.playing.unwrap_or(false);
                let expected_remote_pos = if target_playing {
                    event.position + latency
                } else {
                    event.position
                };
                let drift = local_pos - expected_remote_pos;

                if event.event_type == EventType::State {
                    // STATE events: soft drift correction ONLY (no play/pause/seek)
                    if target_playing && local_playing {
                        if drift.abs() > 0.250 {
                            let target_rate = if drift > 0.0 { 0.95 } else { 1.05 };
                            let _ = session.set_playback_rate(target_rate);
                        } else if drift.abs() < 0.080 {
                            let _ = session.set_playback_rate(1.0);
                        }
                    }
                } else {
                    // Explicit commands (PLAY, PAUSE, SEEK): apply state + position changes
                    if target_playing != local_playing {
                        if target_playing {
                            let _ = session.play();
                        } else {
                            let _ = session.pause();
                        }
                    }

                    if drift.abs() > 1.5 {
                        let _ = session.seek(Duration::from_secs_f64(expected_remote_pos));
                    }
                }
            }
        }
    }
}
```

### [protocol.rs](file:///c:/PVR/lumi/src/protocol.rs)
```rust
use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum EventType {
    #[serde(rename = "PLAY")]
    Play,
    #[serde(rename = "PAUSE")]
    Pause,
    #[serde(rename = "SEEK")]
    Seek,
    #[serde(rename = "STATE")]
    State,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LumiEvent {
    #[serde(rename = "type")]
    pub event_type: EventType,
    pub device_id: String,
    pub event_id: String,
    pub timestamp: u64,
    pub position: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub playing: Option<bool>,
}

impl LumiEvent {
    pub fn new(event_type: EventType, device_id: &str, position: f64, playing: Option<bool>) -> Self {
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;

        Self {
            event_type,
            device_id: device_id.to_string(),
            event_id: Uuid::new_v4().to_string(),
            timestamp,
            position,
            playing,
        }
    }
}
```
