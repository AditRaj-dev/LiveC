/// Connection module — WebSocket relay client with auto-reconnect.
///
/// Connects to the relay server, sends `device_join`, and forwards
/// incoming messages to the frontend via Tauri events.
///
/// Auto-reconnect: exponential backoff 1s → 2s → 4s … max 30s.
///
/// Events emitted to frontend:
///   connection:status   { connected: bool, relayUrl: string }
///   relay:message       { type, from, to, payload }
///
/// Tauri commands:
///   send_relay_message  — send a raw JSON message to the relay
///   get_connection_status — returns current connected state

use std::collections::HashMap;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::{mpsc, oneshot};
use tokio_tungstenite::{connect_async, tungstenite::Message as WsMessage};

use crate::config::SharedConfig;
use crate::protocol::{Message, MsgDedup};

static CONNECTED: AtomicBool = AtomicBool::new(false);

// ─── Device registry (polled by toast / overlay windows) ──────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceInfo {
    pub id: String,
    pub label: String,
    pub platform: String,
    #[serde(default)]
    pub fingerprint: String,
}

/// Result of a pending file offer — resolved when sender receives file_accept/reject.
pub enum OfferResult {
    Accepted { upload_tokens: HashMap<String, String> },
    Rejected,
}

lazy_static::lazy_static! {
    static ref ROOM_DEVICES: Mutex<Vec<DeviceInfo>> = Mutex::new(Vec::new());
    // Shared dedup across relay and LAN paths — drops the second copy of any message.
    static ref DEDUP: Mutex<MsgDedup> = Mutex::new(MsgDedup::new(500));
    // Pending outbound offers awaiting file_accept/file_reject from the recipient.
    static ref PENDING_OFFERS: Mutex<HashMap<String, oneshot::Sender<OfferResult>>> =
        Mutex::new(HashMap::new());
}

/// Register a pending offer. Returns a receiver that resolves on accept/reject.
pub fn register_pending_offer(offer_id: &str) -> oneshot::Receiver<OfferResult> {
    let (tx, rx) = oneshot::channel();
    PENDING_OFFERS.lock().unwrap().insert(offer_id.to_string(), tx);
    rx
}

fn resolve_pending_offer(offer_id: &str, result: OfferResult) {
    if let Some(tx) = PENDING_OFFERS.lock().unwrap().remove(offer_id) {
        let _ = tx.send(result);
    }
}

/// Returns true if this message ID is new (not seen before). Thread-safe.
pub fn check_dedup(id: &str) -> bool {
    DEDUP.lock().unwrap().check_and_insert(id)
}

fn add_device(id: &str, label: &str, platform: &str, fingerprint: &str) {
    let mut devs = ROOM_DEVICES.lock().unwrap();
    if let Some(existing) = devs.iter_mut().find(|d| d.id == id) {
        // Refresh fingerprint if it arrived after the initial join (LAN-vs-relay race).
        if existing.fingerprint.is_empty() && !fingerprint.is_empty() {
            existing.fingerprint = fingerprint.to_string();
        }
        return;
    }
    devs.push(DeviceInfo {
        id: id.to_string(),
        label: label.to_string(),
        platform: platform.to_string(),
        fingerprint: fingerprint.to_string(),
    });
}

fn remove_device(id: &str) {
    let mut devs = ROOM_DEVICES.lock().unwrap();
    devs.retain(|d| d.id != id);
}

fn clear_devices() {
    ROOM_DEVICES.lock().unwrap().clear();
}

#[tauri::command]
pub fn get_room_devices() -> Vec<DeviceInfo> {
    ROOM_DEVICES.lock().unwrap().clone()
}

/// Look up a peer's fingerprint from the registry by their device_id.
fn lookup_peer_fingerprint(device_id: &str) -> String {
    ROOM_DEVICES.lock().unwrap().iter()
        .find(|d| d.id == device_id)
        .map(|d| d.fingerprint.clone())
        .unwrap_or_default()
}

pub fn is_connected() -> bool {
    CONNECTED.load(Ordering::SeqCst)
}

// ─── Outbound sender (shared between reconnect loops) ─────────────────────────
type TxSender = Arc<Mutex<Option<mpsc::UnboundedSender<String>>>>;

lazy_static::lazy_static! {
    static ref TX: TxSender = Arc::new(Mutex::new(None));
}

fn set_tx(tx: mpsc::UnboundedSender<String>) {
    *TX.lock().unwrap() = Some(tx);
}

fn clear_tx() {
    *TX.lock().unwrap() = None;
}

pub fn send_raw(json: String) -> Result<(), String> {
    let lock = TX.lock().unwrap();
    if let Some(tx) = lock.as_ref() {
        tx.send(json).map_err(|e| e.to_string())
    } else {
        Err("Not connected".to_string())
    }
}

