/// Overlay module — transparent, always-on-top circular shelf window.
///
/// The Rust side owns:
/// - Global drag detection via a WH_MOUSE_LL hook → emits `shelf:drag_start`
/// - Overlay window drag-and-drop handling via Tauri WindowEvent::DragDrop
/// - Overlay window positioning (bottom-right of the active monitor)
///
/// The React frontend owns the 4-state animation (IDLE / BLOOMED / READY / ACCEPTED).
///
/// Events emitted to the frontend:
/// - `shelf:drag_start`  → `{}` — user started dragging anywhere on desktop
/// - `shelf:drag_enter`  → `{}` — cursor entered the overlay drop zone
/// - `shelf:drag_leave`  → `{}` — cursor left the overlay drop zone
/// - `shelf:drop`        → `{ files: string[] }` — files dropped on overlay
use serde::Serialize;
use tauri::{AppHandle, DragDropEvent, Emitter, Manager, WebviewWindow, WindowEvent};

// ─── Public types ────────────────────────────────────────────────────────────

#[allow(dead_code)]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum OverlayState {
    Idle,
    Bloomed,
    Ready,
    Accepted,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OverlaySnapshot {
    pub state: OverlayState,
    pub window_label: &'static str,
    pub click_through_idle: bool,
}

impl Default for OverlaySnapshot {
    fn default() -> Self {
        Self {
            state: OverlayState::Idle,
            window_label: "overlay",
            click_through_idle: true,
        }
    }
}

// ─── Tauri drag-and-drop wiring ───────────────────────────────────────────────

/// Wire Tauri's built-in drag-and-drop events on the overlay window.
pub fn wire_drag_events(app: &AppHandle, win: &WebviewWindow) {
    let app_clone = app.clone();

    win.on_window_event(move |event| {
        if let WindowEvent::DragDrop(drag_event) = event {
            match drag_event {
                DragDropEvent::Enter { paths, .. } => {
                    let files: Vec<String> = paths
                        .iter()
                        .map(|p| p.to_string_lossy().to_string())
                        .collect();
                    let _ = app_clone.emit("shelf:drag_enter", serde_json::json!({ "files": files }));
                }
                DragDropEvent::Leave => {
                    let _ = app_clone.emit("shelf:drag_leave", serde_json::json!({}));
                }
                DragDropEvent::Drop { paths, .. } => {
                    let files: Vec<String> = paths
                        .iter()
                        .map(|p| p.to_string_lossy().to_string())
                        .collect();
                    let _ = app_clone.emit("shelf:drop", serde_json::json!({ "files": files }));
                }
                _ => {}
            }
        }
    });
}

// ─── Global mouse hook for drag-start detection ───────────────────────────────

/// Installs a low-level mouse hook so we can detect when the user starts dragging
/// anywhere on the desktop (not just over our window).
/// Emits `shelf:drag_start` on left-button-down.
#[cfg(target_os = "windows")]
pub mod drag_hook {
    use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
    use std::sync::OnceLock;

