<div align="center">

# ⚡ LiveC

### Your clipboard, your files, your devices.
### No cloud. No accounts. No spying.

[![License: MIT](https://img.shields.io/badge/license-MIT-FBBF24.svg?style=for-the-badge)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Android-34D399?style=for-the-badge)]()
[![Status](https://img.shields.io/badge/status-public%20alpha-F87171?style=for-the-badge)]()
[![Self--Hosted](https://img.shields.io/badge/self--hosted-✓-3B82F6?style=for-the-badge)]()

**Copy on your PC. Paste on your phone. Drag a 10 GB video.
It just works — and nothing ever touches anyone's cloud but yours.**

</div>

---

## 🎬 What is this?

LiveC is a **self-hosted clipboard and file sync** between your Windows PC and your Android phone.

Think:
- Apple's **Universal Clipboard**, but for Windows ↔ Android.
- **LocalSend**, but it works when you're not on the same WiFi.
- **Pushbullet** or **Blip**, but you run the server. Nobody else's cloud. Nobody else's account. Nobody else's terms of service.

Copy a link on your PC → it shows up in your phone's clipboard.
Drag a 10 GB movie onto the drop shelf → it lands on your phone with a confirmation.
Take a screenshot → it's already on your phone's gallery before you've thought about it.

That's it. That's the product.

---

## ✨ Why you'll like it

<table>
<tr>
<td width="33%" valign="top">

### 🚀 Stupid fast on WiFi
Same network? LAN-direct over mDNS. No middleman, no relay hop, no waiting.

</td>
<td width="33%" valign="top">

### 🌐 Doesn't fall over off WiFi
Phone on 5G? Your self-hosted relay picks up the slack. Same room, same files, no thinking about it.

</td>
<td width="33%" valign="top">

### 🔐 You own the keys
Per-device Ed25519 identity. Private keys live in **Windows Credential Manager** and **Android Keystore** — never on disk, never on a server.

</td>
</tr>
<tr>
<td width="33%" valign="top">

### 📦 Files up to 100 MB
Streaming 1 MB chunks. Survives Cloudflare timeouts. Survives your WiFi dropping mid-send. Pick it back up where it left off. *(Limits are sized for free-tier hosting — bump the constants if you self-host on bigger metal.)*

</td>
<td width="33%" valign="top">

### 🤝 Trust, the way it should be
Scan one QR. That peer is now trusted forever. Future files auto-accept. Untrusted devices that wander into your room? They wait for your tap.

</td>
<td width="33%" valign="top">

### 🪶 Self-host in 60 seconds
One Docker command. One env var. That's the entire server. No database, no admin panel, no Stripe integration, no terms of service.

</td>
</tr>
</table>

---

## 🧠 How it works (in one picture)

```
       ┌─────────────────┐                                              ┌─────────────────┐
       │   🖥️  Windows    │ ◄──── ws://192.168.x.x:7777  (LAN, fast) ──► │   📱 Android    │
       │     (Tauri)     │                                              │   (Compose)     │
       │                 │ ◄──── wss://your-relay (offline fallback) ──►│                 │
       └─────────────────┘                                              └─────────────────┘
                                          ▲
                                          │
                            ┌─────────────────────────────┐
                            │   🌐 Your self-hosted relay │
                            │   (one Docker container)     │
                            └─────────────────────────────┘
```

Both transports run **in parallel**. First one to arrive wins; the duplicate is silently dropped. LAN gives you instant. Relay gives you reachable. You don't think about either.

Files use a two-phase **offer → accept → chunked upload** protocol. Nothing hits the relay's disk until the recipient says yes. Done downloading? The file's deleted immediately. Forgot about it for a week? It expires on its own.

---

## 🚦 Get started

### 1. Spin up your relay

```bash
git clone https://github.com/AditRaj-dev/LiveC.git
cd LiveC
cp .env.example .env
# edit .env — set LIVEC_SIGNING_SECRET to something random:
#   openssl rand -hex 32
docker compose up -d
```

That's the entire server. It's listening on `:3000`. Point a Cloudflare tunnel or Caddy at it if you want to reach it from outside your network.

**Or deploy free on Render:**
1. Push this repo to your own GitHub.
2. Render Dashboard → **New +** → **Web Service** → connect the repo.
3. Root Directory: `relay`. Runtime: **Docker**. Plan: **Free**.
4. Environment Variables: add `LIVEC_SIGNING_SECRET` = output of `openssl rand -hex 32`.
5. Deploy. Use `wss://<service-name>.onrender.com/ws` as the relay URL in the apps.

Free-tier caveats baked into the defaults: 100 MB file cap, 1 h TTLs (the container's disk evaporates on restart), and the service sleeps after 15 min of no traffic. WebSocket reconnects automatically when traffic resumes; in-flight uploads survive via HEAD-based resume.

### 2. Install the apps

**Windows:**
```bash
cd desktop && npm install && npm run tauri build
# launches at: desktop/src-tauri/target/release/desktop.exe
```

**Android:**
```bash
cd android && ./gradlew assembleRelease
# APK at: android/app/build/outputs/apk/release/
```

### 3. Pair once. Forget forever.

Open the desktop app. There's a QR code in the left panel. Scan it with the phone. Done.

From now on, copy on one device, paste on the other. Drag files. Send screenshots. It just works.

---

## 🆚 How LiveC compares

| | **LiveC** | LocalSend | Apple Universal Clipboard | Pushbullet | Blip |
|---|:---:|:---:|:---:|:---:|:---:|
| Works on LAN | ✅ | ✅ | ✅ | ❌ | ❌ |
| Works over internet | ✅ | ❌ | ❌ (Apple-only) | ✅ | ✅ |
| Self-hosted server | ✅ | N/A | ❌ | ❌ | ❌ |
| No account needed | ✅ | ✅ | iCloud | ❌ | ❌ |
| Files > 100 MB | ✅ (10 GB) | ✅ | ❌ | 25 MB free | ✅ |
| Resumes failed uploads | ✅ | ❌ | ❌ | ❌ | ✅ |
| Clipboard sync | ✅ | ❌ | ✅ | ✅ | ❌ |
| QR pairing | ✅ | ✅ | ❌ | ❌ | ❌ |
| Windows + Android | ✅ | ✅ | ❌ | ✅ | ✅ |

---

## 🗺️ Roadmap

LiveC is in **public alpha**. The wire protocol is stable enough that we're inviting people to use it, but expect rough edges.

**Shipping next:**
- 🔐 End-to-end encryption (today the relay sees plaintext — your relay, your problem, but we're closing this gap)
- 🌐 Web client — receive files without installing anything
- 🍎 macOS + Linux desktop builds
- 📱 iOS client
- ✍️ Signed messages (the Ed25519 keys are there, we're not signing with them yet)

**On the wishlist:**
- Per-recipient signed download URLs
- Hosted-relay tier (so you don't have to)
- Device groups, multi-room
- Translations

Want to help? PRs welcome. The codebase is small (Rust + Kotlin + Node + React) and the architecture is documented in the source.

---

## 🤔 FAQ

**Why not just use [X]?**
Because [X] either has an account, runs in someone else's cloud, doesn't sync clipboard, doesn't do big files, doesn't work off WiFi, or all of the above. LiveC is what you'd build for yourself in a weekend if you had a weekend.

**Is this secure?**
The wire is TLS to your relay. The download URLs are HMAC-signed. The private keys live in OS keychains. But **the relay sees your file bytes and clipboard text in plaintext** — that's the next thing on the list to fix. Run the relay yourself. Don't put it on the public internet without knowing what you're doing.

**Does it work over cellular?**
Yes, through your relay. Make sure the relay is reachable from the internet (Cloudflare Tunnel is the easiest way — free for personal use).

**Will it eat my phone battery?**
No. The Android service uses a WebSocket with a 25-second heartbeat. Single-digit milliwatts. You won't notice it.

**Why "LiveC"?**
Because everything good was taken.

---

## 📜 License

[MIT](LICENSE) — do what you want with it.

---

<div align="center">

**Built because nobody else was building it.**

⭐ Star the repo if this is the thing you've been waiting for ⭐

</div>