// ─── Startup ─────────────────────────────────────────────────────────────────

pub fn start_connection(app: AppHandle, config: SharedConfig) {
    tauri::async_runtime::spawn(async move {
        let mut backoff = 1u64;

        loop {
            let (relay_url, device_id, device_name, room_token, fingerprint) = {
                let cfg = config.read().unwrap();
                (
                    cfg.relay_url.clone(),
                    cfg.device_id.clone(),
                    cfg.device_name.clone(),
                    cfg.room_token.clone(),
                    cfg.fingerprint.clone(),
                )
            };

            eprintln!("[connection] Connecting to {}", relay_url);

            match connect_async(&relay_url).await {
                Ok((ws_stream, _)) => {
                    backoff = 1; // reset backoff on success
                    CONNECTED.store(true, Ordering::SeqCst);
                    let _ = app.emit(
                        "connection:status",
                        serde_json::json!({ "connected": true, "relayUrl": relay_url }),
                    );

                    let (mut write, mut read) = ws_stream.split();

                    // Create outbound channel
                    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
                    set_tx(tx);

                    // Send device_join
                    let join_msg = Message::device_join(&device_id, &device_name, &room_token, "windows", &fingerprint);
                    if let Ok(json) = serde_json::to_string(&join_msg) {
                        let _ = write.send(WsMessage::Text(json.into())).await;
                    }

                    // Outbound task
                    let write_task = tokio::spawn(async move {
                        while let Some(msg) = rx.recv().await {
                            if write.send(WsMessage::Text(msg.into())).await.is_err() {
                                break;
                            }
                        }
                    });

                    // Heartbeat task
                    let hb_device_id = device_id.clone();
                    let hb_room = room_token.clone();
                    let heartbeat = tokio::spawn(async move {
                        let mut interval = tokio::time::interval(Duration::from_secs(25));
                        loop {
                            interval.tick().await;
                            let ping = Message::ping(&hb_device_id, &hb_room);
                            if let Ok(json) = serde_json::to_string(&ping) {
                                if send_raw(json).is_err() { break; }
                            }
                        }
                    });

                    // Inbound loop
                    while let Some(Ok(msg)) = read.next().await {
                        if let WsMessage::Text(text) = msg {
                            if let Ok(m) = serde_json::from_str::<Message>(&text) {
                                // Skip our own echoes — some relays broadcast back to the sender,
                                // which would otherwise round-trip a clipboard_text and add a
                                // phantom "remote" duplicate of every local copy.
                                if m.from == device_id { continue; }
                                if m.to == device_id || m.to == "broadcast" {
                                    if check_dedup(&m.id) {
                                        handle_message(&app, &m);
                                    }
                                }
                            }
                        }
                    }

                    // Connection dropped
                    write_task.abort();
                    heartbeat.abort();
                    clear_tx();
                    clear_devices();
                    CONNECTED.store(false, Ordering::SeqCst);
                    let _ = app.emit(
                        "connection:status",
                        serde_json::json!({ "connected": false, "relayUrl": relay_url }),
                    );
                    eprintln!("[connection] Disconnected. Retrying in {}s...", backoff);
                }
                Err(e) => {
                    eprintln!("[connection] Failed to connect: {e}. Retrying in {}s...", backoff);
                    CONNECTED.store(false, Ordering::SeqCst);
                    let _ = app.emit(
                        "connection:status",
                        serde_json::json!({ "connected": false, "relayUrl": relay_url }),
                    );
                }
            }

            tokio::time::sleep(Duration::from_secs(backoff)).await;
            backoff = (backoff * 2).min(30);
        }
    });
}

// ─── Message handler ─────────────────────────────────────────────────────────

