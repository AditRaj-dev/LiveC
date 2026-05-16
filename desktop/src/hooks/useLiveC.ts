import { useState, useEffect, useCallback, useRef } from "react";
import { listen } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";
import type { ClipEntry, RoomState, Device, FileTransfer } from "../types";
import { MESSAGE_TYPES } from "../protocol";

// ─── Persistence helpers ─────────────────────────────────────────────────────
const CLIPBOARD_STORAGE_KEY = "livec.clipboard.entries";
const TRANSFERS_STORAGE_KEY = "livec.transfers";

function loadFromStorage<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return fallback;
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function saveToStorage(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* quota / disabled — ignore */
  }
}

// ─── Clipboard hook ───────────────────────────────────────────────────────────
const CLEAR_GUARD_MS = 400;

export function useClipboard() {
  const [entries, setEntries] = useState<ClipEntry[]>(() =>
    loadFromStorage<ClipEntry[]>(CLIPBOARD_STORAGE_KEY, [])
  );
  const entryIdRef = useRef(0);
  // Tombstone for clear: any addEntry call within this window after a clear
  // is dropped. Prevents the React batching race where a clipboard:change or
  // relay:* event fires in the same tick as a clear and re-populates state.
  const clearedAtRef = useRef(0);

  // Persist entries on every change — survives Ctrl+R reload
  useEffect(() => {
    saveToStorage(CLIPBOARD_STORAGE_KEY, entries);
  }, [entries]);

  const addEntry = useCallback((entry: Omit<ClipEntry, "id" | "timestamp">) => {
    if (Date.now() - clearedAtRef.current < CLEAR_GUARD_MS) return;
    const newEntry: ClipEntry = {
      ...entry,
      id: String(++entryIdRef.current),
      timestamp: Date.now(),
    };
    setEntries((prev) => [newEntry, ...prev].slice(0, 100));
  }, []);

  const clearEntries = useCallback(() => {
    clearedAtRef.current = Date.now();
    entryIdRef.current = 0;
    setEntries([]);
    saveToStorage(CLIPBOARD_STORAGE_KEY, []);
    // Broadcast to peers so they clear too
    invoke("broadcast_clear", { kind: "clipboard" }).catch((err) => {
      console.error("[clearEntries] broadcast failed:", err);
    });
  }, []);

  // Remote peer cleared their clipboard — drop our local view too
  useEffect(() => {
    const unlisten = listen("relay:clipboard_clear", () => {
      clearedAtRef.current = Date.now();
      setEntries([]);
      saveToStorage(CLIPBOARD_STORAGE_KEY, []);
    });
    return () => { unlisten.then((fn) => fn()); };
  }, []);

  // Local clipboard change — add to feed and auto-broadcast text.
  // Sends are debounced: if you copy several items in rapid succession, only the
  // final one is pushed to peers. Avoids spamming Android with every intermediate
  // clipboard fire (rich-text format normalization, clipboard-history hand-offs,
  // accidental double-copies, etc.).
  const pendingSendRef = useRef<{ text: string; timer: ReturnType<typeof setTimeout> } | null>(null);
  const SEND_DEBOUNCE_MS = 350;

  useEffect(() => {
    const unlisten = listen<{ kind: "text" | "image"; text?: string; sizeBytes?: number }>(
      "clipboard:change",
      (event) => {
        const { kind, text, sizeBytes } = event.payload;
        addEntry({ kind, text, sizeBytes, source: "local" });
        if (kind === "text" && text) {
          // Cancel any pending send and queue this newer one.
          if (pendingSendRef.current) clearTimeout(pendingSendRef.current.timer);
          const timer = setTimeout(() => {
            invoke("send_clipboard_text", { text }).catch(() => {});
            pendingSendRef.current = null;
          }, SEND_DEBOUNCE_MS);
          pendingSendRef.current = { text, timer };
        }
      }
    );
    return () => {
      unlisten.then((fn) => fn());
      if (pendingSendRef.current) {
        clearTimeout(pendingSendRef.current.timer);
        pendingSendRef.current = null;
      }
    };
  }, [addEntry]);

  // Remote clipboard text from relay
  useEffect(() => {
    const unlisten = listen<{ text: string; from: string }>(
      "relay:clipboard_text",
      (event) => {
        addEntry({ kind: "text", text: event.payload.text, source: "remote" });
      }
    );
    return () => { unlisten.then((fn) => fn()); };
  }, [addEntry]);

  // Remote clipboard image from relay
  useEffect(() => {
    const unlisten = listen<{ fileId: string; downloadUrl: string; from: string }>(
      `relay:${MESSAGE_TYPES.CLIPBOARD_IMAGE}`,
      (event) => {
        addEntry({ kind: "image", text: event.payload.downloadUrl, source: "remote" });
      }
    );
    return () => { unlisten.then((fn) => fn()); };
  }, [addEntry]);

  const copyEntry = useCallback((entry: ClipEntry) => {
    if (entry.kind === "text" && entry.text) {
      navigator.clipboard.writeText(entry.text);
    }
  }, []);

  const sendToRelay = useCallback(async (text: string) => {
    await invoke("send_clipboard_text", { text }).catch(console.error);
  }, []);

  return { entries, addEntry, copyEntry, sendToRelay, clearEntries };
}

