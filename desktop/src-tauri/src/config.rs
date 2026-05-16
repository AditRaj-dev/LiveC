/// Config module — persistent app configuration stored in Tauri's app data directory.
///
/// On first launch, generates a unique `device_id` and `room_token`.
/// Config is stored as JSON at `<app_data>/livec_config.json`.

use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use tauri::Manager;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairedDevice {
    pub device_id: String,
    pub device_name: String,
    pub platform: String, // "windows" | "android" | "unknown"
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppConfig {
    pub device_id: String,
    pub device_name: String,
    pub room_token: String,
    pub relay_url: String,
    pub screenshot_folder: String,
    pub paired_devices: Vec<PairedDevice>,
}

impl Default for AppConfig {
    fn default() -> Self {
        let hostname = hostname::get()
            .map(|h| h.to_string_lossy().to_string())
            .unwrap_or_else(|_| "Windows PC".to_string());

        let screenshots = dirs::home_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("Pictures")
            .join("Screenshots")
            .to_string_lossy()
            .to_string();

        Self {
            device_id: Uuid::new_v4().to_string(),
            device_name: hostname,
            room_token: Uuid::new_v4().to_string()[..8].to_string(), // short code
            relay_url: "ws://localhost:3000/ws".to_string(),
            screenshot_folder: screenshots,
            paired_devices: Vec::new(),
        }
    }
}

impl AppConfig {
    fn config_path(app: &tauri::AppHandle) -> PathBuf {
        app.path()
            .app_data_dir()
            .unwrap_or_else(|_| PathBuf::from("."))
            .join("livec_config.json")
    }

    pub fn load(app: &tauri::AppHandle) -> Self {
        let path = Self::config_path(app);
        if let Ok(data) = std::fs::read_to_string(&path) {
            if let Ok(mut cfg) = serde_json::from_str::<AppConfig>(&data) {
                // Fix any stale relay URLs saved before normalization was added
                cfg.relay_url = normalize_relay_url(&cfg.relay_url);
                return cfg;
            }
        }
        let cfg = AppConfig::default();
        let _ = cfg.save(app);
        cfg
    }

    pub fn save(&self, app: &tauri::AppHandle) -> Result<(), String> {
        let path = Self::config_path(app);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let data = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        std::fs::write(path, data).map_err(|e| e.to_string())
    }
}

// ─── Global config state ─────────────────────────────────────────────────────
use std::sync::{Arc, RwLock};

pub type SharedConfig = Arc<RwLock<AppConfig>>;

pub fn init_config(app: &tauri::AppHandle) -> SharedConfig {
    Arc::new(RwLock::new(AppConfig::load(app)))
}

// ─── Tauri commands ──────────────────────────────────────────────────────────

#[tauri::command]
pub fn get_config(state: tauri::State<SharedConfig>) -> AppConfig {
    state.read().unwrap().clone()
}

#[tauri::command]
pub fn update_device_name(
    name: String,
    state: tauri::State<SharedConfig>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    let mut cfg = state.write().unwrap();
    cfg.device_name = name;
    cfg.save(&app)
}

#[tauri::command]
pub fn update_relay_url(
    url: String,
    state: tauri::State<SharedConfig>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    let mut cfg = state.write().unwrap();
    cfg.relay_url = normalize_relay_url(&url);
    cfg.save(&app)
}

/// Accept any form the user might paste and store a canonical ws[s]://…/ws URL.
///
/// Examples:
///   https://foo.trycloudflare.com      → wss://foo.trycloudflare.com/ws
///   http://localhost:3000              → ws://localhost:3000/ws
///   wss://foo.trycloudflare.com/ws    → wss://foo.trycloudflare.com/ws  (unchanged)
pub fn normalize_relay_url(raw: &str) -> String {
    let trimmed = raw.trim().trim_end_matches('/');
    let ws = if trimmed.starts_with("https://") {
        trimmed.replacen("https://", "wss://", 1)
    } else if trimmed.starts_with("http://") {
        trimmed.replacen("http://", "ws://", 1)
    } else {
        trimmed.to_string()
    };
    if ws.ends_with("/ws") { ws } else { format!("{}/ws", ws) }
}


#[tauri::command]
pub fn update_screenshot_folder(
    folder: String,
    state: tauri::State<SharedConfig>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    let mut cfg = state.write().unwrap();
    cfg.screenshot_folder = folder;
    cfg.save(&app)
}
