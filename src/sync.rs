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
            println!("[SyncEngine] Received remote event: {:?} from {}", event.event_type, event.device_id);
            
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
                match event.event_type {
                    EventType::Play => {
                        if let Err(e) = session.play() {
                            eprintln!("[SyncEngine] Failed to play: {}", e);
                        }
                    }
                    EventType::Pause => {
                        if let Err(e) = session.pause() {
                            eprintln!("[SyncEngine] Failed to pause: {}", e);
                        }
                    }
                    EventType::Seek => {
                        let pos = Duration::from_secs_f64(event.position);
                        if let Err(e) = session.seek(pos) {
                            eprintln!("[SyncEngine] Failed to seek: {}", e);
                        }
                    }
                    EventType::State => {
                        // ignore state in phase 1
                    }
                }
            } else {
                eprintln!("[SyncEngine] Dropped event because no active MediaSession is bound.");
            }
        }
    }
}
