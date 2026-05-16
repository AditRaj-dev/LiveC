/// Screenshot detection module.
///
/// Polls `%USERPROFILE%\Pictures\Screenshots` for new PNG/JPG files
/// (created by Win+PrtSc or the Snipping Tool).  Only emits for files
/// created after the watcher starts — pre-existing files are ignored.
///
///   `screenshot:detected` → `{ source: "file", path: "C:\\...\\screenshot.png" }`
use std::collections::HashSet;
use std::path::PathBuf;
use std::time::{Duration, SystemTime};
use tauri::{AppHandle, Emitter};

pub fn start_watcher(app: AppHandle, config: crate::config::SharedConfig) {
    let watch_dir: PathBuf = {
        let folder = config.read().unwrap().screenshot_folder.clone();
        if folder.is_empty() {
            match std::env::var("USERPROFILE") {
                Ok(home) => PathBuf::from(home).join("Pictures").join("Screenshots"),
                Err(_) => {
                    eprintln!("[screenshot_watcher] USERPROFILE not set, watcher disabled");
                    return;
                }
            }
        } else {
            PathBuf::from(folder)
        }
    };

    if !watch_dir.exists() {
        if std::fs::create_dir_all(&watch_dir).is_err() {
            eprintln!("[screenshot_watcher] cannot create dir {:?}", watch_dir);
            return;
        }
    }

    eprintln!("[screenshot_watcher] polling {:?}", watch_dir);

    tauri::async_runtime::spawn(async move {
        // Snapshot the directory's mtime when we start — we only care about
        // files created after this point.
        let start_time = SystemTime::now();
        let mut interval = tokio::time::interval(Duration::from_secs(1));
        let mut seen: HashSet<PathBuf> = HashSet::new();

        loop {
            interval.tick().await;

            let read_dir = match std::fs::read_dir(&watch_dir) {
                Ok(d) => d,
                Err(_) => continue,
            };

            for entry in read_dir.flatten() {
                let path = entry.path();

                // Only PNG/JPG files.
                let ext = path
                    .extension()
                    .and_then(|e| e.to_str())
                    .unwrap_or("")
                    .to_lowercase();
                if !matches!(ext.as_str(), "png" | "jpg" | "jpeg") {
                    continue;
                }

                // Skip directories.
                if entry.file_type().map(|ft| ft.is_dir()).unwrap_or(false) {
                    continue;
                }

                let mtime = match entry.metadata().and_then(|m| m.modified()) {
                    Ok(t) => t,
                    Err(_) => continue,
                };

                // Ignore files that existed before we started.
                if mtime <= start_time {
                    continue;
                }

                let path_str = path.to_string_lossy().to_string();

                // Deduplicate: only emit once per file path.
                if !seen.insert(path.clone()) {
                    continue;
                }

                eprintln!("[screenshot_watcher] new screenshot: {path_str}");

                let app_clone = app.clone();
                tauri::async_runtime::spawn(async move {
                    let _ = app_clone.emit(
                        "screenshot:detected",
                        serde_json::json!({
                            "source": "file",
                            "path": path_str,
                        }),
                    );
                });
            }
        }
    });
}
