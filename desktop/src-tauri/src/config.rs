/// Config module — persistent app configuration stored in Tauri's app data directory.
///
/// On first launch, generates a unique `device_id` and `room_token`.
/// Config is stored as JSON at `<app_data>/livec_config.json`.

use ed25519_dalek::SigningKey;
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::PathBuf;
use tauri::Manager;
use uuid::Uuid;

const KEYRING_SERVICE: &str = "LiveC";
const KEYRING_USER: &str = "device_signing_key";

/// Store the private key (hex-encoded) in the OS keychain.
fn store_privkey(hex: &str) -> Result<(), String> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER)
        .and_then(|e| e.set_password(hex))
        .map_err(|e| e.to_string())
}

/// Load the private key (hex-encoded) from the OS keychain.
/// Returns `None` if no entry exists yet.
fn load_privkey() -> Option<String> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER)
        .ok()?
        .get_password()
        .ok()
}

/// Generate a real Ed25519 keypair and return `(pubkey_hex, fingerprint_hex, privkey_hex)`.
/// The fingerprint is SHA-256(pubkey)[..16].
fn generate_identity() -> (String, String, String) {
    use rand::RngCore;
    let mut secret = [0u8; 32];
    OsRng.fill_bytes(&mut secret);
    let signing_key = SigningKey::from_bytes(&secret);
    let pubkey = signing_key.verifying_key().to_bytes(); // [u8; 32]
    let privkey = signing_key.to_bytes();                 // [u8; 32]
    let fingerprint = Sha256::digest(pubkey);
    (
        hex::encode(pubkey),
        hex::encode(&fingerprint[..16]),
        hex::encode(privkey),
    )
}

/// Return the device `SigningKey` by loading the private key from the OS keychain.
/// Returns `None` if the key is not stored or cannot be decoded.
pub fn signing_key() -> Option<SigningKey> {
    let hex = load_privkey()?;
    let bytes = hex::decode(&hex).ok()?;
    let arr: [u8; 32] = bytes.try_into().ok()?;
    Some(SigningKey::from_bytes(&arr))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairedDevice {
    pub device_id: String,
    pub device_name: String,
    pub platform: String, // "windows" | "android" | "unknown"
}

/// A peer we've explicitly trusted (Phase 5b). Keyed by hex fingerprint.
/// `quick_mode = true` means incoming file_offers from this peer auto-accept.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TrustedPeer {
    pub fingerprint: String,
    pub device_name: String,
    pub added_at: u64,        // unix ms
    pub quick_mode: bool,
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

    // Phase 5: per-device identity. Pubkey is hex(32 bytes), fingerprint is hex(16 bytes).
    // Both default to empty for back-compat with v0 configs; `load()` populates them on read.
    #[serde(default)]
    pub device_pubkey: String,
    #[serde(default)]
    pub fingerprint: String,

    // Phase 5b: identity version. 0 = legacy opaque bytes, 1 = real Ed25519 keypair.
    // Defaults to 0 so old configs are recognised as needing a migration on next load.
    #[serde(default)]
    pub identity_version: u32,

    // Phase 5b: trusted-peer list. Empty by default.
    #[serde(default)]
    pub trusted_peers: Vec<TrustedPeer>,
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

        let (pubkey, fingerprint, privkey) = generate_identity();
        // Best-effort: store privkey in OS keychain. If the keychain is unavailable on
        // this platform, we'll retry in `load()` once the app handle is available.
        let _ = store_privkey(&privkey);

        Self {
            device_id: Uuid::new_v4().to_string(),
            device_name: hostname,
            room_token: Uuid::new_v4().to_string()[..8].to_string(), // short code
            relay_url: "ws://localhost:3000/ws".to_string(),
            screenshot_folder: screenshots,
            paired_devices: Vec::new(),
            device_pubkey: pubkey,
            fingerprint,
            identity_version: 1,
            trusted_peers: Vec::new(),
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
                // Migrate to Phase 5b real Ed25519 identity if:
                //   - identity_version < 1 (legacy opaque bytes), OR
                //   - device_pubkey is empty (pre-Phase-5 config), OR
                //   - private key is missing from the OS keychain
                let needs_identity = cfg.identity_version < 1
                    || cfg.device_pubkey.is_empty()
                    || cfg.fingerprint.is_empty()
                    || load_privkey().is_none();
                if needs_identity {
                    let (pubkey, fingerprint, privkey) = generate_identity();
                    cfg.device_pubkey = pubkey;
                    cfg.fingerprint = fingerprint;
                    cfg.identity_version = 1;
                    // Clear stale trusted-peer entries — their fingerprints referenced
                    // the old placeholder identity and are no longer valid.
                    cfg.trusted_peers.clear();
                    let _ = store_privkey(&privkey);
                    let _ = cfg.save(app);
                }
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

// ─── Phase 5b: Trusted peers ─────────────────────────────────────────────────

#[tauri::command]
pub fn get_trusted_peers(state: tauri::State<SharedConfig>) -> Vec<TrustedPeer> {
    state.read().unwrap().trusted_peers.clone()
}

/// Check whether a fingerprint is trusted. Empty fingerprints are never trusted.
pub fn is_trusted(state: &SharedConfig, fingerprint: &str) -> bool {
    if fingerprint.is_empty() { return false; }
    state.read().unwrap().trusted_peers.iter().any(|p| p.fingerprint == fingerprint)
}

/// Check whether a peer is trusted AND in quick_mode.
pub fn is_quick_mode(state: &SharedConfig, fingerprint: &str) -> bool {
    if fingerprint.is_empty() { return false; }
    state.read().unwrap().trusted_peers.iter()
        .any(|p| p.fingerprint == fingerprint && p.quick_mode)
}

#[tauri::command]
pub fn add_trusted_peer(
    fingerprint: String,
    device_name: String,
    quick_mode: Option<bool>,
    state: tauri::State<SharedConfig>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    if fingerprint.is_empty() {
        return Err("Empty fingerprint".to_string());
    }
    let mut cfg = state.write().unwrap();
    if let Some(existing) = cfg.trusted_peers.iter_mut().find(|p| p.fingerprint == fingerprint) {
        existing.device_name = device_name;
        if let Some(qm) = quick_mode { existing.quick_mode = qm; }
    } else {
        cfg.trusted_peers.push(TrustedPeer {
            fingerprint,
            device_name,
            added_at: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0),
            quick_mode: quick_mode.unwrap_or(false),
        });
    }
    cfg.save(&app)
}

#[tauri::command]
pub fn remove_trusted_peer(
    fingerprint: String,
    state: tauri::State<SharedConfig>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    let mut cfg = state.write().unwrap();
    cfg.trusted_peers.retain(|p| p.fingerprint != fingerprint);
    cfg.save(&app)
}

#[tauri::command]
pub fn set_quick_mode(
    fingerprint: String,
    enabled: bool,
    state: tauri::State<SharedConfig>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    let mut cfg = state.write().unwrap();
    if let Some(peer) = cfg.trusted_peers.iter_mut().find(|p| p.fingerprint == fingerprint) {
        peer.quick_mode = enabled;
        cfg.save(&app)
    } else {
        Err("Peer not in trusted list".to_string())
    }
}