// ─── Connection & room hook ───────────────────────────────────────────────────
export function useRoomState() {
  const [room, setRoom] = useState<RoomState>({ roomId: null, connected: false, devices: [] });

  useEffect(() => {
    invoke<boolean>("get_connection_status").then((connected) => {
      if (connected) setRoom((r) => ({ ...r, connected }));
    }).catch(() => {});

    invoke<{ roomToken: string }>("get_config").then((cfg) => {
      setRoom((r) => ({ ...r, roomId: cfg.roomToken }));
    }).catch(() => {});
  }, []);

  useEffect(() => {
    const unlisten = listen<{ connected: boolean; relayUrl: string }>(
      "connection:status",
      (event) => {
        setRoom((r) => ({ ...r, connected: event.payload.connected }));
      }
    );
    return () => { unlisten.then((fn) => fn()); };
  }, []);

  useEffect(() => {
    const unlisten = listen<{ deviceId: string; deviceName: string; platform: string }>(
      "relay:device_join",
      (event) => {
        const { deviceId, deviceName, platform } = event.payload;
        setRoom((r) => {
          const exists = r.devices.some((d) => d.id === deviceId);
          if (exists) return r;
          const newDevice: Device = {
            id: deviceId,
            label: deviceName,
            platform: platform as Device["platform"],
            lastSeen: Date.now(),
          };
          return { ...r, devices: [...r.devices, newDevice] };
        });
      }
    );
    return () => { unlisten.then((fn) => fn()); };
  }, []);

  useEffect(() => {
    const unlisten = listen<{ deviceId: string }>(
      "relay:device_leave",
      (event) => {
        setRoom((r) => ({
          ...r,
          devices: r.devices.filter((d) => d.id !== event.payload.deviceId),
        }));
      }
    );
    return () => { unlisten.then((fn) => fn()); };
  }, []);

  useEffect(() => {
    const unlisten = listen("tray:leave_room", () => {
      setRoom({ roomId: null, connected: false, devices: [] });
    });
    return () => { unlisten.then((fn) => fn()); };
  }, []);

  return { room, setRoom };
}

// ─── Config hook ─────────────────────────────────────────────────────────────
export function useConfig() {
  const [config, setConfig] = useState<{
    deviceId: string;
    deviceName: string;
    roomToken: string;
    relayUrl: string;
    screenshotFolder: string;
  } | null>(null);

  useEffect(() => {
    invoke<any>("get_config").then(setConfig).catch(console.error);
  }, []);

  const updateDeviceName = useCallback(async (name: string) => {
    await invoke("update_device_name", { name });
    setConfig((prev) => prev ? { ...prev, deviceName: name } : null);
  }, []);

  const updateRelayUrl = useCallback(async (url: string) => {
    await invoke("update_relay_url", { url });
    setConfig((prev) => prev ? { ...prev, relayUrl: url } : null);
  }, []);

  const updateScreenshotFolder = useCallback(async (folder: string) => {
    await invoke("update_screenshot_folder", { folder });
    setConfig((prev) => prev ? { ...prev, screenshotFolder: folder } : null);
  }, []);

  return { config, updateDeviceName, updateRelayUrl, updateScreenshotFolder };
}

// ─── File transfers hook ──────────────────────────────────────────────────────
const TRANSFER_CLEAR_GUARD_MS = 400;

