use tauri::{Emitter, Manager};
use std::sync::Mutex;

#[cfg(target_os = "windows")]
fn strip_dwm_border(win: &tauri::WebviewWindow) {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::Graphics::Dwm::{
        DwmSetWindowAttribute,
        DWMWA_BORDER_COLOR,
        DWMWA_WINDOW_CORNER_PREFERENCE,
        DWMWCP_DONOTROUND,
    };

    let hwnd = HWND(win.hwnd().map(|h| h.0).unwrap_or(std::ptr::null_mut()));
    if hwnd.0.is_null() { return; }

    unsafe {
        let pref = DWMWCP_DONOTROUND.0;
        let _ = DwmSetWindowAttribute(
            hwnd,
            DWMWA_WINDOW_CORNER_PREFERENCE,
            &pref as *const _ as *const _,
            std::mem::size_of::<u32>() as u32,
        );

        let color: u32 = 0xFFFFFFFE;
        let _ = DwmSetWindowAttribute(
            hwnd,
            DWMWA_BORDER_COLOR,
            &color as *const _ as *const _,
            std::mem::size_of::<u32>() as u32,
        );
    }
}

pub mod clipboard;
pub mod config;
pub mod connection;
pub mod lan;
pub mod protocol;
pub mod screenshot;
pub mod tray;
pub mod windows_overlay;

/// Pending screenshot path — set by the watcher, read by the toast frontend.
static PENDING_SCREENSHOT: Mutex<Option<String>> = Mutex::new(None);

#[tauri::command]
fn get_pending_screenshot() -> Option<String> {
    PENDING_SCREENSHOT.lock().unwrap().clone()
}

#[tauri::command]
fn screenshot_toast_dismiss(app: tauri::AppHandle) {
    use tauri::Manager;
    *PENDING_SCREENSHOT.lock().unwrap() = None;
    if let Some(win) = app.get_webview_window("screenshot_toast") {
        let _ = win.hide();
    }
}

#[tauri::command]
fn screenshot_toast_show(app: tauri::AppHandle) {
    use tauri::Manager;
    if let Some(win) = app.get_webview_window("screenshot_toast") {
        #[cfg(target_os = "windows")]
        unsafe {
            use windows::Win32::UI::WindowsAndMessaging::GetCursorPos;
            let mut point = std::mem::zeroed();
            if GetCursorPos(&mut point).is_ok() {
                let _ = win.set_position(tauri::PhysicalPosition::new(point.x + 20, point.y + 20));
            }
        }
        let _ = win.show();
        let _ = win.set_focus();
    }
}

#[tauri::command]
fn overlay_hide(app: tauri::AppHandle) {
    use tauri::Manager;
    if let Some(win) = app.get_webview_window("overlay") {
        let _ = win.hide();
    }
}

#[tauri::command]
fn write_clipboard_text(text: String) -> Result<(), String> {
    clipboard::write_text(&text)
}

/// Reject/dismiss a staged file. Sends HTTP DELETE to the relay's download URL
/// so the file is freed from storage immediately instead of waiting for TTL.
#[tauri::command]
async fn delete_relay_file(url: String) -> Result<(), String> {
    if url.is_empty() {
        return Ok(());
    }
    let client = reqwest::Client::new();
    match client.delete(&url).send().await {
        Ok(_) => Ok(()),
        // Don't fail the UI just because the relay's gone — local dismiss already succeeded.
        Err(e) => {
            eprintln!("[delete_relay_file] {url} -> {e}");
            Ok(())
        }
    }
}

/// Broadcast a history-clear message to all peers. `kind` is "clipboard" or "files".
#[tauri::command]
fn broadcast_clear(
    kind: String,
    cfg_state: tauri::State<config::SharedConfig>,
) -> Result<(), String> {
    let (device_id, room_token) = {
        let cfg = cfg_state.read().unwrap();
        (cfg.device_id.clone(), cfg.room_token.clone())
    };
    let msg_type = match kind.as_str() {
        "clipboard" => "clipboard_clear",
        "files" => "files_clear",
        _ => return Err(format!("Unknown clear kind: {kind}")),
    };
    let msg = protocol::Message::new(msg_type, &device_id, protocol::BROADCAST, &room_token, serde_json::json!({}));
    let json = serde_json::to_string(&msg).map_err(|e| e.to_string())?;
    lan::send_lan(&json);
    let _ = connection::send_raw(json);
    Ok(())
}

