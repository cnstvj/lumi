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
