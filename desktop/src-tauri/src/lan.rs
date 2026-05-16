/// LAN module — local WS server + mDNS advertisement.
///
/// Windows advertises itself via mDNS (_livec._tcp.local.) with:
///   room_hash = sha256(room_token)[0..4] as hex (8 chars)
///   device_id = device UUID
///   platform  = "windows"
///
/// Android discovers the service, verifies room_hash, and connects to
/// ws://<windows-ip>:LAN_PORT/ws for low-latency clipboard sync.
///
/// Outbound clipboard_text is sent to BOTH relay and all LAN clients;
/// the global dedup in connection.rs drops duplicates on the receiving side.

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Mutex;

use futures_util::{SinkExt, StreamExt};
use lazy_static::lazy_static;
use tauri::AppHandle;
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio_tungstenite::{accept_async, tungstenite::Message as WsMessage};

use crate::config::SharedConfig;
use crate::protocol::Message;

pub const LAN_PORT: u16 = 7777;

type LanTx = mpsc::UnboundedSender<String>;

lazy_static! {
    static ref LAN_CLIENTS: Mutex<HashMap<String, LanTx>> = Mutex::new(HashMap::new());
}

/// Broadcast a JSON string to every connected LAN peer.
pub fn send_lan(json: &str) {
    let clients = LAN_CLIENTS.lock().unwrap();
    for tx in clients.values() {
        let _ = tx.send(json.to_string());
    }
}

pub fn lan_peer_count() -> usize {
    LAN_CLIENTS.lock().unwrap().len()
}

// ─── Entry point ─────────────────────────────────────────────────────────────

pub fn start_lan(app: AppHandle, config: SharedConfig) {
    tauri::async_runtime::spawn(async move {
        let (device_id, room_token) = {
            let cfg = config.read().unwrap();
            (cfg.device_id.clone(), cfg.room_token.clone())
        };

        advertise_mdns(&device_id, &room_token);

        let listener = match TcpListener::bind(("0.0.0.0", LAN_PORT)).await {
            Ok(l) => {
                eprintln!("[LAN] WS server listening on :{LAN_PORT}");
                l
            }
            Err(e) => {
                eprintln!("[LAN] Bind failed: {e}");
                return;
            }
        };

        loop {
            let Ok((stream, addr)) = listener.accept().await else {
                continue;
            };
            let app = app.clone();
            let config = config.clone();
            tokio::spawn(handle_client(stream, addr, app, config));
        }
    });
}

// ─── mDNS advertisement ───────────────────────────────────────────────────────

fn local_ipv4() -> Ipv4Addr {
    let sock = std::net::UdpSocket::bind("0.0.0.0:0").ok();
    if let Some(s) = sock {
        let _ = s.connect("8.8.8.8:80");
        if let Ok(addr) = s.local_addr() {
            if let IpAddr::V4(ip) = addr.ip() {
                return ip;
            }
        }
    }
    Ipv4Addr::LOCALHOST
}

pub fn room_hash(room_token: &str) -> String {
    use sha2::{Digest, Sha256};
    let hash = Sha256::digest(room_token.as_bytes());
    hex::encode(&hash[..4])
}

fn advertise_mdns(device_id: &str, room_token: &str) {
    use mdns_sd::{ServiceDaemon, ServiceInfo};

    let hash = room_hash(room_token);
    let ip = local_ipv4();
    let hostname = hostname::get()
        .unwrap_or_default()
        .to_string_lossy()
        .into_owned();

    let mut props = HashMap::new();
    props.insert("room_hash".to_string(), hash.clone());
    props.insert("device_id".to_string(), device_id.to_string());
    props.insert("platform".to_string(), "windows".to_string());

    let mdns = match ServiceDaemon::new() {
        Ok(d) => d,
        Err(e) => {
            eprintln!("[LAN] mDNS daemon error: {e}");
            return;
        }
    };

    let service = match ServiceInfo::new(
        "_livec._tcp.local.",
        device_id,
        &format!("{}.local.", hostname),
        IpAddr::V4(ip),
        LAN_PORT,
        props,
    ) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("[LAN] ServiceInfo error: {e}");
            return;
        }
    };

    if let Err(e) = mdns.register(service) {
        eprintln!("[LAN] mDNS register error: {e}");
        return;
    }

    eprintln!("[LAN] mDNS advertising room_hash={hash} on {ip}:{LAN_PORT}");
    std::mem::forget(mdns); // lives for process lifetime
}

// ─── Per-client handler ───────────────────────────────────────────────────────

async fn handle_client(
    stream: tokio::net::TcpStream,
    addr: SocketAddr,
    app: AppHandle,
    config: SharedConfig,
) {
    let ws = match accept_async(stream).await {
        Ok(ws) => ws,
        Err(e) => {
            eprintln!("[LAN] WS handshake failed from {addr}: {e}");
            return;
        }
    };

    let peer_key = addr.to_string();
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
    LAN_CLIENTS.lock().unwrap().insert(peer_key.clone(), tx);
    eprintln!("[LAN] Connected: {addr}");

    let (mut write, mut read) = ws.split();

    let pump = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            if write.send(WsMessage::Text(msg.into())).await.is_err() {
                break;
            }
        }
    });

    let (device_id, device_name, room_token, fingerprint) = {
        let cfg = config.read().unwrap();
        (cfg.device_id.clone(), cfg.device_name.clone(), cfg.room_token.clone(), cfg.fingerprint.clone())
    };

    // Greet the new client with our own device_join so it knows we're here on LAN
    {
        let join = Message::device_join(&device_id, &device_name, &room_token, "windows", &fingerprint);
        if let Ok(json) = serde_json::to_string(&join) {
            let clients = LAN_CLIENTS.lock().unwrap();
            if let Some(tx) = clients.get(&peer_key) {
                let _ = tx.send(json);
            }
        }
    }

    while let Some(frame) = read.next().await {
        let text = match frame {
            Ok(WsMessage::Text(t)) => t,
            Ok(WsMessage::Ping(_)) | Ok(WsMessage::Pong(_)) => continue,
            Ok(WsMessage::Close(_)) => break,
            Ok(_) => continue, // Binary/etc — not used in V1
            Err(e) => {
                eprintln!("[LAN] Read error from {addr}: {e}");
                break;
            }
        };
        let Ok(m) = serde_json::from_str::<Message>(&text) else {
            continue;
        };

        // Reject messages from a different room
        if m.room != room_token {
            continue;
        }

        // Fan out to other LAN clients
        {
            let clients = LAN_CLIENTS.lock().unwrap();
            for (key, client_tx) in clients.iter() {
                if key != &peer_key {
                    let _ = client_tx.send(text.to_string());
                }
            }
        }

        // Skip our own echoes — defensive even on LAN
        if m.from == device_id { continue; }

        // Process locally if addressed to us, using the global dedup
        if (m.to == device_id || m.to == crate::protocol::BROADCAST)
            && crate::connection::check_dedup(&m.id)
        {
            crate::connection::handle_message(&app, &m);
        }
    }

    LAN_CLIENTS.lock().unwrap().remove(&peer_key);
    pump.abort();
    eprintln!("[LAN] Disconnected: {addr}");
}
