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
