use crate::adapter::{MediaMetadata, MediaSession};
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
        let success = self.session.TryPlayAsync()?.get()?;
        println!("[WindowsMediaSession] play() success status: {}", success);
        Ok(())
    }

    fn pause(&self) -> Result<(), Box<dyn std::error::Error>> {
        let success = self.session.TryPauseAsync()?.get()?;
        println!("[WindowsMediaSession] pause() success status: {}", success);
        Ok(())
    }

    fn seek(&self, position: Duration) -> Result<(), Box<dyn std::error::Error>> {
        let ticks = (position.as_nanos() / 100) as i64;
        let success = self.session.TryChangePlaybackPositionAsync(ticks)?.get()?;
        println!("[WindowsMediaSession] seek() success status: {}", success);
        Ok(())
    }
}
