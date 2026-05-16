#!/usr/bin/env node
/**
 * LiveC fake device emulator.
 * Connects to the relay as a second device so you can test the device picker.
 *
 * Usage:
 *   node fake-device.js                        # auto-reads config, uses defaults
 *   node fake-device.js --name "Pixel 9 Pro" --platform android
 *   node fake-device.js --room <token> --relay ws://localhost:3000/ws
 *
 * Flags:
 *   --name     <string>   device display name  (default: "Fake Android")
 *   --platform <string>   android | windows    (default: android)
 *   --room     <string>   room token           (auto-read from config if omitted)
 *   --relay    <string>   relay WS URL         (auto-read from config if omitted)
 */

const WebSocket = require('ws');
const fs        = require('fs');
const path      = require('path');
const os        = require('os');
const { randomUUID } = require('crypto');

// ── CLI args ──────────────────────────────────────────────────────────────────
const args = process.argv.slice(2);
const get  = (flag) => { const i = args.indexOf(flag); return i !== -1 ? args[i + 1] : null; };

const DEVICE_NAME = get('--name')     ?? 'Fake Android';
const PLATFORM    = get('--platform') ?? 'android';
const DEVICE_ID   = randomUUID();

// ── Auto-read LiveC config ───────────────────────────────────────────────────
function readLiveCConfig() {
  const configPath = path.join(
    os.homedir(), 'AppData', 'Roaming',
    'com.livec.desktop', 'livec_config.json'
  );
  try {
    return JSON.parse(fs.readFileSync(configPath, 'utf8'));
  } catch {
    return null;
  }
}

const livecConfig = readLiveCConfig();

const ROOM_TOKEN = get('--room')  ?? livecConfig?.roomToken  ?? null;
const RELAY_URL  = get('--relay') ?? livecConfig?.relayUrl   ?? 'ws://localhost:3000/ws';

if (!ROOM_TOKEN) {
  console.error('\x1b[31m✗ Could not find room token.\x1b[0m');
  console.error('  Either run the LiveC desktop app first, or pass --room <token>');
  process.exit(1);
}

// ── Pretty print ──────────────────────────────────────────────────────────────
const C = {
  reset:  '\x1b[0m',
  dim:    '\x1b[2m',
  bold:   '\x1b[1m',
  yellow: '\x1b[33m',
  green:  '\x1b[32m',
  cyan:   '\x1b[36m',
  red:    '\x1b[31m',
  gray:   '\x1b[90m',
};

function ts() {
  return C.gray + new Date().toLocaleTimeString() + C.reset;
}

function log(icon, color, msg) {
  console.log(`${ts()} ${color}${icon}${C.reset}  ${msg}`);
}

// ── Connect ───────────────────────────────────────────────────────────────────
console.log('');
console.log(`${C.bold}LiveC Fake Device${C.reset}`);
console.log(`${C.dim}─────────────────────────────────────────────${C.reset}`);
console.log(`  name     : ${C.cyan}${DEVICE_NAME}${C.reset}`);
console.log(`  platform : ${C.cyan}${PLATFORM}${C.reset}`);
console.log(`  device id: ${C.gray}${DEVICE_ID}${C.reset}`);
console.log(`  room     : ${C.yellow}${ROOM_TOKEN}${C.reset}`);
console.log(`  relay    : ${C.gray}${RELAY_URL}${C.reset}`);
console.log(`${C.dim}─────────────────────────────────────────────${C.reset}`);
console.log('');

let ws;
let pingInterval;

function send(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

function makeMsg(type, payload = {}) {
  return {
    type,
    id:      randomUUID(),
    from:    DEVICE_ID,
    to:      'broadcast',
    room:    ROOM_TOKEN,
    payload,
  };
}

function connect() {
  ws = new WebSocket(RELAY_URL);

  ws.on('open', () => {
    log('✓', C.green, `Connected to relay`);

    // Send device_join
    send(makeMsg('device_join', {
      deviceId:   DEVICE_ID,
      deviceName: DEVICE_NAME,
      platform:   PLATFORM,
      roomToken:  ROOM_TOKEN,
    }));

    log('→', C.yellow, `Joined room ${C.bold}${ROOM_TOKEN}${C.reset} as ${C.cyan}${DEVICE_NAME}${C.reset}`);
    console.log('');
    console.log(`${C.dim}  Waiting for messages... (Ctrl+C to disconnect)${C.reset}`);
    console.log('');

    // Heartbeat every 25s
    pingInterval = setInterval(() => {
      send(makeMsg('ping'));
    }, 25000);
  });

  ws.on('message', (data) => {
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return; }

    // Skip our own messages reflected back and heartbeats
    if (msg.from === DEVICE_ID) return;
    if (msg.type === 'pong' || msg.type === 'ping') return;

    switch (msg.type) {
      case 'clipboard_text':
        log('📋', C.cyan, `Clipboard text from ${C.bold}${msg.from.slice(0, 8)}${C.reset}: "${(msg.payload.text ?? '').slice(0, 80)}"`);
        break;

      case 'clipboard_image':
        log('🖼 ', C.cyan, `Clipboard image from ${C.bold}${msg.from.slice(0, 8)}${C.reset}: ${msg.payload.downloadUrl ?? ''}`);
        break;

      case 'file_meta': {
        const { name, size, downloadUrl } = msg.payload;
        const kb = size ? ` (${(size / 1024).toFixed(1)} KB)` : '';
        log('📦', C.yellow, `File from ${C.bold}${msg.from.slice(0, 8)}${C.reset}: ${C.bold}${name}${C.reset}${kb}`);
        log(' ', C.gray, `  ↳ ${downloadUrl}`);
        break;
      }

      case 'device_join':
        log('＋', C.green, `Device joined: ${C.bold}${msg.payload.deviceName ?? msg.from}${C.reset} (${msg.payload.platform ?? '?'})`);
        break;

      case 'device_leave':
        log('−', C.red, `Device left: ${C.bold}${msg.from.slice(0, 8)}${C.reset}`);
        break;

      default:
        log('?', C.gray, `${msg.type} from ${msg.from.slice(0, 8)}: ${JSON.stringify(msg.payload).slice(0, 120)}`);
    }
  });

  ws.on('close', (code) => {
    clearInterval(pingInterval);
    log('✗', C.red, `Disconnected (${code}). Reconnecting in 3s…`);
    setTimeout(connect, 3000);
  });

  ws.on('error', (err) => {
    log('✗', C.red, `WebSocket error: ${err.message}`);
  });
}

// ── Graceful shutdown ─────────────────────────────────────────────────────────
function shutdown() {
  console.log('');
  log('←', C.yellow, 'Sending device_leave…');
  send(makeMsg('device_leave', { deviceId: DEVICE_ID }));
  clearInterval(pingInterval);
  setTimeout(() => {
    ws && ws.close();
    process.exit(0);
  }, 200);
}

process.on('SIGINT',  shutdown);
process.on('SIGTERM', shutdown);

connect();
