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

use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tauri::{AppHandle, Emitter};
use tokio::sync::mpsc;
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
}

lazy_static::lazy_static! {
    static ref ROOM_DEVICES: Mutex<Vec<DeviceInfo>> = Mutex::new(Vec::new());
    // Shared dedup across relay and LAN paths — drops the second copy of any message.
    static ref DEDUP: Mutex<MsgDedup> = Mutex::new(MsgDedup::new(500));
}

/// Returns true if this message ID is new (not seen before). Thread-safe.
pub fn check_dedup(id: &str) -> bool {
    DEDUP.lock().unwrap().check_and_insert(id)
}

fn add_device(id: &str, label: &str, platform: &str) {
    let mut devs = ROOM_DEVICES.lock().unwrap();
    if !devs.iter().any(|d| d.id == id) {
        devs.push(DeviceInfo {
            id: id.to_string(),
            label: label.to_string(),
            platform: platform.to_string(),
        });
    }
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
            let (relay_url, device_id, device_name, room_token) = {
                let cfg = config.read().unwrap();
                (
                    cfg.relay_url.clone(),
                    cfg.device_id.clone(),
                    cfg.device_name.clone(),
                    cfg.room_token.clone(),
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
                    let join_msg = Message::device_join(&device_id, &device_name, &room_token, "windows");
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
            if !device_id.is_empty() {
                add_device(device_id, device_name, platform);
            }
            let _ = app.emit("relay:device_join", &msg.payload);
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
