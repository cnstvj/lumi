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

    fn next(&self) -> Result<(), Box<dyn std::error::Error>> {
        self.session.TrySkipNextAsync()?.get()?;
        Ok(())
    }

    fn previous(&self) -> Result<(), Box<dyn std::error::Error>> {
        self.session.TrySkipPreviousAsync()?.get()?;
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
