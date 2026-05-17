import { useState, useCallback, useEffect, useRef } from "react";
import {
  Clipboard,
  Monitor,
  Settings,
  Smartphone,
  Wifi,
  WifiOff,
  Copy,
  Image,
  Clock,
  MonitorUp,
  Download,
  Upload,
  FolderOpen,
  Check,
  X,
  Cloud,
  FileText,
  Brush,
} from "lucide-react";
import { listen } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";
import { QRCodeSVG } from "qrcode.react";
import { useClipboard, useRoomState, useConfig, useFileTransfers } from "./hooks/useLiveC";
import type { ClipEntry, FileTransfer } from "./types";

function cn(...classes: (string | false | undefined | null)[]) {
  return classes.filter(Boolean).join(" ");
}

function timeAgo(ts: number) {
  const diff = (Date.now() - ts) / 1000;
  if (diff < 5) return "just now";
  if (diff < 60) return `${Math.floor(diff)}s ago`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  return `${Math.floor(diff / 3600)}h ago`;
}

function formatBytes(bytes: number) {
  if (bytes === 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// ─── TopBar ───────────────────────────────────────────────────────────────────
function TopBar({
  connected,
  roomId,
  onOpenSettings,
}: {
  connected: boolean;
  roomId: string | null;
  onOpenSettings: () => void;
}) {
  return (
    <div
      className="h-12 border-b border-default bg-surface/60 backdrop-blur-md flex items-center justify-between px-4 shrink-0 z-10"
      data-tauri-drag-region
    >
      <div className="flex items-center gap-2.5 pointer-events-none select-none">
        <div className="w-6 h-6 rounded-md bg-accent flex items-center justify-center shadow-[0_0_12px_rgba(251,191,36,0.4)]">
          <MonitorUp className="w-3.5 h-3.5 text-[rgb(var(--bg-base))]" />
        </div>
        <span className="font-display font-semibold text-sm tracking-tight text-primary">LiveC</span>
        {roomId && (
          <span className="text-xs px-2 py-0.5 rounded-full bg-elevated border border-default text-tertiary font-mono">
            #{roomId}
          </span>
        )}
      </div>

      <div className="flex items-center gap-2">
        <div
          className={cn(
            "flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full border transition-all",
            connected
              ? "border-[rgb(var(--sev-low)/0.4)] bg-[rgb(var(--sev-low)/0.08)] text-[rgb(var(--sev-low))]"
              : "border-default bg-elevated text-tertiary"
          )}
        >
          {connected ? (
            <><Wifi className="w-3 h-3" /> Connected</>
          ) : (
            <><WifiOff className="w-3 h-3" /> Disconnected</>
          )}
        </div>
        <button
          onClick={onOpenSettings}
          className="w-7 h-7 rounded-md flex items-center justify-center hover:bg-elevated text-tertiary hover:text-primary transition-colors"
        >
          <Settings className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  );
}

// ─── Left Panel — Devices ─────────────────────────────────────────────────────
function LeftPanel({
  connected,
  roomId,
  devices,
  config,
  onLeave,
  onConnect,
}: {
  connected: boolean;
  roomId: string | null;
  devices: { id: string; label: string; platform: string; lastSeen: number }[];
  config: any;
  onLeave: () => void;
  onConnect: (token: string) => void;
}) {
  const [inputRoom, setInputRoom] = useState("");
  // Phase 5b: include our fingerprint so the joiner can pin it as trusted.
  const qrValue = config
    ? JSON.stringify({
        relayUrl: config.relayUrl,
        roomToken: config.roomToken,
        fingerprint: config.fingerprint ?? "",
        deviceName: config.deviceName ?? "",
      })
    : "";

  return (
    <div className="w-56 border-r border-default bg-base/60 flex flex-col shrink-0 overflow-hidden">
      <div className="px-3 pt-3 pb-2 border-b border-default/50 shrink-0">
        <p className="text-xs font-semibold text-tertiary uppercase tracking-wider">Devices</p>
      </div>

      <div className="flex-1 overflow-y-auto p-3 space-y-3">
        {/* Connection status / Join */}
        {!connected ? (
          <div className="space-y-2">
            <div className="flex gap-1.5">
              <input
                className="flex-1 min-w-0 bg-elevated border border-default rounded-lg px-2 py-1.5 text-xs text-primary placeholder:text-tertiary outline-none focus:border-accent/60 font-mono"
                placeholder="Room code…"
                value={inputRoom}
                onChange={(e) => setInputRoom(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter" && inputRoom.trim()) onConnect(inputRoom.trim()); }}
              />
              <button
                onClick={() => inputRoom.trim() && onConnect(inputRoom.trim())}
                className="px-2.5 py-1.5 rounded-lg bg-accent text-[rgb(var(--bg-base))] text-xs font-semibold hover:bg-accent/90 transition-colors"
              >
                Join
              </button>
            </div>
          </div>
        ) : (
          <div className="p-2.5 rounded-xl border border-[rgb(var(--sev-low)/0.3)] bg-[rgb(var(--sev-low)/0.06)]">
            <div className="flex items-center justify-between mb-1">
              <div className="flex items-center gap-1.5">
                <div className="w-1.5 h-1.5 rounded-full bg-[rgb(var(--sev-low))] animate-pulse" />
                <span className="text-xs font-semibold text-primary">Live</span>
              </div>
              <button
                onClick={onLeave}
                className="text-[10px] text-tertiary hover:text-[rgb(var(--sev-critical))] transition-colors"
              >
                Leave
              </button>
            </div>
            <p className="text-[10px] text-tertiary font-mono truncate">#{roomId}</p>
            <div className="flex items-center gap-1 mt-1">
              <Cloud className="w-3 h-3 text-tertiary" />
              <span className="text-[10px] text-tertiary">Via relay</span>
            </div>
          </div>
        )}

        {/* QR code */}
        {qrValue && (
          <div className="flex flex-col items-center gap-2 p-2.5 rounded-xl border border-default bg-elevated">
            <p className="text-[10px] text-tertiary">Scan to pair</p>
            <div className="p-1.5 bg-white rounded-lg">
              <QRCodeSVG value={qrValue} size={112} level="H" includeMargin={false} />
            </div>
          </div>
        )}

        {/* Device list */}
        {devices.length > 0 ? (
          <div className="space-y-1.5">
            {devices.map((d) => {
              const Icon = d.platform === "android" ? Smartphone : Monitor;
              return (
                <div
                  key={d.id}
                  className="flex items-center gap-2 p-2 rounded-lg border border-default bg-elevated"
                >
                  <div className="w-7 h-7 rounded-md bg-[rgb(var(--sev-low)/0.12)] border border-[rgb(var(--sev-low)/0.2)] flex items-center justify-center shrink-0">
                    <Icon className="w-3.5 h-3.5 text-[rgb(var(--sev-low))]" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-xs font-medium text-primary truncate">{d.label}</p>
                    <p className="text-[10px] text-tertiary">{timeAgo(d.lastSeen)}</p>
                  </div>
                  <div className="w-1.5 h-1.5 rounded-full bg-[rgb(var(--sev-low))] shrink-0" />
                </div>
              );
            })}
          </div>
        ) : connected ? (
          <div className="text-center py-4">
            <Monitor className="w-6 h-6 text-tertiary mx-auto mb-1.5" />
            <p className="text-xs text-tertiary">No other devices yet</p>
          </div>
        ) : null}
      </div>
    </div>
  );
}

// ─── Center Panel — Clipboard ─────────────────────────────────────────────────
function ClipCard({ entry, onCopy }: { entry: ClipEntry; onCopy: (e: ClipEntry) => void }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    onCopy(entry);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <div className="group relative p-3 rounded-xl border border-default bg-elevated hover:border-accent/30 transition-all cursor-default">
      <div className="flex items-center justify-between mb-1.5">
        <div className="flex items-center gap-1.5">
          {entry.kind === "text" ? (
            <Clipboard className="w-3 h-3 text-accent" />
          ) : (
            <Image className="w-3 h-3 text-[rgb(var(--sev-med))]" />
          )}
          <span className="text-[10px] font-semibold uppercase tracking-wider text-tertiary">
            {entry.kind}
          </span>
          {entry.source === "remote" && (
            <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-[rgb(var(--sev-med)/0.12)] text-[rgb(var(--sev-med))] border border-[rgb(var(--sev-med)/0.2)]">
              remote
            </span>
          )}
        </div>
        <div className="flex items-center gap-1.5">
          <span className="text-[10px] text-tertiary flex items-center gap-1">
            <Clock className="w-3 h-3" />
            {timeAgo(entry.timestamp)}
          </span>
          {entry.kind === "text" && (
            <button
              onClick={handleCopy}
              className="opacity-0 group-hover:opacity-100 w-5 h-5 rounded flex items-center justify-center hover:bg-surface text-tertiary hover:text-primary transition-all"
            >
              <Copy className="w-3 h-3" />
            </button>
          )}
        </div>
      </div>

      {entry.kind === "text" && entry.text && (
        <p className="text-sm text-primary font-mono leading-relaxed line-clamp-3 break-all">
          {entry.text}
        </p>
      )}
      {entry.kind === "image" && (
        <div className="flex flex-col gap-1.5">
          {entry.text?.startsWith("http") ? (
            <img src={entry.text} alt="Clipboard" className="max-h-28 rounded object-contain bg-black/10" />
          ) : (
            <p className="text-xs text-secondary">
              Image{entry.sizeBytes ? ` · ${formatBytes(entry.sizeBytes)}` : ""}
            </p>
          )}
        </div>
      )}

      {copied && (
        <div className="absolute inset-0 rounded-xl bg-elevated/90 flex items-center justify-center">
          <span className="text-sm text-accent font-medium">Copied!</span>
        </div>
      )}
    </div>
  );
}

function ClipboardPanel({
  entries,
  onCopy,
  onClear,
}: {
  entries: ClipEntry[];
  onCopy: (e: ClipEntry) => void;
  onClear: () => void;
}) {
  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <div className="px-4 pt-4 pb-2.5 border-b border-default/50 shrink-0 flex items-start justify-between gap-2">
        <div>
          <h2 className="text-sm font-semibold text-primary">Clipboard</h2>
          <p className="text-xs text-tertiary mt-0.5">
            {entries.length} item{entries.length !== 1 ? "s" : ""}
          </p>
        </div>
        {entries.length > 0 && (
          <button
            onClick={onClear}
            title="Clear clipboard history (syncs to all devices)"
            className="w-6 h-6 flex items-center justify-center rounded hover:bg-elevated text-tertiary hover:text-primary transition-colors shrink-0 mt-0.5"
          >
            <Brush className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      <div className="flex-1 overflow-y-auto px-4 py-3 space-y-2">
        {entries.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-center py-12">
            <div className="w-12 h-12 rounded-2xl bg-elevated border border-default flex items-center justify-center mb-3">
              <Clipboard className="w-5 h-5 text-tertiary" />
            </div>
            <p className="text-sm font-medium text-secondary">No clipboard activity</p>
            <p className="text-xs text-tertiary mt-1">Copy text on any connected device.</p>
          </div>
        ) : (
          entries.map((entry) => (
            <ClipCard key={entry.id} entry={entry} onCopy={onCopy} />
          ))
        )}
      </div>
    </div>
  );
}

// ─── Right Panel — File Transfers ─────────────────────────────────────────────
function TransferRow({
  transfer,
  onDownload,
  onReveal,
  onDismiss,
  onAccept,
  onReject,
}: {
  transfer: FileTransfer;
  onDownload: (id: string) => void;
  onReveal: (path: string) => void;
  onDismiss: (id: string) => void;
  onAccept: (offerId: string) => void;
  onReject: (offerId: string) => void;
}) {
  const isIncoming = transfer.direction === "incoming";
  const ext = transfer.name.split(".").pop()?.toLowerCase() ?? "";
  const canDismiss = transfer.status !== "downloading" && transfer.status !== "uploading";

  return (
    <div className="group relative flex items-start gap-2.5 p-2.5 rounded-xl border border-default bg-elevated hover:border-accent/20 transition-all">
      {canDismiss && (
        <button
          onClick={() => onDismiss(transfer.id)}
          title={isIncoming && transfer.status === "pending" ? "Reject — delete from relay" : "Remove from list"}
          className="absolute top-1.5 right-1.5 w-5 h-5 flex items-center justify-center rounded opacity-0 group-hover:opacity-100 hover:bg-[rgb(var(--sev-critical)/0.15)] text-tertiary hover:text-[rgb(var(--sev-critical))] transition-all"
        >
          <X className="w-3 h-3" />
        </button>
      )}
      <div className="w-8 h-8 rounded-lg bg-[rgb(var(--sev-med)/0.1)] border border-[rgb(var(--sev-med)/0.2)] flex items-center justify-center shrink-0 mt-0.5">
        {isIncoming ? (
          <Download className="w-3.5 h-3.5 text-[rgb(var(--sev-med))]" />
        ) : (
          <Upload className="w-3.5 h-3.5 text-accent" />
        )}
      </div>

      <div className="flex-1 min-w-0">
        <p className="text-xs font-medium text-primary truncate">{transfer.name}</p>
        <div className="flex items-center gap-1.5 mt-0.5">
          {transfer.size > 0 && (
            <span className="text-[10px] text-tertiary">{formatBytes(transfer.size)}</span>
          )}
          {ext && (
            <span className="text-[10px] text-tertiary uppercase font-mono">{ext}</span>
          )}
          <span className="text-[10px] text-tertiary">{timeAgo(transfer.timestamp)}</span>
        </div>
        <p className="text-[10px] text-tertiary mt-0.5">
          {isIncoming ? `from ${transfer.from.slice(0, 8)}…` : "sent"}
        </p>

        {transfer.status === "error" && (
          <p className="text-[10px] text-[rgb(var(--sev-critical))] mt-0.5">{transfer.errorMsg}</p>
        )}
        {(transfer.status === "uploading" || transfer.status === "downloading") && (
          <div className="mt-1.5 space-y-0.5">
            <div className="h-1 rounded-full bg-elevated overflow-hidden">
              <div
                className="h-full bg-accent transition-[width] duration-150 ease-out"
                style={{ width: `${Math.round((transfer.progress ?? 0) * 100)}%` }}
              />
            </div>
            <p className="text-[9px] text-tertiary tabular-nums">
              {Math.round((transfer.progress ?? 0) * 100)}%
            </p>
          </div>
        )}
        {transfer.status === "done" && transfer.savedPath && (
          <button
            onClick={() => onReveal(transfer.savedPath!)}
            className="text-[10px] text-accent hover:underline mt-0.5 flex items-center gap-0.5"
          >
            <FolderOpen className="w-3 h-3" /> Show in folder
          </button>
        )}
      </div>

      <div className="flex flex-col items-end gap-1.5 shrink-0 mt-5">
        {transfer.status === "offer_pending" && isIncoming && (
          <div className="flex items-center gap-1">
            <button
              onClick={() => onAccept(transfer.id)}
              className="px-2 py-1 rounded-md bg-[rgb(var(--sev-low)/0.15)] border border-[rgb(var(--sev-low)/0.3)] text-[rgb(var(--sev-low))] text-[10px] font-semibold hover:bg-[rgb(var(--sev-low)/0.25)] transition-colors"
            >
              Accept
            </button>
            <button
              onClick={() => onReject(transfer.id)}
              className="px-2 py-1 rounded-md bg-[rgb(var(--sev-critical)/0.12)] border border-[rgb(var(--sev-critical)/0.3)] text-[rgb(var(--sev-critical))] text-[10px] font-semibold hover:bg-[rgb(var(--sev-critical)/0.2)] transition-colors"
            >
              Reject
            </button>
          </div>
        )}
        {transfer.status === "pending" && isIncoming && (
          <button
            onClick={() => onDownload(transfer.id)}
            className="px-2 py-1 rounded-md bg-[rgb(var(--sev-med)/0.15)] border border-[rgb(var(--sev-med)/0.3)] text-[rgb(var(--sev-med))] text-[10px] font-semibold hover:bg-[rgb(var(--sev-med)/0.25)] transition-colors"
          >
            Save
          </button>
        )}
        {(transfer.status === "downloading" || transfer.status === "uploading") && (
          <span className="text-[10px] text-tertiary animate-pulse">
            {transfer.status === "uploading" ? "↑" : "↓"}
          </span>
        )}
        {transfer.status === "done" && (
          <Check className="w-3.5 h-3.5 text-[rgb(var(--sev-low))]" />
        )}
        {transfer.status === "error" && (
          <X className="w-3.5 h-3.5 text-[rgb(var(--sev-critical))]" />
        )}
      </div>
    </div>
  );
}

function RightPanel({
  transfers,
  onDownload,
  onReveal,
  onBrowse,
  onClear,
  onDismiss,
  onAccept,
  onReject,
}: {
  transfers: FileTransfer[];
  onDownload: (id: string) => void;
  onReveal: (path: string) => void;
  onBrowse: () => void;
  onClear: () => void;
  onDismiss: (id: string) => void;
  onAccept: (offerId: string) => void;
  onReject: (offerId: string) => void;
}) {
  const [isDragOver, setIsDragOver] = useState(false);

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(true);
  };

  const handleDragLeave = () => setIsDragOver(false);

  // Files dropped in the main window are handled via main:file_drop Rust event
  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
  };

  return (
    <div className="w-72 border-l border-default bg-base/60 flex flex-col shrink-0 overflow-hidden">
      <div className="px-3 pt-3 pb-2 border-b border-default/50 shrink-0 flex items-center justify-between">
        <p className="text-xs font-semibold text-tertiary uppercase tracking-wider">File Transfers</p>
        <div className="flex items-center gap-2">
          <span className="text-[10px] text-tertiary">{transfers.length}</span>
          {transfers.length > 0 && (
            <button
              onClick={onClear}
              title="Clear history"
              className="w-5 h-5 flex items-center justify-center rounded hover:bg-elevated text-tertiary hover:text-primary transition-colors"
            >
              <Brush className="w-3 h-3" />
            </button>
          )}
        </div>
      </div>

      {/* Drop zone + Browse */}
      <div
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        className={cn(
          "m-3 rounded-xl border-2 border-dashed transition-all flex flex-col items-center justify-center gap-2 py-4 shrink-0",
          isDragOver
            ? "border-accent/60 bg-accent/5"
            : "border-default/60 hover:border-default"
        )}
      >
        <Upload className={cn("w-5 h-5", isDragOver ? "text-accent" : "text-tertiary")} />
        <p className="text-xs text-tertiary text-center leading-relaxed">
          Drop files here or
        </p>
        <button
          onClick={onBrowse}
          className="px-3 py-1.5 rounded-lg bg-elevated border border-default text-xs font-medium text-secondary hover:text-primary hover:border-accent/40 transition-colors flex items-center gap-1.5"
        >
          <FolderOpen className="w-3.5 h-3.5" />
          Browse
        </button>
      </div>

      {/* Transfer list */}
      <div className="flex-1 overflow-y-auto px-3 pb-3 space-y-2">
        {transfers.length === 0 ? (
          <div className="flex flex-col items-center py-6 text-center">
            <FileText className="w-6 h-6 text-tertiary mb-2" />
            <p className="text-xs text-tertiary">No transfers yet</p>
            <p className="text-[10px] text-tertiary mt-0.5">Files sent between devices appear here.</p>
          </div>
        ) : (
          transfers.map((t) => (
            <TransferRow
              key={t.id}
              transfer={t}
              onDownload={onDownload}
              onReveal={onReveal}
              onDismiss={onDismiss}
              onAccept={onAccept}
              onReject={onReject}
            />
          ))
        )}
      </div>
    </div>
  );
}

// ─── Types ────────────────────────────────────────────────────────────────────
interface PendingPeer {
  deviceId: string;
  deviceName: string;
  fingerprint: string;
  platform: string;
}

interface TrustedPeer {
  fingerprint: string;
  deviceName: string;
  addedAt: number;
  quickMode: boolean;
}

// ─── Untrusted Peer Banner ────────────────────────────────────────────────────
function UntrustedPeerBanner({
  pending,
  onTrust,
  onIgnore,
}: {
  pending: PendingPeer[];
  onTrust: (peer: PendingPeer, quickMode: boolean) => void;
  onIgnore: (peer: PendingPeer) => void;
}) {
  const [quickModeMap, setQuickModeMap] = useState<Record<string, boolean>>({});

  if (pending.length === 0) return null;

  return (
    <div className="absolute top-12 left-0 right-0 z-40 flex flex-col gap-1.5 px-3 pt-2 pointer-events-none">
      {pending.map((peer) => {
        const qm = quickModeMap[peer.deviceId] ?? false;
        return (
          <div
            key={peer.deviceId}
            className="pointer-events-auto flex items-center gap-3 px-3.5 py-2.5 rounded-xl border border-[rgb(var(--sev-med)/0.45)] bg-[rgb(var(--bg-surface)/0.96)] shadow-lg backdrop-blur-md"
          >
            <div className="flex-1 min-w-0">
              <p className="text-xs font-semibold text-primary truncate">
                New device: <span className="text-accent">{peer.deviceName}</span>
              </p>
              <p className="text-[10px] text-tertiary font-mono">
                {peer.fingerprint.slice(0, 8)}…
              </p>
            </div>
            <label className="flex items-center gap-1.5 text-[10px] text-tertiary whitespace-nowrap cursor-pointer select-none">
              <input
                type="checkbox"
                className="w-3 h-3 accent-amber-400"
                checked={qm}
                onChange={(e) =>
                  setQuickModeMap((prev) => ({ ...prev, [peer.deviceId]: e.target.checked }))
                }
              />
              Quick mode
            </label>
            <button
              onClick={() => onTrust(peer, qm)}
              className="px-2.5 py-1 rounded-lg text-[10px] font-semibold bg-accent text-[rgb(var(--bg-base))] hover:bg-accent/90 transition-colors shrink-0"
            >
              Trust
            </button>
            <button
              onClick={() => onIgnore(peer)}
              className="px-2.5 py-1 rounded-lg text-[10px] font-semibold bg-elevated border border-default text-secondary hover:text-primary transition-colors shrink-0"
            >
              Ignore
            </button>
          </div>
        );
      })}
    </div>
  );
}

// ─── Trusted Devices Section (inside Settings) ────────────────────────────────
function TrustedDevicesSection({ onMutated }: { onMutated: () => void }) {
  const [peers, setPeers] = useState<TrustedPeer[]>([]);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      const list = await invoke<TrustedPeer[]>("get_trusted_peers");
      setPeers(list);
    } catch {
      setPeers([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const handleRemove = async (fingerprint: string) => {
    try {
      await invoke("remove_trusted_peer", { fingerprint });
      onMutated();
      refresh();
    } catch {}
  };

  const handleToggleQuick = async (fingerprint: string, enabled: boolean) => {
    try {
      await invoke("set_quick_mode", { fingerprint, enabled });
      refresh();
    } catch {}
  };

  return (
    <div className="space-y-2">
      <label className="text-xs font-medium text-secondary">Trusted Devices</label>
      {loading ? (
        <p className="text-xs text-tertiary">Loading…</p>
      ) : peers.length === 0 ? (
        <p className="text-[10px] text-tertiary leading-relaxed">
          No trusted devices yet. Scan a QR code to pair.
        </p>
      ) : (
        <div className="space-y-1.5">
          {peers.map((peer) => (
            <div
              key={peer.fingerprint}
              className="flex items-center gap-2.5 px-3 py-2.5 rounded-lg border border-default bg-elevated"
            >
              <div className="flex-1 min-w-0">
                <p className="text-xs font-medium text-primary truncate">{peer.deviceName}</p>
                <p className="text-[10px] text-tertiary font-mono">
                  {peer.fingerprint.slice(0, 8)}
                </p>
                <p className="text-[10px] text-tertiary">
                  {new Date(peer.addedAt).toLocaleString()}
                </p>
              </div>
              <label className="flex items-center gap-1.5 text-[10px] text-tertiary whitespace-nowrap cursor-pointer select-none shrink-0">
                <input
                  type="checkbox"
                  className="w-3 h-3 accent-amber-400"
                  checked={peer.quickMode}
                  onChange={(e) => handleToggleQuick(peer.fingerprint, e.target.checked)}
                />
                Quick
              </label>
              <button
                onClick={() => handleRemove(peer.fingerprint)}
                className="w-6 h-6 flex items-center justify-center rounded-md hover:bg-[rgb(var(--sev-critical)/0.12)] text-tertiary hover:text-[rgb(var(--sev-critical))] transition-colors shrink-0"
                title="Remove"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ─── Settings Modal ───────────────────────────────────────────────────────────
function SettingsModal({
  config,
  onClose,
  updateDeviceName,
  updateRelayUrl,
  updateScreenshotFolder,
}: {
  config: any;
  onClose: () => void;
  updateDeviceName: (name: string) => void;
  updateRelayUrl: (url: string) => void;
  updateScreenshotFolder: (folder: string) => void;
}) {
  const [deviceName, setDeviceName] = useState(config?.deviceName ?? "");
  const [relayUrl, setRelayUrl] = useState(config?.relayUrl ?? "");
  const [screenshotFolder, setScreenshotFolder] = useState(config?.screenshotFolder ?? "");
  const [saved, setSaved] = useState(false);

  const handleSave = async () => {
    if (deviceName !== config?.deviceName) updateDeviceName(deviceName);
    if (relayUrl !== config?.relayUrl) updateRelayUrl(relayUrl);
    if (screenshotFolder !== config?.screenshotFolder) updateScreenshotFolder(screenshotFolder);
    setSaved(true);
    setTimeout(() => { setSaved(false); onClose(); }, 900);
  };

  const pickFolder = async () => {
    try {
      const folder = await invoke<string | null>("open_folder_dialog");
      if (folder) setScreenshotFolder(folder);
    } catch {}
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="w-[420px] bg-[rgb(var(--bg-surface))] border border-default rounded-2xl shadow-2xl p-6 flex flex-col gap-5">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-primary">Settings</h2>
          <button onClick={onClose} className="w-7 h-7 rounded-md flex items-center justify-center hover:bg-elevated text-tertiary transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="space-y-4">
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-secondary">Device Name</label>
            <input
              className="w-full bg-elevated border border-default rounded-lg px-3 py-2 text-sm text-primary placeholder:text-tertiary outline-none focus:border-accent/60 transition-colors"
              value={deviceName}
              onChange={(e) => setDeviceName(e.target.value)}
            />
          </div>

          <div className="space-y-1.5">
            <label className="text-xs font-medium text-secondary">Relay Server URL</label>
            <input
              className="w-full bg-elevated border border-default rounded-lg px-3 py-2 text-sm text-primary placeholder:text-tertiary outline-none focus:border-accent/60 font-mono transition-colors"
              value={relayUrl}
              onChange={(e) => setRelayUrl(e.target.value)}
              placeholder="wss://your-relay.example.com/ws"
            />
          </div>

          <TrustedDevicesSection onMutated={() => {}} />

          <div className="space-y-1.5">
            <label className="text-xs font-medium text-secondary">Screenshot Watch Folder</label>
            <div className="flex gap-2">
              <input
                className="flex-1 min-w-0 bg-elevated border border-default rounded-lg px-3 py-2 text-sm text-primary placeholder:text-tertiary outline-none focus:border-accent/60 font-mono transition-colors"
                value={screenshotFolder}
                onChange={(e) => setScreenshotFolder(e.target.value)}
                placeholder="C:\Users\…\Pictures\Screenshots"
              />
              <button
                onClick={pickFolder}
                className="px-3 py-2 rounded-lg bg-elevated border border-default text-xs text-secondary hover:text-primary hover:border-accent/40 transition-colors flex items-center gap-1.5 shrink-0"
              >
                <FolderOpen className="w-3.5 h-3.5" />
                Browse
              </button>
            </div>
            <p className="text-[10px] text-tertiary">Restart app to apply folder changes.</p>
          </div>

          {config?.deviceId && (
            <div className="space-y-1">
              <label className="text-xs font-medium text-secondary">Device ID</label>
              <p className="text-xs text-tertiary font-mono break-all">{config.deviceId}</p>
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 pt-1 border-t border-default">
          <button
            onClick={onClose}
            className="px-3 py-2 rounded-lg text-sm text-secondary hover:text-primary hover:bg-elevated transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={saved}
            className={cn(
              "px-4 py-2 rounded-lg text-sm font-semibold transition-all flex items-center gap-2",
              saved
                ? "bg-[rgb(var(--sev-low)/0.2)] text-[rgb(var(--sev-low))] border border-[rgb(var(--sev-low)/0.3)]"
                : "bg-accent text-[rgb(var(--bg-base))] hover:bg-accent/90"
            )}
          >
            {saved ? <><Check className="w-3.5 h-3.5" /> Saved</> : "Save Changes"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ─── App Shell ────────────────────────────────────────────────────────────────
export default function App() {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [pendingTrust, setPendingTrust] = useState<PendingPeer[]>([]);
  const { entries, copyEntry, clearEntries } = useClipboard();
  const { room, setRoom } = useRoomState();
  const { config, updateDeviceName, updateRelayUrl, updateScreenshotFolder } = useConfig();
  const { transfers, downloadTransfer, startUpload, updateTransferById, clearTransfers, dismissTransfer, acceptOffer, rejectOffer } = useFileTransfers();
  const configRef = useRef(config);
  useEffect(() => { configRef.current = config; }, [config]);

  // Subscribe to untrusted peer events
  useEffect(() => {
    const unlistenPromise = listen<PendingPeer>("relay:untrusted_peer", (e) => {
      setPendingTrust((prev) => {
        // Avoid duplicates if the event fires multiple times for the same device
        if (prev.some((p) => p.deviceId === e.payload.deviceId)) return prev;
        return [...prev, e.payload];
      });
    });
    return () => { unlistenPromise.then((unlisten) => unlisten()); };
  }, []);

  const handleTrustPeer = useCallback(async (peer: PendingPeer, quickMode: boolean) => {
    try {
      await invoke("add_trusted_peer", {
        fingerprint: peer.fingerprint,
        deviceName: peer.deviceName,
        quickMode,
      });
    } catch (err) {
      console.error("[handleTrustPeer] failed:", err);
    }
    setPendingTrust((prev) => prev.filter((p) => p.deviceId !== peer.deviceId));
  }, []);

  const handleIgnorePeer = useCallback((peer: PendingPeer) => {
    setPendingTrust((prev) => prev.filter((p) => p.deviceId !== peer.deviceId));
  }, []);

  const handleConnect = useCallback((roomId: string) => {
    setRoom((prev) => ({ ...prev, roomId, connected: true }));
  }, [setRoom]);

  const handleLeave = useCallback(async () => {
    try { await invoke("leave_room_cmd"); } catch {}
    setRoom({ roomId: null, connected: false, devices: [] });
  }, [setRoom]);

  // Overlay reports back with overlay:file_uploaded after successful send to device picker
  useEffect(() => {
    const unlistenPromise = listen<{ name: string; downloadUrl: string }>("overlay:file_uploaded", (e) => {
      const id = startUpload(e.payload.name);
      updateTransferById(id, { status: "done", downloadUrl: e.payload.downloadUrl });
    });
    return () => { unlistenPromise.then((unlisten) => unlisten()); };
  }, [startUpload, updateTransferById]);

  const handleBrowse = useCallback(async () => {
    let path: string | null = null;
    try {
      path = await invoke<string | null>("open_file_dialog");
    } catch (err) {
      console.error("File dialog failed:", err);
      return;
    }
    if (!path) return; // user cancelled

    const name = path.split(/[\\/]/).pop() ?? "file";
    const id = startUpload(name);
    try {
      const downloadUrl = await invoke<string>("upload_file", { path });
      updateTransferById(id, { status: "done", downloadUrl });
    } catch (err) {
      const msg = typeof err === "string" ? err : (err as any)?.message ?? "Upload failed";
      updateTransferById(id, { status: "error", errorMsg: msg });
    }
  }, [startUpload, updateTransferById]);

  const handleReveal = useCallback(async (path: string) => {
    try { await invoke("reveal_in_explorer", { path }); } catch {}
  }, []);

  return (
    <div className="relative flex flex-col h-screen bg-[rgb(var(--bg-base))] text-[rgb(var(--text-primary))] overflow-hidden">
      <UntrustedPeerBanner
        pending={pendingTrust}
        onTrust={handleTrustPeer}
        onIgnore={handleIgnorePeer}
      />
      <TopBar
        connected={room.connected}
        roomId={room.roomId}
        onOpenSettings={() => setSettingsOpen(true)}
      />

      <div className="flex flex-1 overflow-hidden">
        <LeftPanel
          connected={room.connected}
          roomId={room.roomId}
          devices={room.devices}
          config={config}
          onLeave={handleLeave}
          onConnect={handleConnect}
        />

        <ClipboardPanel entries={entries} onCopy={copyEntry} onClear={clearEntries} />

        <RightPanel
          transfers={transfers}
          onDownload={downloadTransfer}
          onReveal={handleReveal}
          onBrowse={handleBrowse}
          onClear={clearTransfers}
          onDismiss={dismissTransfer}
          onAccept={acceptOffer}
          onReject={rejectOffer}
        />
      </div>

      {settingsOpen && config && (
        <SettingsModal
          config={config}
          onClose={() => setSettingsOpen(false)}
          updateDeviceName={updateDeviceName}
          updateRelayUrl={updateRelayUrl}
          updateScreenshotFolder={updateScreenshotFolder}
        />
      )}
    </div>
  );
}
