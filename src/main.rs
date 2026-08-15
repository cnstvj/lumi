mod adapter;
mod constants;
mod engine;
mod gui;
mod handler;
mod network;
mod protocol;
mod sync;
mod windows_adapter;

use crate::engine::Engine;
use crate::gui::LumiApp;
use std::sync::Arc;

fn main() -> Result<(), eframe::Error> {
    let rt = tokio::runtime::Runtime::new().expect("Failed to create Tokio runtime");
    let _guard = rt.enter();

    let engine = Arc::new(Engine::new().expect("Failed to initialize system media engine"));
    
    let options = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_inner_size([500.0, 500.0])
            .with_resizable(true),
        ..Default::default()
    };
    
    eframe::run_native(
        "Lumi Desktop Sync",
        options,
        Box::new(|cc| Box::new(LumiApp::new(cc, engine))),
    )
}
