/// Clipboard module — Win32 clipboard monitoring (outbound) and write (inbound).
///
/// Outbound (monitoring):
///   `start_monitor` spawns a hidden Win32 window that registers with
///   `AddClipboardFormatListener`. On WM_CLIPBOARDUPDATE it reads the
///   clipboard and emits `clipboard:change` to the frontend.
///
/// Inbound (write):
///   `write_text` / `write_image` are called from IPC commands when
///   the room delivers a `clipboard_text` or `clipboard_image` item.
///
/// Events emitted (via `AppHandle::emit`):
/// - `clipboard:change` → `{ kind: "text"|"image", text?: string, sizeBytes?: number }`
use serde::Serialize;
use tauri::AppHandle;

/// Snapshot returned to the frontend on bootstrap.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardSnapshot {
    pub monitoring_enabled: bool,
    pub last_direction: &'static str,
}

impl Default for ClipboardSnapshot {
    fn default() -> Self {
        Self {
            monitoring_enabled: false,
            last_direction: "idle",
        }
    }
}

// ─── Shared atomic flags (accessible from lib.rs) ──────────────────────────────

#[allow(unused_imports)]
pub(crate) use self::win32::SELF_WRITE_PENDING;

// ─── Win32 implementation (Windows only) ────────────────────────────────────

#[cfg(target_os = "windows")]
pub(crate) mod win32 {
    use std::ffi::OsString;
    use std::os::windows::ffi::OsStringExt;
    use std::sync::atomic::{AtomicBool, Ordering};

    /// Set right before we write to the clipboard ourselves so the monitor
    /// thread can recognize and ignore the resulting WM_CLIPBOARDUPDATE.
    /// Prevents self-write echoes from being re-emitted as `clipboard:change`
    /// (which would otherwise create phantom "Screenshot / Image" feed items
    /// after every auto-synced remote image).
    pub(crate) static SELF_WRITE_PENDING: AtomicBool = AtomicBool::new(false);

    /// Last-seen FNV hash + timestamp of clipboard image bytes. When the screenshot
    /// tool re-writes the same image (e.g. on retry), we skip re-emitting within the
    /// dedup window to avoid duplicate upload loops.
    static LAST_IMAGE_HASH: std::sync::Mutex<Option<(u64, u64)>> = std::sync::Mutex::new(None);

    /// Last-seen FNV hash + timestamp of clipboard text content. Some clipboard
    /// providers can fire repeated WM_CLIPBOARDUPDATE notifications for unchanged
    /// text; dedupe here prevents websocket resend loops.
    static LAST_TEXT_HASH: std::sync::Mutex<Option<(u64, u64)>> = std::sync::Mutex::new(None);

    const IMAGE_DEDUP_WINDOW_MS: u64 = 5000;
    const TEXT_DEDUP_WINDOW_MS: u64 = 5000;

