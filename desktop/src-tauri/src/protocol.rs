/// Protocol — message types matching the relay server JSON protocol.
///
/// Message flow:
///   Client → Relay: `{ type, from, to, payload }`
///   Relay → Client: same structure
///
/// Types:
///   device_join     — announce presence to room
///   device_leave    — graceful disconnect
///   clipboard_text  — text clipboard payload
///   clipboard_image — image file reference (fileId + downloadUrl)
///   file_meta       — file transfer metadata
///   ping / pong     — heartbeat
///   ack             — delivery acknowledgement

use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub const BROADCAST: &str = "broadcast";

// ── HTTP paths ────────────────────────────────────────────────────────────────
pub mod paths {
    pub const WS: &str = "/ws";
    pub const UPLOAD: &str = "/upload";
    pub const DOWNLOAD: &str = "/download";
    pub const HEALTH: &str = "/health";
}

// ── Message types ─────────────────────────────────────────────────────────────
pub mod msg {
    pub const DEVICE_JOIN: &str = "device_join";
    pub const DEVICE_LEAVE: &str = "device_leave";
    pub const CLIPBOARD_TEXT: &str = "clipboard_text";
    pub const CLIPBOARD_IMAGE: &str = "clipboard_image";
    pub const CLIPBOARD_CLEAR: &str = "clipboard_clear";
    pub const FILE_META: &str = "file_meta";
    pub const FILE_EXPIRED: &str = "file_expired";
    pub const FILES_CLEAR: &str = "files_clear";
    pub const FILE_OFFER: &str = "file_offer";
    pub const FILE_ACCEPT: &str = "file_accept";
    pub const FILE_REJECT: &str = "file_reject";
    pub const FILE_READY: &str = "file_ready";
    pub const FILE_DONE: &str = "file_done";
    pub const PING: &str = "ping";
    pub const PONG: &str = "pong";
    pub const ACK: &str = "ack";
}

// ── Limits ────────────────────────────────────────────────────────────────────
// Sized for Render free tier (512 MB RAM, ephemeral disk, idle-sleeps).
pub mod limits {
    pub const MAX_FILE_BYTES: u64 = 100 * 1024 * 1024;              // 100 MB
    pub const MAX_TEXT_BYTES: usize = 1 * 1024 * 1024;
    pub const FILE_TTL_MS: u64 = 60 * 60 * 1000;                    // 1 hour
    pub const OFFER_TTL_MS: u64 = 30 * 60 * 1000;                   // 30 min
    pub const OFFLINE_QUEUE_TTL_MS: u64 = 60 * 60 * 1000;           // 1 hour
    pub const OFFLINE_QUEUE_MAX_PER_DEVICE: usize = 100;
    pub const CHUNK_SIZE: usize = 1 * 1024 * 1024;                  // 1 MB
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    #[serde(rename = "type")]
    pub kind: String,
    pub id: String,
    pub from: String,       // device_id of sender
    pub to: String,         // device_id or "broadcast"
    pub room: String,       // room_token
    pub payload: serde_json::Value,
}

impl Message {
    pub fn new(kind: &str, from: &str, to: &str, room: &str, payload: serde_json::Value) -> Self {
        Self {
            kind: kind.to_string(),
            id: Uuid::new_v4().to_string(),
            from: from.to_string(),
            to: to.to_string(),
            room: room.to_string(),
            payload,
        }
    }

    pub fn device_join(
        device_id: &str,
        device_name: &str,
        room: &str,
        platform: &str,
        fingerprint: &str,
    ) -> Self {
        Self::new(
            "device_join",
            device_id,
            BROADCAST,
            room,
            serde_json::json!({
                "deviceId": device_id,
                "deviceName": device_name,
                "platform": platform,
                "roomToken": room,
                "fingerprint": fingerprint,
            }),
        )
    }

    pub fn clipboard_text(from: &str, to: &str, room: &str, text: &str) -> Self {
        Self::new(
            "clipboard_text",
            from,
            to,
            room,
            serde_json::json!({ "text": text }),
        )
    }

    pub fn ping(from: &str, room: &str) -> Self {
        Self::new("ping", from, BROADCAST, room, serde_json::json!({}))
    }
}

/// Deduplication ring buffer — prevents processing the same message twice
/// when it arrives via multiple paths (relay + LAN).
pub struct MsgDedup {
    seen: std::collections::VecDeque<String>,
    cap: usize,
}

impl MsgDedup {
    pub fn new(cap: usize) -> Self {
        Self { seen: std::collections::VecDeque::with_capacity(cap), cap }
    }

    /// Returns `true` if the message id is new (not seen before).
    pub fn check_and_insert(&mut self, id: &str) -> bool {
        if self.seen.contains(&id.to_string()) {
            return false;
        }
        if self.seen.len() >= self.cap {
            self.seen.pop_front();
        }
        self.seen.push_back(id.to_string());
        true
    }
}
