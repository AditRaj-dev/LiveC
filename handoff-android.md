# LiveC — Handoff #2 (Android scaffold + protocol SoT)

Picks up from `handoff.md`. Read that first for the desktop/relay state.

## What this session added

### Protocol source of truth
- **`D:\LiveC\PROTOCOL.md`** — single doc all clients mirror. Lists HTTP endpoints, WS message types, envelope shape, QR payload format, limits.
- **`relay/src/protocol.js`** — Node mirror. Exports `PATHS`, `MESSAGE_TYPES`, `BROADCAST`, `LIMITS`, `UPLOAD_FIELD_NAME`. **Not yet wired** into `server.js` / `file-store.js` / `room-manager.js` — they still hardcode strings. Next session should swap them.
- **`desktop/src/protocol.ts`** — TS mirror with the same shape + helpers `relayToHttpBase()` and `downloadUrl()`. **Not yet wired** into the React code — needs to replace inline URL munging in `App.tsx` and `useLiveC.ts`.
- **`android/app/src/main/kotlin/com/livec/app/data/Protocol.kt`** — Kotlin mirror, wired throughout the Android code already.

When you change any message type or path, update all four files in lockstep.

### Android project (skeleton — compiles and runs as a foreground service, no UI yet)

Created at `D:\LiveC\android\`:

```
android/
├── settings.gradle.kts             ← rootProject.name = "LiveC", includes :app
├── build.gradle.kts                ← root, references libs.versions.toml
├── gradle.properties
├── gradle/libs.versions.toml       ← version catalog (AGP 8.5.2, Kotlin 2.0.20, Compose BOM 2024.09.02)
├── app/
│   ├── build.gradle.kts            ← minSdk 26, targetSdk 34, Compose, OkHttp, ML Kit, CameraX
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml     ← INTERNET, FOREGROUND_SERVICE_DATA_SYNC, CAMERA, POST_NOTIFICATIONS, share intent filter
│       ├── res/
│       │   ├── values/strings.xml
│       │   ├── values/themes.xml   ← Theme.LiveC (status bar = bg-base)
│       │   └── xml/data_extraction_rules.xml
│       └── kotlin/com/livec/app/
│           ├── LiveCApplication.kt  ← ensures device_id, starts foreground service
│           ├── data/
│           │   ├── Protocol.kt      ← constants + relayToHttpBase()
│           │   ├── Message.kt       ← JSON envelope, parse(), factories (deviceJoin/clipboardText/ping/…)
│           │   ├── ConfigStore.kt   ← DataStore preferences (device_id, name, room_token, relay_url)
│           │   └── AppState.kt      ← process-wide singleton with StateFlows (connected, devices, clips, transfers)
│           ├── network/
│           │   └── RelayClient.kt   ← OkHttp WebSocket, exponential reconnect 1s→30s, dedup ring, heartbeat
│           ├── service/
│           │   └── LiveCService.kt  ← LifecycleService, owns RelayClient + clipboard listener, posts notifications
│           └── ui/
│               ├── AppViewModel.kt  ← exposes AppState + pairing/leave/download intents
│               └── theme/
│                   ├── Color.kt     ← LiveCColors (zinc + amber palette mirroring desktop)
│                   └── Theme.kt     ← Material3 darkColorScheme + shapes + typography
```

### What works in the scaffold
- Foreground service starts on app launch.
- ConfigStore generates a UUID `device_id` on first run.
- When `relayUrl` and `roomToken` are set, `RelayClient` opens a WS, sends `device_join`, handles incoming `clipboard_text`, `clipboard_image`, `file_meta`, `device_join`, `device_leave`, `file_expired`. Echos local clipboard changes back to the relay with the `SELF_WRITE_PENDING` guard (same pattern as Windows).
- Inbound clipboard text is written to the Android clipboard.
- Inbound `file_meta` posts a notification + appears in `AppState.transfers`.
- `AppViewModel.downloadTransfer()` enqueues via `DownloadManager` to `Environment.DIRECTORY_DOWNLOADS`.

### What is NOT in the scaffold yet
- **MainActivity.kt** — needs writing (NavHost with home / pairing / settings routes, share-intent handling).
- **UI screens** — `HomeScreen.kt`, `PairingScreen.kt`, `SettingsScreen.kt`.
- App icon (no `mipmap` resources). Use `Image Asset Studio` in Android Studio or drop a `mipmap-anydpi-v26/ic_launcher.xml`.
- Theme.kt has a `dpUnit()` private helper — replace with `import androidx.compose.ui.unit.dp` and use `6.dp` etc. Minor cleanup, but it compiles as-is.

## Implementation plan for next session

Order matters. Each step is verifiable before moving on.

### Step 1 — Wire the protocol mirrors (30 min)

Currently the mirror files exist but the legacy code still uses hardcoded strings. Replace:

**relay/src/server.js**
```js
const { PATHS, MESSAGE_TYPES, BROADCAST } = require('./protocol');
// app.post(PATHS.UPLOAD, …)
// app.get(`${PATHS.DOWNLOAD}/:fileId`, …)
// new WebSocketServer({ server, path: PATHS.WS })
// if (messageObj.type === MESSAGE_TYPES.DEVICE_JOIN) …
```

**relay/src/file-store.js**
```js
const { LIMITS, UPLOAD_FIELD_NAME } = require('./protocol');
// limits: { fileSize: LIMITS.MAX_FILE_BYTES }
// upload.single(UPLOAD_FIELD_NAME)
// FILE_TTL_MS → LIMITS.FILE_TTL_MS
```

**relay/src/room-manager.js**, **message-router.js**, **offline-queue.js**
- Replace string literals (`'broadcast'`, `'device_join'`, etc.) with `MESSAGE_TYPES.*` / `BROADCAST` / `LIMITS.*`.

**desktop/src-tauri/src/protocol.rs**
- Add a Rust mirror module (constants for HTTP paths, message types, broadcast). The existing `protocol.rs` only has `BROADCAST` and `Message` — extend it.

**desktop/src/App.tsx + hooks/useLiveC.ts**
- Replace inline `.replace("wss://", "https://")` with `relayToHttpBase()` from `protocol.ts`.
- Replace `"/download/"` literals with `downloadUrl(httpBase, fileId)`.
- Replace event-type string checks with `MESSAGE_TYPES.CLIPBOARD_TEXT` etc.

**desktop/src-tauri/src/lib.rs + connection.rs**
- Replace `"clipboard_image"`, `"file_meta"` literals with constants from the extended `protocol.rs`.

Verify: `npm start` (relay), `cargo build` (desktop), `npx tsc --noEmit` (desktop). Should all be clean.

### Step 2 — Android MainActivity + NavHost (1 hr)

```kotlin
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Handle share-sheet intent (ACTION_SEND with text/plain or image/*)
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val sharedUri = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

        setContent {
            LiveCTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") { HomeScreen(vm, onOpenSettings = { nav.navigate("settings") }, onPair = { nav.navigate("pair") }) }
                    composable("pair") { PairingScreen(vm, onDone = { nav.popBackStack() }) }
                    composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}
```

Request `POST_NOTIFICATIONS` permission at first launch (Android 13+).
Request `CAMERA` permission inside `PairingScreen` only.

### Step 3 — HomeScreen (1.5 hr)

Layout: single column (mobile, no 3-pane). TopAppBar with title + connection chip + settings gear. Body: tabbed pages "Clipboard" and "Files" (or vertical sections — easier).

Components:
- `ConnectionChip` — green dot + "Connected" or grey + "Disconnected"
- `RoomBanner` — when `config.roomToken.isEmpty()`, big "Scan QR" CTA → navigate to `pair`. When paired, show `#${roomToken.take(8)}` + Leave button.
- `DeviceRow` — icon (Smartphone/Monitor), name, last-seen ago
- `ClipCard` — like the Windows version, kind chip + content preview + Copy button + remote badge
- `TransferCard` — file icon, name, size, status, "Save" / "Open folder" actions

Use `LazyColumn` for the lists. Pull-to-refresh isn't needed; everything is StateFlow-driven.

Copy-to-clipboard: `clipboard.setPrimaryClip(ClipData.newPlainText("LiveC", text))`. The service's `SELF_WRITE_PENDING` is per-Service-instance, so an in-process write from the activity won't trip it. If you want symmetric guarding, route copies through the service via a broadcast or move clipboard ownership entirely into the service.

### Step 4 — PairingScreen (1.5 hr)

The QR contains the JSON `{ "relayUrl": "...", "roomToken": "..." }` (already emitted by the Windows app).

Flow:
1. Request `CAMERA` permission with `accompanist-permissions`.
2. CameraX `Preview` + `ImageAnalysis` → ML Kit `BarcodeScanning`.
3. On first detected barcode, pause analysis, call `vm.pairFromQr(rawValue)`.
4. If `Result.isSuccess`, `onDone()`.

Skeleton:
```kotlin
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PairingScreen(vm: AppViewModel, onDone: () -> Unit) {
    val perm = rememberPermissionState(Manifest.permission.CAMERA)
    LaunchedEffect(Unit) { if (!perm.status.isGranted) perm.launchPermissionRequest() }
    if (!perm.status.isGranted) {
        // show "Camera permission required" UI
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val scanner = remember { BarcodeScanning.getClient() }
    var locked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { proxy ->
            if (locked) { proxy.close(); return@setAnalyzer }
            val mediaImage = proxy.image
            if (mediaImage != null) {
                val img = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                scanner.process(img)
                    .addOnSuccessListener { codes ->
                        codes.firstOrNull()?.rawValue?.let { value ->
                            locked = true
                            vm.pairFromQr(value).onSuccess { onDone() }
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            } else proxy.close()
        }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }

    AndroidView({ previewView }, modifier = Modifier.fillMaxSize())
}
```

Need `kotlinx-coroutines-play-services` for `.await()` on `ListenableFuture`, OR wrap with `addListener`. Adding `await()` extension manually is also fine.

### Step 5 — SettingsScreen (45 min)

Simple form:
- TextField for device name (defaults to `Build.MODEL`)
- TextField for relay URL (read-only display from config, with "Re-pair" button → navigate to `pair`)
- Device ID (read-only, mono font, copy-to-clipboard on tap)
- "Leave room" button — calls `vm.leaveRoom()`
- "Clear transfer history" — calls `vm.clearTransfers()`

### Step 6 — Share-sheet upload (1 hr)

In `MainActivity.onCreate`, inspect the intent:

```kotlin
if (intent.action == Intent.ACTION_SEND) {
    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    when {
        !text.isNullOrEmpty() -> vm.sendSharedText(text)   // → send clipboard_text via service
        uri != null           -> vm.sendSharedFile(uri)    // → upload + file_meta
    }
}
```

To upload from Android:
```kotlin
suspend fun uploadFile(context: Context, uri: Uri, relayUrl: String, deviceId: String, roomToken: String): String {
    val httpBase = relayToHttpBase(relayUrl)
    val cr = context.contentResolver
    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read uri")
    val name = queryDisplayName(cr, uri) ?: "file"
    val mime = cr.getType(uri) ?: "application/octet-stream"

    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart(UPLOAD_FIELD_NAME, name,
            bytes.toRequestBody(mime.toMediaType()))
        .addFormDataPart("roomToken", roomToken)
        .addFormDataPart("deviceId", deviceId)
        .build()

    val req = Request.Builder().url("$httpBase${Paths.UPLOAD}").post(body).build()
    val res = OkHttpClient().newCall(req).execute()
    if (!res.isSuccessful) error("HTTP ${res.code}")
    val json = JSONObject(res.body!!.string())
    return json.getString("fileId")
}
```

Then `client.send(Message(...file_meta...))`. Reach the `RelayClient` from the ViewModel by binding to the service, OR move the upload helper into `LiveCService` and trigger via Intent extras / a function the Service exposes.

Cleanest: add a `start()` companion overload to `LiveCService` that accepts an extra `EXTRA_SHARE_URI` / `EXTRA_SHARE_TEXT` and handles it in `onStartCommand`.

### Step 7 — Theme.kt cleanup (5 min)

Replace the `dpUnit()` helper with:
```kotlin
import androidx.compose.ui.unit.dp
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
```

### Step 8 — App icon

Either generate via Android Studio (Image Asset Studio) or drop `mipmap-anydpi-v26/ic_launcher.xml` with a vector adaptive icon. Match the Windows app's amber square + monitor glyph.

### Step 9 — Verification

```
cd D:\LiveC\android
./gradlew :app:assembleDebug
```

Install on a device. Open desktop app, generate QR (LeftPanel shows it when paired). Scan with Android. Should see device appear in Windows app's device list within ~1s. Copy text on Android — should appear in Windows clipboard panel. Copy text on Windows — should be applied to Android clipboard.

## Remaining Windows-side work (carried over from handoff.md)

Still untouched:
1. **Drop shelf device picker UI** (`OverlayApp.tsx`) — Rust side already has `target: Option<String>` on `upload_file` (the user/linter added it this session). What's left: the React `'picking'` state + animated device rows + Escape handler + `overlay:file_uploaded` event back to the main window. See handoff.md "Implementation plan" section for the full design.
2. **Screenshot toast device picker** — same pattern.
3. **File TTL `file_expired` notification** — relay-side + frontend "Resend?" button.

## Build verification commands

```
# Relay
cd D:\LiveC\relay && node src/server.js

# Desktop
cd D:\LiveC\desktop
cargo build --manifest-path src-tauri/Cargo.toml
npx tsc --noEmit

# Android (after Step 1–8 are done)
cd D:\LiveC\android
./gradlew :app:assembleDebug
```

## Gotchas to remember on the Android side

- **Background clipboard read on Android 10+** is restricted. `OnPrimaryClipChangedListener` in a foreground service receives the callback, but reading `primaryClip.getItemAt(0)` may return empty on API 29+ when the source app isn't focused. For V1 the foreground notification keeps reads working in most cases. V2 = optional `AccessibilityService` toggle.
- **Writes to clipboard from background** work fine on all supported APIs.
- **Foreground service type** is `dataSync` (Android 14+ requirement) — already in the manifest.
- **`POST_NOTIFICATIONS`** must be requested at runtime on API 33+. Not yet done — add to `MainActivity.onCreate`.
- **DownloadManager** does the file save. Don't roll your own HTTP for downloads on Android — DownloadManager handles retries, network changes, notifications.
- **Self-write guard** in `LiveCService.kt`: `selfWritePending` set right before `clipboard.setPrimaryClip(...)`, cleared on the very next `OnPrimaryClipChanged` callback. Same pattern as Windows; same fragility (don't reset elsewhere).

## User preferences

- Caveman mode in chat — fragments OK. Code/docs: normal English.
- Wants Windows app done before adding mDNS / LAN routing. (Step-by-step product completion.)
- Surface failures in UI; never `console.error` silently.
- Don't over-engineer. Plain JSON > kotlinx.serialization for the message envelope. Plain DataStore > Room for config.
