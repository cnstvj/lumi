use crate::engine::Engine;
use crate::network::WebSocketTransport;
use crate::protocol::{EventType, LumiEvent};
use crate::handler::create_session_handler;
use crate::sync::SyncEngine;
use crate::adapter::MediaSession;

use eframe::egui;
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Instant, Duration};
use tokio::sync::mpsc;

pub struct LumiApp {
    coordinator_input: String,
    room_input: String,

    engine: Arc<Engine>,
    transport: Arc<Mutex<Option<Arc<WebSocketTransport>>>>,
    last_network_event: Arc<Mutex<Option<(Instant, EventType)>>>,

    // Shared UI state
    is_host: Arc<AtomicBool>,
    status: Arc<Mutex<String>>,
    bound_session_name: Arc<Mutex<Option<String>>>,

    sessions: Vec<crate::windows_adapter::WindowsMediaSession>,
    refresh_timer: Instant,
}

impl LumiApp {
    pub fn new(cc: &eframe::CreationContext<'_>, engine: Arc<Engine>) -> Self {
        // Set modern dark styling
        let mut style = (*cc.egui_ctx.style()).clone();
        style.visuals = egui::Visuals::dark();
        style.visuals.window_rounding = 8.0.into();
        style.visuals.widgets.noninteractive.bg_fill = egui::Color32::from_rgb(26, 26, 32);
        style.visuals.widgets.inactive.bg_fill = egui::Color32::from_rgb(38, 38, 48);
        style.visuals.widgets.hovered.bg_fill = egui::Color32::from_rgb(48, 48, 64);
        style.visuals.widgets.active.bg_fill = egui::Color32::from_rgb(63, 101, 240); // Sleek Indigo accent
        cc.egui_ctx.set_style(style);

        Self {
            coordinator_input: crate::constants::DEFAULT_COORDINATOR_URL.to_string(),
            room_input: crate::constants::DEFAULT_ROOM_CODE.to_string(),
            engine,
            transport: Arc::new(Mutex::new(None)),
            last_network_event: Arc::new(Mutex::new(None)),
            is_host: Arc::new(AtomicBool::new(false)),
            status: Arc::new(Mutex::new("Disconnected".to_string())),
            bound_session_name: Arc::new(Mutex::new(None)),
            sessions: Vec::new(),
            refresh_timer: Instant::now() - Duration::from_secs(5),
        }
    }

    fn connect_room(&mut self) {
        let coordinator = self.coordinator_input.clone();
        let room = self.room_input.clone();
        
        let status_lock = self.status.clone();
        let is_host_lock = self.is_host.clone();
        let last_net = self.last_network_event.clone();
        let engine_clone = self.engine.clone();
        let transport_lock = self.transport.clone();

        *status_lock.lock().unwrap() = "Connecting...".to_string();

        let (tx, rx) = mpsc::channel(100);

        let status_lock_cb = status_lock.clone();
        let is_host_cb = is_host_lock.clone();
        
        let on_host_status = move |status: bool| {
            is_host_cb.store(status, Ordering::Relaxed);
        };

        tokio::spawn(async move {
            let transport_opt = {
                let res = WebSocketTransport::connect(
                    &coordinator,
                    &room,
                    tx,
                    on_host_status,
                ).await;
                match res {
                    Ok(t) => Some(Arc::new(t)),
                    Err(e) => {
                        *status_lock_cb.lock().unwrap() = format!("Error: {}", e);
                        None
                    }
                }
            };

            if let Some(transport_arc) = transport_opt {
                *transport_lock.lock().unwrap() = Some(transport_arc);
                *status_lock_cb.lock().unwrap() = format!("Connected (Room {})", room);

                // Sync Engine
                let sync_engine = SyncEngine::new(
                    rx,
                    engine_clone.get_active_session_arc(),
                    last_net.clone(),
                );
                sync_engine.run().await;
            }
        });
    }

    fn disconnect_room(&mut self) {
        *self.transport.lock().unwrap() = None;
        self.is_host.store(false, Ordering::Relaxed);
        *self.status.lock().unwrap() = "Disconnected".to_string();
    }
}