    use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, POINT, WPARAM};
    use windows::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_WIN32,
        PROCESS_QUERY_LIMITED_INFORMATION,
    };
    use windows::Win32::UI::WindowsAndMessaging::{
        CallNextHookEx, DispatchMessageW, GetClassNameW, GetMessageW,
        GetSystemMetrics, SetWindowsHookExW, WindowFromPoint, MSG, SM_CXDRAG, SM_CYDRAG,
        WH_MOUSE_LL, WM_LBUTTONDOWN, WM_LBUTTONUP, WM_MOUSEMOVE,
    };
    use windows::Win32::UI::WindowsAndMessaging::GetWindowThreadProcessId;

    use tauri::{AppHandle, Emitter, Manager};

    static APP_HANDLE: OnceLock<AppHandle> = OnceLock::new();
    // Set when LButtonDown occurs over an Explorer window; cleared on LButtonUp.
    static ARMED: AtomicBool = AtomicBool::new(false);
    // Set when we have shown the shelf for the current drag.
    static SHOWING: AtomicBool = AtomicBool::new(false);
    // Cursor position at LButtonDown (for drag-threshold check).
    static DOWN_X: AtomicI32 = AtomicI32::new(0);
    static DOWN_Y: AtomicI32 = AtomicI32::new(0);

    /// Returns true when the window at `hwnd` belongs to explorer.exe.
    unsafe fn is_explorer_window(hwnd: HWND) -> bool {
        // Class name check — fast path.
        let mut class_buf = [0u16; 256];
        let class_len = GetClassNameW(hwnd, &mut class_buf) as usize;
        let class = String::from_utf16_lossy(&class_buf[..class_len]);
        let explorer_classes = [
            "SysListView32",
            "DirectUIHWND",
            "CabinetWClass",
            "ExploreWClass",
            "SHELLDLL_DefView",
        ];
        if !explorer_classes.iter().any(|&c| class.contains(c)) {
            return false;
        }

        // Process name check — confirm it is explorer.exe.
        let mut pid = 0u32;
        GetWindowThreadProcessId(hwnd, Some(&mut pid));
        if pid == 0 {
            return false;
        }
        let handle = match OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid) {
            Ok(h) => h,
            Err(_) => return false,
        };
        let mut name_buf = [0u16; 260];
        let mut size = 260u32;
        let ok = QueryFullProcessImageNameW(handle, PROCESS_NAME_WIN32, windows::core::PWSTR(name_buf.as_mut_ptr()), &mut size).is_ok();
        let _ = windows::Win32::Foundation::CloseHandle(handle);
        if !ok {
            return false;
        }
        let name = String::from_utf16_lossy(&name_buf[..size as usize]);
        name.to_lowercase().ends_with("explorer.exe")
    }

    unsafe extern "system" fn mouse_hook(
        n_code: i32,
        w_param: WPARAM,
        l_param: LPARAM,
    ) -> LRESULT {
        if n_code >= 0 {
            let msg = w_param.0 as u32;

            if msg == WM_LBUTTONDOWN {
                // Get cursor position from MSLLHOOKSTRUCT.
                let hook_struct = &*(l_param.0 as *const windows::Win32::UI::WindowsAndMessaging::MSLLHOOKSTRUCT);
                let pt = POINT { x: hook_struct.pt.x, y: hook_struct.pt.y };
                let hwnd = WindowFromPoint(pt);
                if is_explorer_window(hwnd) {
                    ARMED.store(true, Ordering::SeqCst);
                    DOWN_X.store(pt.x, Ordering::SeqCst);
                    DOWN_Y.store(pt.y, Ordering::SeqCst);
                } else {
                    ARMED.store(false, Ordering::SeqCst);
                    SHOWING.store(false, Ordering::SeqCst);
                }
            } else if msg == WM_MOUSEMOVE {
                if ARMED.load(Ordering::SeqCst) && !SHOWING.load(Ordering::SeqCst) {
                    let hook_struct = &*(l_param.0 as *const windows::Win32::UI::WindowsAndMessaging::MSLLHOOKSTRUCT);
                    let dx = (hook_struct.pt.x - DOWN_X.load(Ordering::SeqCst)).abs();
                    let dy = (hook_struct.pt.y - DOWN_Y.load(Ordering::SeqCst)).abs();
                    let thresh_x = GetSystemMetrics(SM_CXDRAG);
                    let thresh_y = GetSystemMetrics(SM_CYDRAG);
                    if dx > thresh_x || dy > thresh_y {
                        // Design spec: Shift+drag only. Check Shift at threshold so
                        // the user can press Shift at any point during the drag.
                        use windows::Win32::UI::Input::KeyboardAndMouse::{GetAsyncKeyState, VK_SHIFT};
                        let shift_held = (GetAsyncKeyState(VK_SHIFT.0 as i32) as u16 & 0x8000) != 0;
                        if shift_held {
                            SHOWING.store(true, Ordering::SeqCst);
                            ARMED.store(false, Ordering::SeqCst);
                            if let Some(app) = APP_HANDLE.get() {
                                if let Some(win) = app.get_webview_window("overlay") {
                                    let _ = win.show();
                                }
                                let _ = app.emit("shelf:drag_start", serde_json::json!({}));
                            }
                        }
                        // Shift not held yet — stay ARMED so next WM_MOUSEMOVE rechecks.
                    }
                }
            } else if msg == WM_LBUTTONUP {
                ARMED.store(false, Ordering::SeqCst);
                if SHOWING.load(Ordering::SeqCst) {
                    SHOWING.store(false, Ordering::SeqCst);
                    if let Some(app) = APP_HANDLE.get() {
                        let _ = app.emit("shelf:drag_end", serde_json::json!({}));
                    }
                }
            }
        }
        CallNextHookEx(None, n_code, w_param, l_param)
    }

    pub fn install(app: AppHandle) {
        APP_HANDLE.get_or_init(|| app);
        std::thread::spawn(|| unsafe {
            let _hook = SetWindowsHookExW(WH_MOUSE_LL, Some(mouse_hook), None, 0)
                .expect("SetWindowsHookExW failed");
            let mut msg = MSG::default();
            while GetMessageW(&mut msg, None, 0, 0).as_bool() {
                DispatchMessageW(&msg);
            }
        });
    }
}

// ─── Overlay window positioning ───────────────────────────────────────────────

/// Position the overlay window to bottom-right of the primary monitor
/// with an 88px margin from each edge.
pub fn position_overlay_bottom_right(app: &AppHandle) {
    if let Some(win) = app.get_webview_window("overlay") {
        if let Ok(Some(m)) = win.primary_monitor() {
            let size = m.size();
            let scale = m.scale_factor();
            let w = 360.0f64;
            let h = 360.0f64;
            let margin = 88.0f64;
            let x = (size.width as f64 / scale) - w - margin;
            let y = (size.height as f64 / scale) - h - margin;
            let _ = win.set_position(tauri::PhysicalPosition::new(
                (x * scale) as i32,
                (y * scale) as i32,
            ));
        }
        // Wire drag-and-drop events before showing
        wire_drag_events(app, &win);
    }
}

/// Show the overlay window and bring to front.
pub fn show_overlay(app: &AppHandle) {
    if let Some(win) = app.get_webview_window("overlay") {
        let _ = win.show();
        let _ = win.set_focus();
    }
}

/// Hide the overlay window.
pub fn hide_overlay(app: &AppHandle) {
    if let Some(win) = app.get_webview_window("overlay") {
        let _ = win.hide();
    }
}

/// Set whether the overlay ignores cursor events (click-through).
pub fn set_overlay_click_through(app: &AppHandle, enabled: bool) {
    if let Some(win) = app.get_webview_window("overlay") {
        let _ = win.set_ignore_cursor_events(enabled);
    }
}