#[tauri::command]
fn send_clipboard_text(
    text: String,
    cfg_state: tauri::State<config::SharedConfig>,
) -> Result<(), String> {
    let (device_id, room_token) = {
        let cfg = cfg_state.read().unwrap();
        (cfg.device_id.clone(), cfg.room_token.clone())
    };
    let msg = protocol::Message::clipboard_text(&device_id, protocol::BROADCAST, &room_token, &text);
    let json = serde_json::to_string(&msg).map_err(|e| e.to_string())?;
    lan::send_lan(&json);
    connection::send_raw(json)
}

/// Upload a screenshot and send as clipboard_image.
/// If LAN peers are connected: sends image inline (base64) over LAN — no relay upload.
/// Otherwise: uploads to relay and broadcasts downloadUrl.
#[tauri::command]
async fn upload_screenshot(
    path: String,
    target: Option<String>,
    cfg_state: tauri::State<'_, config::SharedConfig>,
) -> Result<String, String> {
    let (relay_url, room_token, device_id) = {
        let cfg = cfg_state.read().unwrap();
        (cfg.relay_url.clone(), cfg.room_token.clone(), cfg.device_id.clone())
    };

    let to = target.as_deref().unwrap_or(protocol::BROADCAST);
    let file_bytes = std::fs::read(&path).map_err(|e| e.to_string())?;

    if lan::lan_peer_count() > 0 {
        use base64::Engine;
        let b64 = base64::engine::general_purpose::STANDARD.encode(&file_bytes);
        let msg = protocol::Message::new(
            "clipboard_image",
            &device_id,
            to,
            &room_token,
            serde_json::json!({ "data": b64, "mimeType": "image/png" }),
        );
        let json = serde_json::to_string(&msg).map_err(|e| e.to_string())?;
        lan::send_lan(&json);
        return Ok(String::new()); // LAN-only — no relay fileId
    }

    // Relay path — no LAN peers available
    let http_base = relay_to_http_base(&relay_url);
    let upload_url = format!("{}/upload", http_base);
    let file_name = std::path::Path::new(&path)
        .file_name()
        .unwrap_or_default()
        .to_string_lossy()
        .into_owned();

    let client = reqwest::Client::new();
    let part = reqwest::multipart::Part::bytes(file_bytes)
        .file_name(file_name)
        .mime_str("image/png")
        .map_err(|e| e.to_string())?;
    let form = reqwest::multipart::Form::new()
        .part("file", part)
        .text("roomToken", room_token.clone())
        .text("deviceId", device_id.clone());

    let res = client.post(&upload_url).multipart(form).send().await.map_err(|e| e.to_string())?;
    if !res.status().is_success() {
        return Err(format!("Upload failed with status: {}", res.status()));
    }

    let res_data: serde_json::Value = res.json().await.map_err(|e| e.to_string())?;
    let file_id = res_data["fileId"].as_str().unwrap_or_default().to_string();
    let download_url = format!("{}/download/{}", http_base, file_id);

    let msg = protocol::Message::new(
        "clipboard_image",
        &device_id,
        to,
        &room_token,
        serde_json::json!({ "fileId": file_id, "downloadUrl": download_url }),
    );
    let json = serde_json::to_string(&msg).map_err(|e| e.to_string())?;
    if let Err(e) = connection::send_raw(json) {
        eprintln!("[upload_screenshot] relay notify failed after upload: {e}");
    }

    Ok(file_id)
}

