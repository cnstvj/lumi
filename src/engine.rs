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

pub enum SessionEvent {
    PlaybackStatusChanged(String, PlaybackStatus),
    MetadataChanged(String, MediaMetadata),
    TimelineChanged(String, TimelineProperties),
}