export function useFileTransfers() {
  const [transfers, setTransfers] = useState<FileTransfer[]>(() =>
    loadFromStorage<FileTransfer[]>(TRANSFERS_STORAGE_KEY, [])
  );
  const transfersRef = useRef<FileTransfer[]>(transfers);
  // Tombstone: prevent addTransfer from re-populating after a clear
  const transferClearedAtRef = useRef(0);

  // Persist transfers on every change — survives Ctrl+R reload
  useEffect(() => {
    saveToStorage(TRANSFERS_STORAGE_KEY, transfers);
  }, [transfers]);

  // Remote peer cleared their transfers — drop our local view too
  useEffect(() => {
    const unlisten = listen("relay:files_clear", () => {
      setTransfers([]);
      transfersRef.current = [];
    });
    return () => { unlisten.then((fn) => fn()); };
  }, []);

  const updateTransfers = useCallback((updater: (prev: FileTransfer[]) => FileTransfer[]) => {
    setTransfers((prev) => {
      const next = updater(prev);
      transfersRef.current = next;
      return next;
    });
  }, []);

  const addTransfer = useCallback((t: FileTransfer) => {
    if (Date.now() - transferClearedAtRef.current < TRANSFER_CLEAR_GUARD_MS) return;
    updateTransfers((prev) => [t, ...prev].slice(0, 50));
  }, [updateTransfers]);

  // Incoming file_meta from relay
  useEffect(() => {
    const unlisten = listen<{
      fileId: string; name: string; size: number; downloadUrl: string; from: string;
    }>("relay:file_meta", (event) => {
      const { fileId, name, size, downloadUrl, from } = event.payload;
      addTransfer({
        id: fileId || String(Date.now()),
        name: name || "file",
        size: size || 0,
        downloadUrl,
        from,
        timestamp: Date.now(),
        status: "pending",
        direction: "incoming",
      });
    });
    return () => { unlisten.then((fn) => fn()); };
  }, [addTransfer]);

  // Relay TTL expiry — mark the transfer as errored
  useEffect(() => {
    const unlisten = listen<{ fileId: string }>("relay:file_expired", (event) => {
      const { fileId } = event.payload;
      updateTransfers((prev) =>
        prev.map((t) =>
          t.id === fileId ? { ...t, status: "error", errorMsg: "Expired on relay" } : t
        )
      );
    });
    return () => { unlisten.then((fn) => fn()); };
  }, [updateTransfers]);

  // Files dropped onto the main window
  useEffect(() => {
    const unlisten = listen<{ files: string[] }>("main:file_drop", async (event) => {
      for (const path of event.payload.files ?? []) {
        try {
          const downloadUrl = await invoke<string>("upload_file", { path });
          const name = path.split(/[\\/]/).pop() ?? "file";
          addTransfer({
            id: String(Date.now()),
            name,
            size: 0,
            downloadUrl,
            from: "local",
            timestamp: Date.now(),
            status: "done",
            direction: "outgoing",
          });
        } catch (err) {
          console.error("Drop upload failed:", err);
        }
      }
    });
    return () => { unlisten.then((fn) => fn()); };
  }, [addTransfer]);

  const downloadTransfer = useCallback(async (id: string) => {
    const transfer = transfersRef.current.find((t) => t.id === id);
    if (!transfer) return;

    updateTransfers((prev) => prev.map((t) => t.id === id ? { ...t, status: "downloading" } : t));
    try {
      const savedPath = await invoke<string>("download_file", {
        url: transfer.downloadUrl,
        filename: transfer.name,
      });
      updateTransfers((prev) => prev.map((t) => t.id === id ? { ...t, status: "done", savedPath } : t));
    } catch (err) {
      const msg = typeof err === "string" ? err : (err as any)?.message ?? "Download failed";
      updateTransfers((prev) => prev.map((t) => t.id === id ? { ...t, status: "error", errorMsg: msg } : t));
    }
  }, [updateTransfers]);

  const addOutgoing = useCallback((name: string, downloadUrl: string) => {
    addTransfer({
      id: String(Date.now()),
      name,
      size: 0,
      downloadUrl,
      from: "local",
      timestamp: Date.now(),
      status: "done",
      direction: "outgoing",
    });
  }, [addTransfer]);

  // Returns a stable id that can be used with updateTransferById
  const startUpload = useCallback((name: string): string => {
    const id = `upload_${Date.now()}_${Math.random()}`;
    addTransfer({
      id,
      name,
      size: 0,
      downloadUrl: "",
      from: "local",
      timestamp: Date.now(),
      status: "uploading",
      direction: "outgoing",
    });
    return id;
  }, [addTransfer]);

  const updateTransferById = useCallback((id: string, patch: Partial<FileTransfer>) => {
    updateTransfers((prev) => prev.map((t) => t.id === id ? { ...t, ...patch } : t));
  }, [updateTransfers]);

  const clearTransfers = useCallback(() => {
    transferClearedAtRef.current = Date.now();
    updateTransfers(() => []);
    saveToStorage(TRANSFERS_STORAGE_KEY, []);
    // Broadcast to peers so they clear too
    invoke("broadcast_clear", { kind: "files" }).catch((err) => {
      console.error("[clearTransfers] broadcast failed:", err);
    });
  }, [updateTransfers]);

  /**
   * Reject/dismiss a single transfer. Removes it from the local list
   * and (for pending incoming files with a downloadUrl) tells the relay
   * to delete the staged file so it doesn't sit around for 90s.
   */
  const dismissTransfer = useCallback((id: string) => {
    const target = transfersRef.current.find((t) => t.id === id);
    updateTransfers((prev) => prev.filter((t) => t.id !== id));
    if (target?.downloadUrl) {
      invoke("delete_relay_file", { url: target.downloadUrl }).catch((err) => {
        console.error("[dismissTransfer] relay delete failed:", err);
      });
    }
  }, [updateTransfers]);

  return { transfers, downloadTransfer, addOutgoing, startUpload, updateTransferById, clearTransfers, dismissTransfer };
}