/// Upload any file and send as file_meta to target device or broadcast.
#[tauri::command]
async fn upload_file(
    path: String,
    target: Option<String>,
    cfg_state: tauri::State<'_, config::SharedConfig>,
) -> Result<String, String> {
    let (relay_url, room_token, device_id) = {
        let cfg = cfg_state.read().unwrap();
        (cfg.relay_url.clone(), cfg.room_token.clone(), cfg.device_id.clone())
    };

    let http_base = relay_to_http_base(&relay_url);
    let upload_url = format!("{}/upload", http_base);

    let file_bytes = std::fs::read(&path).map_err(|e| e.to_string())?;
    let file_size = file_bytes.len() as i64;
    let file_path = std::path::Path::new(&path);
    let file_name = file_path.file_name().unwrap_or_default().to_string_lossy().into_owned();
    let mime = mime_guess::from_path(&path).first_or_octet_stream().to_string();

    let client = reqwest::Client::new();
    let part = reqwest::multipart::Part::bytes(file_bytes)
        .file_name(file_name.clone())
        .mime_str(&mime)
        .map_err(|e| e.to_string())?;
    let form = reqwest::multipart::Form::new()
        .part("file", part)
        .text("roomToken", room_token.clone())
        .text("deviceId", device_id.clone());

    let res = client.post(&upload_url).multipart(form).send().await.map_err(|e| e.to_string())?;
    if !res.status().is_success() {
        return Err(format!("Upload failed: {}", res.status()));
    }

    let res_data: serde_json::Value = res.json().await.map_err(|e| e.to_string())?;
    let file_id = res_data["fileId"].as_str().unwrap_or_default().to_string();
    let download_url = format!("{}/download/{}", http_base, file_id);

    let to = target.as_deref().unwrap_or(protocol::BROADCAST);
    let msg = protocol::Message::new(
        "file_meta",
        &device_id,
        to,
        &room_token,
        serde_json::json!({
            "fileId":      file_id,
            "name":        file_name,
            "size":        file_size,
            "downloadUrl": download_url,
        }),
    );
    let json = serde_json::to_string(&msg).map_err(|e| e.to_string())?;
    lan::send_lan(&json);
    if let Err(e) = connection::send_raw(json) {
        eprintln!("[upload_file] relay notify failed after upload (file is on relay): {e}");
    }

    Ok(download_url)
}

/// Download a file from a URL and save to the user's Downloads folder.
#[tauri::command]
async fn download_file(url: String, filename: String) -> Result<String, String> {
    let client = reqwest::Client::new();
    let response = client.get(&url).send().await.map_err(|e| e.to_string())?;
    if !response.status().is_success() {
        return Err(format!("Download failed: {}", response.status()));
    }
    let bytes = response.bytes().await.map_err(|e| e.to_string())?;

    let downloads_dir = dirs::download_dir()
        .or_else(|| dirs::home_dir().map(|h| h.join("Downloads")))
        .unwrap_or_else(|| std::path::PathBuf::from("."));

    let mut save_path = downloads_dir.join(&filename);
    if save_path.exists() {
        let stem = std::path::Path::new(&filename)
            .file_stem().and_then(|s| s.to_str()).unwrap_or("file").to_string();
        let ext = std::path::Path::new(&filename)
            .extension().and_then(|e| e.to_str())
            .map(|e| format!(".{e}")).unwrap_or_default();
        for i in 1..=99 {
            let candidate = downloads_dir.join(format!("{stem}_{i}{ext}"));
            if !candidate.exists() {
                save_path = candidate;
                break;
            }
        }
    }

    std::fs::write(&save_path, &bytes).map_err(|e| e.to_string())?;
    Ok(save_path.to_string_lossy().to_string())
}

/// Open a native file-picker dialog and return the selected path.
#[tauri::command]
async fn open_file_dialog(app: tauri::AppHandle) -> Result<Option<String>, String> {
    use tauri_plugin_dialog::DialogExt;
    let (tx, rx) = tokio::sync::oneshot::channel();
    app.dialog().file().pick_file(move |path| {
        let _ = tx.send(path);
    });
    let result = rx.await.map_err(|e| e.to_string())?;
    Ok(result.and_then(|fp| match fp {
        tauri_plugin_dialog::FilePath::Path(p) => Some(p.to_string_lossy().to_string()),
        _ => None,
    }))
}

