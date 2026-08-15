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
    #[allow(dead_code)]
    fn get_metadata(&self) -> Result<MediaMetadata, Box<dyn std::error::Error>>;

    fn play(&self) -> Result<(), Box<dyn std::error::Error>>;
    fn pause(&self) -> Result<(), Box<dyn std::error::Error>>;
    fn seek(&self, position: Duration) -> Result<(), Box<dyn std::error::Error>>;
    fn get_timeline_properties(&self) -> Result<TimelineProperties, Box<dyn std::error::Error>>;
    fn set_playback_rate(&self, rate: f64) -> Result<(), Box<dyn std::error::Error>>;
}