pub fn handle_message(app: &AppHandle, msg: &Message) {
    match msg.kind.as_str() {
        "clipboard_text" => {
            if let Some(text) = msg.payload.get("text").and_then(Value::as_str) {
                // Write to local clipboard
                let _ = crate::clipboard::write_text(text);
                // Notify frontend
                let _ = app.emit(
                    "relay:clipboard_text",
                    serde_json::json!({
                        "text": text,
                        "from": msg.from,
                    }),
                );
            }
        }
        "clipboard_image" => {
            let _ = app.emit(
                "relay:clipboard_image",
                serde_json::json!({
                    "fileId":      msg.payload.get("fileId").and_then(Value::as_str).unwrap_or(""),
                    "downloadUrl": msg.payload.get("downloadUrl").and_then(Value::as_str).unwrap_or(""),
                    "from":        msg.from,
                }),
            );
        }
        "device_join" => {
            // Track in registry for polling by toast/overlay
            let device_id = msg.payload.get("deviceId").and_then(Value::as_str).unwrap_or("");
            let device_name = msg.payload.get("deviceName").and_then(Value::as_str).unwrap_or("");
            let platform = msg.payload.get("platform").and_then(Value::as_str).unwrap_or("");
            let fingerprint = msg.payload.get("fingerprint").and_then(Value::as_str).unwrap_or("");
            if !device_id.is_empty() {
                add_device(device_id, device_name, platform, fingerprint);
            }
            let _ = app.emit("relay:device_join", &msg.payload);

            // Phase 5b: notify UI when a new fingerprint joins that we haven't trusted yet.
            if !fingerprint.is_empty() {
                let cfg_state = app.state::<crate::config::SharedConfig>();
                if !crate::config::is_trusted(&cfg_state, fingerprint) {
                    let _ = app.emit("relay:untrusted_peer", serde_json::json!({
                        "deviceId":    device_id,
                        "deviceName":  device_name,
                        "platform":    platform,
                        "fingerprint": fingerprint,
                    }));
                }
            }
        }
        "device_leave" => {
            let device_id = msg.payload.get("deviceId").and_then(Value::as_str)
                .or_else(|| Some(&msg.from as &str))
                .unwrap_or("");
            if !device_id.is_empty() {
                remove_device(device_id);
            }
            let _ = app.emit("relay:device_leave", &msg.payload);
        }
        "clipboard_clear" => {
            let _ = app.emit("relay:clipboard_clear", serde_json::json!({ "from": msg.from }));
        }
        "files_clear" => {
            let _ = app.emit("relay:files_clear", serde_json::json!({ "from": msg.from }));
        }
        "file_meta" => {
            let _ = app.emit("relay:file_meta", serde_json::json!({
                "fileId":      msg.payload.get("fileId").and_then(Value::as_str).unwrap_or(&msg.id),
                "name":        msg.payload.get("name").and_then(Value::as_str).unwrap_or("file"),
                "size":        msg.payload.get("size").and_then(Value::as_i64).unwrap_or(0),
                "downloadUrl": msg.payload.get("downloadUrl").and_then(Value::as_str).unwrap_or(""),
                "from":        msg.from,
            }));
        }
        "file_expired" => {
            let _ = app.emit("relay:file_expired", serde_json::json!({
                "fileId": msg.payload.get("fileId").and_then(Value::as_str).unwrap_or(""),
            }));
        }
        // ── Two-phase transfer messages ──────────────────────────────────────
        "file_offer" => {
            // Incoming offer from a remote sender — emit for UI to show accept/reject prompt.
            let offer_id = msg.payload.get("offerId").and_then(Value::as_str).unwrap_or("").to_string();
            let _ = app.emit("relay:file_offer", serde_json::json!({
                "offerId":  offer_id,
                "files":    msg.payload.get("files").cloned().unwrap_or(serde_json::json!([])),
                "from":     msg.from,
            }));

            // Phase 5b: auto-accept when the sender is a trusted quick-mode peer.
            let sender_fp = lookup_peer_fingerprint(&msg.from);
            if !sender_fp.is_empty() && !offer_id.is_empty() {
                let cfg_state = app.state::<crate::config::SharedConfig>();
                if crate::config::is_quick_mode(&cfg_state, &sender_fp) {
                    let file_ids: Vec<String> = msg.payload.get("files")
                        .and_then(|v| v.as_array())
                        .map(|arr| arr.iter()
                            .filter_map(|f| f.get("fileId").and_then(Value::as_str).map(String::from))
                            .collect())
                        .unwrap_or_default();
                    let (device_id, room_token) = {
                        let cfg = cfg_state.read().unwrap();
                        (cfg.device_id.clone(), cfg.room_token.clone())
                    };
                    let accept = crate::protocol::Message::new(
                        "file_accept",
                        &device_id,
                        &msg.from,
                        &room_token,
                        serde_json::json!({ "offerId": offer_id, "fileIds": file_ids }),
                    );
                    if let Ok(json) = serde_json::to_string(&accept) {
                        let _ = send_raw(json);
                    }
                    eprintln!("[connection] Auto-accepted offer {} from quick-mode peer {}",
                              offer_id, &sender_fp[..8.min(sender_fp.len())]);
                }
            }
        }
        "file_accept" => {
            // Our outbound offer was accepted. Resolve the waiting upload_file future.
            let offer_id = msg.payload.get("offerId").and_then(Value::as_str).unwrap_or("");
            let upload_tokens: HashMap<String, String> = msg.payload
                .get("uploadTokens")
                .and_then(|v| serde_json::from_value(v.clone()).ok())
                .unwrap_or_default();
            resolve_pending_offer(offer_id, OfferResult::Accepted { upload_tokens });
            let _ = app.emit("relay:file_accept", &msg.payload);
        }
        "file_reject" => {
            // Our outbound offer was rejected.
            let offer_id = msg.payload.get("offerId").and_then(Value::as_str).unwrap_or("");
            resolve_pending_offer(offer_id, OfferResult::Rejected);
            let _ = app.emit("relay:file_reject", serde_json::json!({
                "offerId": offer_id,
                "from":    msg.from,
            }));
        }
        "file_ready" => {
            // File is uploaded and available for download (we're the recipient).
            let _ = app.emit("relay:file_ready", serde_json::json!({
                "offerId":     msg.payload.get("offerId").and_then(Value::as_str).unwrap_or(""),
                "fileId":      msg.payload.get("fileId").and_then(Value::as_str).unwrap_or(""),
                "name":        msg.payload.get("name").and_then(Value::as_str).unwrap_or("file"),
                "size":        msg.payload.get("size").and_then(Value::as_i64).unwrap_or(0),
                "downloadUrl": msg.payload.get("downloadUrl").and_then(Value::as_str).unwrap_or(""),
                "from":        msg.from,
            }));
        }
        "pong" | "ping" => {} // ignore heartbeats
        _ => {
            // Forward unknown messages to frontend for extensibility
            let _ = app.emit("relay:message", msg);
        }
    }
}