/// Open a native folder-picker dialog and return the selected path.
#[tauri::command]
async fn open_folder_dialog(app: tauri::AppHandle) -> Result<Option<String>, String> {
    use tauri_plugin_dialog::DialogExt;
    let (tx, rx) = tokio::sync::oneshot::channel();
    app.dialog().file().pick_folder(move |path| {
        let _ = tx.send(path);
    });
    let result = rx.await.map_err(|e| e.to_string())?;
    Ok(result.and_then(|fp| match fp {
        tauri_plugin_dialog::FilePath::Path(p) => Some(p.to_string_lossy().to_string()),
        _ => None,
    }))
}

/// Open a file or folder in the system file explorer.
#[tauri::command]
fn reveal_in_explorer(path: String) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("explorer")
            .arg(format!("/select,{}", path))
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn relay_to_http_base(relay_url: &str) -> String {
    let mut url = relay_url.to_string();
    if url.starts_with("wss://") { url = url.replace("wss://", "https://"); }
    else if url.starts_with("ws://") { url = url.replace("ws://", "http://"); }
    if url.ends_with("/ws") { url = url.trim_end_matches("/ws").to_string(); }
    url
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_single_instance::init(|_app, argv, cwd| {
            println!(
                "a new app instance was opened with {argv:?} and the current working directory is {cwd}"
            );
        }))
        .setup(|app| {
            let handle = app.handle().clone();

            let cfg = config::init_config(&handle);
            app.manage(cfg.clone());

            clipboard::start_monitor(handle.clone());
            connection::start_connection(handle.clone(), cfg.clone());
            lan::start_lan(handle.clone(), cfg.clone());
            screenshot::start_watcher(handle.clone(), cfg.clone());
            tray::setup_tray(&handle)?;

            #[cfg(target_os = "windows")]
            {
                windows_overlay::drag_hook::install(handle.clone());

                if let Some(w) = handle.get_webview_window("overlay") {
                    strip_dwm_border(&w);
                }
                if let Some(w) = handle.get_webview_window("screenshot_toast") {
                    strip_dwm_border(&w);
                }
            }

            windows_overlay::position_overlay_bottom_right(&handle);

            // Wire file-drop events on the main window into the frontend
            if let Some(main_win) = handle.get_webview_window("main") {
                let drop_handle = handle.clone();
                main_win.on_window_event(move |event| {
                    if let tauri::WindowEvent::DragDrop(tauri::DragDropEvent::Drop { paths, .. }) = event {
                        let files: Vec<String> = paths.iter()
                            .map(|p| p.to_string_lossy().to_string())
                            .collect();
                        let _ = drop_handle.emit("main:file_drop", serde_json::json!({ "files": files }));
                    }
                });
            }

            use tauri::Listener;
            let handle_clone = handle.clone();
            app.listen("screenshot:detected", move |event| {
                let payload_str = event.payload();
                let path = serde_json::from_str::<serde_json::Value>(payload_str)
                    .ok()
                    .and_then(|v| v.get("path").and_then(|p| p.as_str().map(|s| s.to_string())));

                if let Some(ref p) = path {
                    *PENDING_SCREENSHOT.lock().unwrap() = Some(p.clone());
                }

                screenshot_toast_show(handle_clone.clone());
            });

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            write_clipboard_text,
            send_clipboard_text,
            broadcast_clear,
            delete_relay_file,
            upload_screenshot,
            upload_file,
            download_file,
            open_file_dialog,
            open_folder_dialog,
            reveal_in_explorer,
            config::get_config,
            config::update_device_name,
            config::update_relay_url,
            config::update_screenshot_folder,
            connection::send_relay_message,
            connection::get_connection_status,
            connection::leave_room_cmd,
            screenshot_toast_dismiss,
            screenshot_toast_show,
            overlay_hide,
            get_pending_screenshot,
            connection::get_room_devices,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
