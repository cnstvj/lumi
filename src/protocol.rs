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
    #[serde(rename = "NEXT")]
    Next,
    #[serde(rename = "PREVIOUS")]
    Previous,
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