    use windows::Win32::Foundation::{HANDLE, HGLOBAL, HWND, LPARAM, LRESULT, WPARAM};
    use windows::Win32::System::DataExchange::{
        AddClipboardFormatListener, CloseClipboard, EmptyClipboard, GetClipboardData,
        OpenClipboard, SetClipboardData,
    };
    use windows::Win32::System::Memory::{
        GlobalAlloc, GlobalLock, GlobalSize, GlobalUnlock, GMEM_MOVEABLE,
    };
    use windows::Win32::System::Ole::{CF_DIB, CF_UNICODETEXT};
    use windows::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DefWindowProcW, DispatchMessageW, GetMessageW, PostQuitMessage,
        RegisterClassW, CS_HREDRAW, CS_VREDRAW, MSG, WM_CLIPBOARDUPDATE, WM_DESTROY, WNDCLASSW,
        WS_EX_NOACTIVATE, WS_POPUP,
    };
    use windows::core::PCWSTR;

    use tauri::{AppHandle, Emitter};

    /// Encode a wide (UTF-16) null-terminated string.
    fn wide(s: &str) -> Vec<u16> {
        use std::os::windows::ffi::OsStrExt;
        std::ffi::OsStr::new(s)
            .encode_wide()
            .chain(std::iter::once(0))
            .collect()
    }

    /// FNV-1a 64-bit hash helper used for lightweight clipboard dedup checks.
    fn fnv1a(bytes: &[u8]) -> u64 {
        let mut h: u64 = 0xcbf29ce484222325;
        for &b in bytes {
            h ^= b as u64;
            h = h.wrapping_mul(0x100000001b3);
        }
        h
    }

    /// Read plain text from the clipboard (CF_UNICODETEXT).
    pub fn read_text() -> Option<String> {
        unsafe {
            OpenClipboard(None).ok()?;
            let handle = GetClipboardData(CF_UNICODETEXT.0 as u32).ok()?;
            let hglobal = HGLOBAL(handle.0);
            let ptr = GlobalLock(hglobal) as *const u16;
            if ptr.is_null() {
                let _ = CloseClipboard();
                return None;
            }
            let len = (0..).take_while(|&i| *ptr.add(i) != 0).count();
            let slice = std::slice::from_raw_parts(ptr, len);
            let text = OsString::from_wide(slice).to_string_lossy().to_string();
            let _ = GlobalUnlock(hglobal);
            let _ = CloseClipboard();
            Some(text)
        }
    }

    /// Read an image from the clipboard (CF_DIB) and return raw DIB bytes.
    /// Returns None if the content matches the last-seen FNV hash within the
    /// dedup window (prevents duplicate events when screenshot tools re-write
    /// the same image on retry).
    pub fn read_image_bytes() -> Option<Vec<u8>> {
        unsafe {
            OpenClipboard(None).ok()?;
            let handle = GetClipboardData(CF_DIB.0 as u32).ok()?;
            let hglobal = HGLOBAL(handle.0);
            let ptr = GlobalLock(hglobal) as *const u8;
            if ptr.is_null() {
                let _ = CloseClipboard();
                return None;
            }
            let size = GlobalSize(hglobal);
            let bytes = std::slice::from_raw_parts(ptr, size).to_vec();
            let _ = GlobalUnlock(hglobal);
            let _ = CloseClipboard();

            let now_ms = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_millis() as u64;

            let sig = fnv1a(&bytes);
            let mut guard = LAST_IMAGE_HASH.lock().unwrap();
            if let Some((prev_sig, prev_ms)) = *guard {
                if prev_sig == sig && now_ms.saturating_sub(prev_ms) < IMAGE_DEDUP_WINDOW_MS {
                    return None; // duplicate within window
                }
            }
            *guard = Some((sig, now_ms));
            Some(bytes)
        }
    }

    /// Write UTF-16 text to the clipboard (CF_UNICODETEXT).
    pub fn write_text(text: &str) -> Result<(), String> {
        let wide_text: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
        let byte_len = wide_text.len() * 2;

        // Pre-seed LAST_TEXT_HASH so any WM_CLIPBOARDUPDATE event triggered by
        // this write — including a second one fired for format normalization
        // after SELF_WRITE_PENDING was already consumed — gets caught by the
        // content+time dedup in wnd_proc and never re-broadcasts back.
        {
            let now_ms = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_millis() as u64;
            let sig = fnv1a(text.as_bytes());
            *LAST_TEXT_HASH.lock().unwrap() = Some((sig, now_ms));
        }

        SELF_WRITE_PENDING.store(true, Ordering::SeqCst);
        unsafe {
            OpenClipboard(None).map_err(|e| {
                SELF_WRITE_PENDING.store(false, Ordering::SeqCst);
                format!("OpenClipboard: {e}")
            })?;
            EmptyClipboard().map_err(|e| {
                let _ = CloseClipboard();
                format!("EmptyClipboard: {e}")
            })?;

            let hmem = GlobalAlloc(GMEM_MOVEABLE, byte_len).map_err(|e| {
                let _ = CloseClipboard();
                format!("GlobalAlloc: {e}")
            })?;

            let ptr = GlobalLock(hmem) as *mut u16;
            if ptr.is_null() {
                let _ = CloseClipboard();
                return Err("GlobalLock returned null".to_string());
            }
            std::ptr::copy_nonoverlapping(wide_text.as_ptr(), ptr, wide_text.len());
            let _ = GlobalUnlock(hmem);

            // SetClipboardData takes Option<HANDLE>; HGLOBAL and HANDLE share the same layout
            let handle = HANDLE(hmem.0);
            SetClipboardData(CF_UNICODETEXT.0 as u32, Some(handle)).map_err(|e| {
                let _ = CloseClipboard();
                format!("SetClipboardData: {e}")
            })?;

            let _ = CloseClipboard();
        }
        Ok(())
    }

    /// Write raw DIB bytes to the clipboard (CF_DIB).
    pub fn write_image(dib_bytes: &[u8]) -> Result<(), String> {
        SELF_WRITE_PENDING.store(true, Ordering::SeqCst);
        unsafe {
            OpenClipboard(None).map_err(|e| {
                SELF_WRITE_PENDING.store(false, Ordering::SeqCst);
                format!("OpenClipboard: {e}")
            })?;
            EmptyClipboard().map_err(|e| {
                SELF_WRITE_PENDING.store(false, Ordering::SeqCst);
                let _ = CloseClipboard();
                format!("EmptyClipboard: {e}")
            })?;

            let hmem = GlobalAlloc(GMEM_MOVEABLE, dib_bytes.len()).map_err(|e| {
                SELF_WRITE_PENDING.store(false, Ordering::SeqCst);
                let _ = CloseClipboard();
                format!("GlobalAlloc: {e}")
            })?;

            let ptr = GlobalLock(hmem) as *mut u8;
            if ptr.is_null() {
                SELF_WRITE_PENDING.store(false, Ordering::SeqCst);
                let _ = CloseClipboard();
                return Err("GlobalLock returned null".to_string());
            }
            std::ptr::copy_nonoverlapping(dib_bytes.as_ptr(), ptr, dib_bytes.len());
            let _ = GlobalUnlock(hmem);

            let handle = HANDLE(hmem.0);
            SetClipboardData(CF_DIB.0 as u32, Some(handle)).map_err(|e| {
                SELF_WRITE_PENDING.store(false, Ordering::SeqCst);
                let _ = CloseClipboard();
                format!("SetClipboardData: {e}")
            })?;

            let _ = CloseClipboard();
        }
        // Do NOT reset SELF_WRITE_PENDING here. wnd_proc's swap(false) clears it
        // when WM_CLIPBOARDUPDATE arrives on the listener thread (async).
        Ok(())
    }

    /// Per-window data passed via GWLP_USERDATA.
    struct ListenerData {
        app: AppHandle,
    }

    unsafe extern "system" fn wnd_proc(
        hwnd: HWND,
        msg: u32,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        if msg == WM_DESTROY {
            PostQuitMessage(0);
            return LRESULT(0);
        }

        if msg == WM_CLIPBOARDUPDATE {
            // Our own write? Swallow the resulting notification.
            if SELF_WRITE_PENDING.swap(false, Ordering::SeqCst) {
                return LRESULT(0);
            }

            use windows::Win32::UI::WindowsAndMessaging::{GetWindowLongPtrW, GWLP_USERDATA};
            let data_ptr =
                GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *const ListenerData;

            if !data_ptr.is_null() {
                let data = &*data_ptr;

                if let Some(text) = read_text() {
                    // Screenshot tools write the saved PNG path as CF_UNICODETEXT.
                    // Skip sending it as clipboard text — the file watcher picks up
                    // the actual image from disk and sends it properly via upload_screenshot.
                    let is_image_path = {
                        let p = std::path::Path::new(&text);
                        p.exists() && matches!(
                            p.extension()
                                .and_then(|e| e.to_str())
                                .unwrap_or("")
                                .to_lowercase()
                                .as_str(),
                            "png" | "jpg" | "jpeg"
                        )
                    };
                    if is_image_path {
                        return LRESULT(0);
                    }

                    let now_ms = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .unwrap()
                        .as_millis() as u64;

                    let sig = fnv1a(text.as_bytes());
                    let mut guard = LAST_TEXT_HASH.lock().unwrap();
                    if let Some((prev_sig, prev_ms)) = *guard {
                        if prev_sig == sig && now_ms.saturating_sub(prev_ms) < TEXT_DEDUP_WINDOW_MS {
                            return LRESULT(0);
                        }
                    }
                    *guard = Some((sig, now_ms));

                    let _ = data.app.emit(
                        "clipboard:change",
                        serde_json::json!({ "kind": "text", "text": text }),
                    );
                }
            }
            return LRESULT(0);
        }

        DefWindowProcW(hwnd, msg, wparam, lparam)
    }

    /// Spawn a dedicated thread with a Win32 message loop for clipboard events.
    pub fn start_monitor(app: AppHandle) {
        tauri::async_runtime::spawn(async move {
            // Run the Win32 message loop in a blocking task so it stays on a dedicated thread
            // while still being managed by the tokio runtime — this lets `app.emit` work.
            tokio::task::spawn_blocking(move || unsafe {
                let class_name = wide("LiveClipClipboardWatcher");
                let wc = WNDCLASSW {
                    style: CS_HREDRAW | CS_VREDRAW,
                    lpfnWndProc: Some(wnd_proc),
                    lpszClassName: PCWSTR(class_name.as_ptr()),
                    ..Default::default()
                };
                RegisterClassW(&wc);

                let hwnd = CreateWindowExW(
                    WS_EX_NOACTIVATE,
                    PCWSTR(class_name.as_ptr()),
                    PCWSTR(wide("LiveClip Clipboard Watcher").as_ptr()),
                    WS_POPUP,
                    0,
                    0,
                    0,
                    0,
                    None,
                    None,
                    None,
                    None,
                )
                .expect("CreateWindowExW for clipboard watcher failed");

                // Store AppHandle in window user data so wnd_proc can reach it.
                use windows::Win32::UI::WindowsAndMessaging::{SetWindowLongPtrW, GWLP_USERDATA};
                let data = Box::new(ListenerData { app });
                let data_ptr = Box::into_raw(data);
                SetWindowLongPtrW(hwnd, GWLP_USERDATA, data_ptr as isize);

                AddClipboardFormatListener(hwnd).expect("AddClipboardFormatListener failed");

                let mut msg = MSG::default();
                while GetMessageW(&mut msg, None, 0, 0).as_bool() {
                    DispatchMessageW(&msg);
                }

                // Reclaim data on quit (reached only after WM_DESTROY).
                let _ = Box::from_raw(data_ptr);
            }).await.ok();
        });
    }
}

