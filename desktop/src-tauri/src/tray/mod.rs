/// Tray module — system tray icon, context menu, and feed window toggle.
///
/// Icon states:
/// - "grey"   → disconnected / no active room
/// - "cyan"   → connected and syncing
/// - "yellow" → reconnecting
///
/// Left-click  → toggle feed window visibility
/// Right-click → context menu
///
/// Menu items:
/// - "Show Feed"      → show/focus feed window
/// - "Copy Last Item" → emit `tray:copy_last` to frontend
/// - ---
/// - "Leave Room"     → emit `tray:leave_room`
/// - "Settings"       → show main window at Settings tab
/// - ---
/// - "Quit"           → exit the app
use serde::Serialize;
use tauri::{
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager,
};

// ─── Public types ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TraySnapshot {
    pub icon_state: &'static str,
    pub feed_window_label: &'static str,
    pub overlay_window_label: &'static str,
}

impl Default for TraySnapshot {
    fn default() -> Self {
        Self {
            icon_state: "grey",
            feed_window_label: "feed",
            overlay_window_label: "overlay",
        }
    }
}

// ─── Toggle helper ────────────────────────────────────────────────────────────

fn toggle_feed(app: &AppHandle) {
    if let Some(win) = app.get_webview_window("feed") {
        let visible = win.is_visible().unwrap_or(false);
        if visible {
            let _ = win.hide();
        } else {
            // Position near bottom-right (tray area) before showing.
            if let Ok(Some(m)) = win.primary_monitor() {
                let size = m.size();
                let scale = m.scale_factor();
                let w = 420.0f64;
                let h = 640.0f64;
                let margin = 16.0f64;
                let x = (size.width as f64 / scale) - w - margin;
                let y = (size.height as f64 / scale) - h - margin - 48.0; // above taskbar
                let _ = win.set_position(tauri::PhysicalPosition::new(
                    (x * scale) as i32,
                    (y * scale) as i32,
                ));
            }
            let _ = win.show();
            let _ = win.set_focus();
        }
    }
}

// ─── Tray builder ────────────────────────────────────────────────────────────

pub fn setup_tray(app: &AppHandle) -> tauri::Result<()> {
    let show_feed = MenuItem::with_id(app, "show_feed", "Show Feed", true, None::<&str>)?;
    let copy_last = MenuItem::with_id(app, "copy_last", "Copy Last Item", true, None::<&str>)?;
    let sep1 = PredefinedMenuItem::separator(app)?;
    let leave_room = MenuItem::with_id(app, "leave_room", "Leave Room", true, None::<&str>)?;
    let settings = MenuItem::with_id(app, "settings", "Settings", true, None::<&str>)?;
    let sep2 = PredefinedMenuItem::separator(app)?;
    let quit = MenuItem::with_id(app, "quit", "Quit LiveClip", true, None::<&str>)?;

    let menu = Menu::with_items(
        app,
        &[
            &show_feed,
            &copy_last,
            &sep1,
            &leave_room,
            &settings,
            &sep2,
            &quit,
        ],
    )?;

    TrayIconBuilder::with_id("liveclip-tray")
        .icon(app.default_window_icon().cloned().unwrap())
        .tooltip("LiveClip")
        .menu(&menu)
        .on_tray_icon_event(|tray, event| {
            let app = tray.app_handle();
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                toggle_feed(&app);
            }
        })
        .on_menu_event(|app, event| match event.id().as_ref() {
            "show_feed" => toggle_feed(app),
            "copy_last" => {
                let _ = app.emit("tray:copy_last", serde_json::json!({}));
            }
            "leave_room" => {
                let _ = app.emit("tray:leave_room", serde_json::json!({}));
            }
            "settings" => {
                if let Some(win) = app.get_webview_window("main") {
                    let _ = win.show();
                    let _ = win.set_focus();
                    let _ = app.emit("nav:settings", serde_json::json!({}));
                }
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .build(app)?;

    Ok(())
}

/// Update tray tooltip with current room info.
pub fn set_tray_tooltip(app: &AppHandle, room_id: &str, state: &str) {
    if let Some(tray) = app.tray_by_id("liveclip-tray") {
        let tooltip = if room_id.is_empty() {
            "LiveClip — disconnected".to_string()
        } else {
            format!("LiveClip — Room {} · {}", room_id, state)
        };
        let _ = tray.set_tooltip(Some(&tooltip));
    }
}
