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
}

export interface RoomState {
  roomId: string | null;
  connected: boolean;
  devices: Device[];
}

export type TransferStatus = "pending" | "uploading" | "downloading" | "done" | "error";

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
}
