export type ClipKind = "text" | "image";

export interface ClipEntry {
  id: string;
  kind: ClipKind;
  text?: string;
  sizeBytes?: number;
  timestamp: number;
  source: "local" | "remote";
}

export interface Device {
  id: string;
  label: string;
  platform: "windows" | "android" | "unknown";
  lastSeen: number;
  /** Hex SHA-256-based device fingerprint (Phase 5). Empty for v0 peers. */
  fingerprint?: string;
}

export interface RoomState {
  roomId: string | null;
  connected: boolean;
  devices: Device[];
}

export type TransferStatus =
  | "offer_pending"   // incoming offer awaiting accept/reject
  | "pending"         // file available, user can download
  | "uploading"       // upload in progress (sender side)
  | "downloading"     // download in progress
  | "done"            // complete
  | "error"
  | "rejected";       // offer was rejected

export interface FileTransfer {
  id: string;
  name: string;
  size: number;
  downloadUrl: string;
  from: string;
  timestamp: number;
  status: TransferStatus;
  direction: "incoming" | "outgoing";
  savedPath?: string;
  errorMsg?: string;
  /** For two-phase offers/transfers: the offerId, sender deviceId, and file UUIDs. */
  offerId?: string;
  senderDeviceId?: string;
  fileIds?: string[];
  /** 0–1 progress while uploading or downloading. Undefined when idle. */
  progress?: number;
}