// ─── Public API (platform-dispatched) ────────────────────────────────────────

/// Start the background clipboard monitor. No-op on non-Windows.
pub fn start_monitor(app: AppHandle) {
    #[cfg(target_os = "windows")]
    win32::start_monitor(app);
    #[cfg(not(target_os = "windows"))]
    let _ = app;
}

/// Write text to the OS clipboard.
pub fn write_text(text: &str) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    return win32::write_text(text);
    #[cfg(not(target_os = "windows"))]
    {
        let _ = text;
        Ok(())
    }
}

/// Write raw DIB image bytes to the OS clipboard.
pub fn write_image(dib_bytes: &[u8]) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    return win32::write_image(dib_bytes);
    #[cfg(not(target_os = "windows"))]
    {
        let _ = dib_bytes;
        Ok(())
    }
}

/// Download an image from `url` using Bearer `jwt`, decode it, convert to DIB,
/// and write to the Windows clipboard.
pub async fn write_image_from_url(url: &str, jwt: &str) -> Result<(), String> {
    eprintln!("[write_image_from_url] downloading {}", url);
    // Download image bytes.
    let bytes = reqwest::Client::new()
        .get(url)
        .header("Authorization", format!("Bearer {jwt}"))
        .send()
        .await
        .map_err(|e| format!("download error: {e}"))?
        .bytes()
        .await
        .map_err(|e| format!("body error: {e}"))?;

    // Decode to RGBA8.
    let img = image::load_from_memory(&bytes)
        .map_err(|e| format!("image decode error: {e}"))?
        .to_rgba8();

    let (width, height) = img.dimensions();

    // Build DIB: BITMAPINFOHEADER (40 bytes) + BGRA pixel rows (top-down, negative biHeight).
    let row_bytes = (width * 4) as usize;
    let pixel_data_size = row_bytes * height as usize;
    let mut dib = Vec::with_capacity(40 + pixel_data_size);

    // BITMAPINFOHEADER fields
    let header_size: u32 = 40;
    let planes: u16 = 1;
    let bit_count: u16 = 32;
    let compression: u32 = 0; // BI_RGB
    let size_image: u32 = pixel_data_size as u32;
    let pels_per_meter: i32 = 2835; // ~72 DPI
    let bi_height: i32 = -(height as i32); // negative = top-down

    dib.extend_from_slice(&header_size.to_le_bytes());
    dib.extend_from_slice(&(width as i32).to_le_bytes());
    dib.extend_from_slice(&bi_height.to_le_bytes());
    dib.extend_from_slice(&planes.to_le_bytes());
    dib.extend_from_slice(&bit_count.to_le_bytes());
    dib.extend_from_slice(&compression.to_le_bytes());
    dib.extend_from_slice(&size_image.to_le_bytes());
    dib.extend_from_slice(&pels_per_meter.to_le_bytes());
    dib.extend_from_slice(&pels_per_meter.to_le_bytes());
    dib.extend_from_slice(&0u32.to_le_bytes()); // clrUsed
    dib.extend_from_slice(&0u32.to_le_bytes()); // clrImportant

    // Pixel rows: convert RGBA → BGRA
    for pixel in img.pixels() {
        let [r, g, b, a] = pixel.0;
        dib.push(b);
        dib.push(g);
        dib.push(r);
        dib.push(a);
    }

    write_image(&dib)
}