// ─── Tauri commands ──────────────────────────────────────────────────────────

#[tauri::command]
pub fn send_relay_message(json: String) -> Result<(), String> {
    send_raw(json)
}

#[tauri::command]
pub fn get_connection_status() -> bool {
    is_connected()
}

pub fn leave_room(app: &AppHandle, config: &SharedConfig) {
    let (device_id, room_token) = {
        let cfg = config.read().unwrap();
        (cfg.device_id.clone(), cfg.room_token.clone())
    };
    let msg = crate::protocol::Message::new(
        "device_leave",
        &device_id,
        crate::protocol::BROADCAST,
        &room_token,
        serde_json::json!({}),
    );
    if let Ok(json) = serde_json::to_string(&msg) {
        let _ = send_raw(json);
    }
    let _ = app.emit("tray:leave_room", ());
}

#[tauri::command]
pub fn leave_room_cmd(app: tauri::AppHandle, cfg_state: tauri::State<SharedConfig>) {
    leave_room(&app, &cfg_state);
}

/// Accept an incoming file offer. Sends file_accept targeted to the sender.
/// `file_ids` is the subset of files to accept (typically all).
#[tauri::command]
pub fn send_file_accept(
    offer_id: String,
    file_ids: Vec<String>,
    sender_device_id: String,
    cfg_state: tauri::State<SharedConfig>,
) -> Result<(), String> {
    let (device_id, room_token) = {
        let cfg = cfg_state.read().unwrap();
        (cfg.device_id.clone(), cfg.room_token.clone())
    };
    let msg = crate::protocol::Message::new(
        "file_accept",
        &device_id,
        &sender_device_id,
        &room_token,
        serde_json::json!({ "offerId": offer_id, "fileIds": file_ids }),
    );
    let json = serde_json::to_string(&msg).map_err(|e| e.to_string())?;
    send_raw(json)
}

/// Reject an incoming file offer. Sends file_reject targeted to the sender.
#[tauri::command]
pub fn send_file_reject(
    offer_id: String,
    sender_device_id: String,
    cfg_state: tauri::State<SharedConfig>,
) -> Result<(), String> {
    let (device_id, room_token) = {
        let cfg = cfg_state.read().unwrap();
        (cfg.device_id.clone(), cfg.room_token.clone())
    };
    let msg = crate::protocol::Message::new(
        "file_reject",
        &device_id,
        &sender_device_id,
        &room_token,
        serde_json::json!({ "offerId": offer_id }),
    );
    let json = serde_json::to_string(&msg).map_err(|e| e.to_string())?;
    send_raw(json)
}

/// Confirm a completed download. Signals the relay to delete the file immediately.
#[tauri::command]
pub fn send_file_done(
    offer_id: String,
    file_id: String,
    sender_device_id: String,
    cfg_state: tauri::State<SharedConfig>,
) -> Result<(), String> {
    let (device_id, room_token) = {
        let cfg = cfg_state.read().unwrap();
        (cfg.device_id.clone(), cfg.room_token.clone())
    };
    let msg = crate::protocol::Message::new(
        "file_done",
        &device_id,
        &sender_device_id,
        &room_token,
        serde_json::json!({ "offerId": offer_id, "fileId": file_id }),
    );
    let json = serde_json::to_string(&msg).map_err(|e| e.to_string())?;
    send_raw(json)
}