impl eframe::App for LumiApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Query sessions list periodically
        if self.refresh_timer.elapsed() >= Duration::from_secs(crate::constants::SESSION_REFRESH_INTERVAL_SEC) {
            self.refresh_timer = Instant::now();
            if let Ok(list) = self.engine.list_sessions() {
                self.sessions = list;
            }
        }

        // Top Navigation Bar
        egui::TopBottomPanel::top("top_panel").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.heading("✨ Lumi Sync");

                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let status_str = self.status.lock().unwrap().clone();
                    
                    // Connected/Disconnected indicator tag
                    let tag_color = if status_str.starts_with("Connected") {
                        egui::Color32::from_rgb(16, 185, 129) // Emerald
                    } else if status_str.starts_with("Connecting") {
                        egui::Color32::from_rgb(245, 158, 11) // Amber
                    } else {
                        egui::Color32::from_rgb(239, 68, 68) // Rose
                    };

                    ui.colored_label(tag_color, &status_str);
                    ui.label("Network:");
                });
            });
        });

        // Main Panel Content
        egui::CentralPanel::default().show(ctx, |ui| {
            // Role Tag Card (Host vs Follower status bar)
            let is_host = self.is_host.load(Ordering::Relaxed);
            let is_connected = self.transport.lock().unwrap().is_some();
            
            if is_connected {
                let (role_text, bg_color) = if is_host {
                    ("YOU ARE HOST 👑 (Broadcasting Timeline)", egui::Color32::from_rgb(16, 185, 129))
                } else {
                    ("YOU ARE FOLLOWER 👥 (Syncing to Host)", egui::Color32::from_rgb(59, 130, 246))
                };
                
                egui::Frame::none()
                    .fill(bg_color.linear_multiply(0.1))
                    .stroke(egui::Stroke::new(1.0, bg_color))
                    .rounding(6.0)
                    .inner_margin(8.0)
                    .show(ui, |ui| {
                        ui.vertical_centered(|ui| {
                            ui.colored_label(bg_color, role_text);
                        });
                    });
                ui.add_space(8.0);
            }

                    ui.group(|ui| {
                        ui.heading("Room Settings");
                        ui.add_space(4.0);

                        ui.horizontal(|ui| {
                            ui.label("Room Code: ");
                            ui.text_edit_singleline(&mut self.room_input);
                            
                            if is_connected {
                                if ui.button("Leave Room").clicked() {
                                    self.disconnect_room();
                                }
                            } else {
                                if ui.button("Join Room").clicked() {
                                    self.connect_room();
                                }
                            }
                        });
                    });
                    ui.add_space(10.0);

                    // Media Session Binding Panel
                    ui.group(|ui| {
                        ui.heading("Media Sessions List");
                        ui.add_space(4.0);

                        if self.sessions.is_empty() {
                            ui.label("No active media sessions detected.");
                        } else {
                            egui::ScrollArea::vertical().max_height(140.0).show(ui, |ui| {
                                for session in &self.sessions {
                                    let app_id = session.get_source_app_id();
                                    
                                    ui.horizontal(|ui| {
                                        ui.label(&app_id);
                                        
                                        let current_bound = self.bound_session_name.lock().unwrap().clone();
                                        let is_bound = current_bound.map(|b| b == app_id).unwrap_or(false);
                                        
                                        if is_bound {
                                            ui.weak("(Bound)");
                                        } else {
                                            if ui.button("Bind").clicked() {
                                                *self.bound_session_name.lock().unwrap() = Some(app_id.clone());
                                                
                                                // Register handler logic
                                                let transport_lock = self.transport.clone();
                                                let last_net = self.last_network_event.clone();
                                                let is_host_arc = self.is_host.clone();
                                                let engine_arc = self.engine.clone();

                                                let transport_opt = transport_lock.lock().unwrap().clone();
                                                if let Some(transport) = transport_opt {
                                                    let handler = create_session_handler(
                                                        engine_arc.clone(),
                                                        transport,
                                                        last_net,
                                                        is_host_arc,
                                                    );
                                                    let _ = engine_arc.bind_session(
                                                        crate::windows_adapter::WindowsMediaSession::new(session.get_raw().clone()),
                                                        handler
                                                    );
                                                }
                                            }
                                        }
                                    });
                                }
                            });
                        }
                    });
                    ui.add_space(10.0);

                    // Local Control Panel (Warnings displayed for Followers)
                    if let Some(session) = self.engine.get_bound_session() {
                        ui.group(|ui| {
                            ui.heading(format!("Playback Controls ({})", session.get_source_app_id()));
                            ui.add_space(4.0);

                            if !is_host && is_connected {
                                ui.colored_label(
                                    egui::Color32::from_rgb(245, 158, 11),
                                    "⚠️ You are a Follower. Actions here will only apply locally and won't broadcast."
                                );
                            }

                            ui.horizontal(|ui| {
                                if ui.button("▶ Play").clicked() {
                                    let pos = session.get_timeline_properties().map(|p| p.position.as_secs_f64()).unwrap_or(0.0);
                                    if session.play().is_ok() && is_host {
                                        let transport_opt = self.transport.lock().unwrap().clone();
                                        if let Some(transport) = transport_opt {
                                            let event = LumiEvent::new(EventType::Play, "windows-device-1", pos, Some(true));
                                            tokio::spawn(async move {
                                                let _ = transport.send_event(&event).await;
                                            });
                                        }
                                    }
                                }

                                if ui.button("⏸ Pause").clicked() {
                                    let pos = session.get_timeline_properties().map(|p| p.position.as_secs_f64()).unwrap_or(0.0);
                                    if session.pause().is_ok() && is_host {
                                        let transport_opt = self.transport.lock().unwrap().clone();
                                        if let Some(transport) = transport_opt {
                                            let event = LumiEvent::new(EventType::Pause, "windows-device-1", pos, Some(false));
                                            tokio::spawn(async move {
                                                let _ = transport.send_event(&event).await;
                                            });
                                        }
                                    }
                                }
                            });

                            if let Ok(timeline) = session.get_timeline_properties() {
                                let mut current_pos = timeline.position.as_secs_f64();
                                let duration = timeline.duration.as_secs_f64();

                                ui.horizontal(|ui| {
                                    ui.label("Position: ");
                                    if ui.add(egui::Slider::new(&mut current_pos, 0.0..=duration).text("s")).changed() {
                                        if session.seek(Duration::from_secs_f64(current_pos)).is_ok() && is_host {
                                            let transport_opt = self.transport.lock().unwrap().clone();
                                            if let Some(transport) = transport_opt {
                                                let is_playing = if let Ok(info) = session.get_raw().GetPlaybackInfo() {
                                                    matches!(
                                                        info.PlaybackStatus(),
                                                        Ok(windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing)
                                                    )
                                                } else {
                                                    false
                                                };
                                                let event = LumiEvent::new(EventType::Seek, crate::constants::DEVICE_ID, current_pos, Some(is_playing));
                                                tokio::spawn(async move {
                                                    let _ = transport.send_event(&event).await;
                                                });
                                            }
                                        }
                                    }
                                });
                            }
                        });
                    }
        });
        
        // Request immediate repaint to keep the log stream and timers updating dynamically
        ctx.request_repaint_after(Duration::from_millis(crate::constants::REPAINT_INTERVAL_MS));
    }
}
