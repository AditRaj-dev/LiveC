package com.livec.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livec.app.data.ClipItem
import com.livec.app.data.DeviceInfo
import com.livec.app.data.TransferItem
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.livec.app.data.AppConfig
import com.livec.app.ui.AppViewModel
import com.livec.app.ui.theme.LiveCColors
import org.json.JSONObject

private const val TAB_DEVICES   = 0
private const val TAB_CLIPBOARD = 1
private const val TAB_FILES     = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenSettings: () -> Unit,
    onPair: () -> Unit,
    onSendFile: () -> Unit = {},
) {
    val connected by vm.connected.collectAsStateWithLifecycle()
    val devices   by vm.devices.collectAsStateWithLifecycle()
    val clips     by vm.clips.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val config    by vm.config.collectAsStateWithLifecycle()

    var selectedTab  by remember { mutableIntStateOf(TAB_DEVICES) }
    var showQrSheet  by remember { mutableStateOf(false) }
    val isPaired = config?.roomToken?.isNotEmpty() == true

    if (showQrSheet && isPaired) {
        QrBottomSheet(config = config!!, onDismiss = { showQrSheet = false })
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            LiveCTopBar(
                tab        = selectedTab,
                connected  = connected,
                onQr       = if (isPaired) { { showQrSheet = true } } else onPair,
                onSettings = onOpenSettings,
            )
        },
        bottomBar = {
            LiveCBottomNav(selectedTab = selectedTab, onSelect = { selectedTab = it })
        },
    ) { padding ->
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(110)) },
            label = "tab-content",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { tab ->
            when (tab) {
                TAB_DEVICES -> DevicesTabContent(
                    roomToken = config?.roomToken ?: "",
                    devices   = devices,
                    onPair    = onPair,
                    onShowQr  = { showQrSheet = true },
                    onLeave   = { vm.leaveRoom() },
                )
                TAB_CLIPBOARD -> ClipboardTabContent(clips = clips)
                else -> FilesTabContent(
                    transfers  = transfers,
                    onSendFile = onSendFile,
                    onSave     = { vm.downloadTransfer(it) },
                    onDismiss  = { vm.dismissTransfer(it) },
                    onAccept   = { vm.acceptOffer(it) },
                    onReject   = { vm.rejectOffer(it) },
                )
            }
        }
    }
}

// ── Top bar ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveCTopBar(
    tab: Int,
    connected: Boolean,
    onQr: () -> Unit,
    onSettings: () -> Unit,
) {
    val title = when (tab) {
        TAB_CLIPBOARD -> "Clipboard"
        TAB_FILES     -> "Files"
        else          -> "LiveC"
    }
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogoMark()
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = {
            ConnectionChip(connected)
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onQr) {
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = "QR",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = LiveCColors.BgSurface.copy(alpha = 0.85f),
        ),
    )
}

// ── Bottom nav ───────────────────────────────────────────────────────────────────

@Composable
private fun LiveCBottomNav(selectedTab: Int, onSelect: (Int) -> Unit) {
    Column {
        HorizontalDivider(
            color = LiveCColors.Border.copy(alpha = 0.8f),
            thickness = 1.dp,
        )
        NavigationBar(
            containerColor = LiveCColors.BgBase.copy(alpha = 0.92f),
            tonalElevation = 0.dp,
        ) {
            NavigationBarItem(
                selected = selectedTab == TAB_DEVICES,
                onClick  = { onSelect(TAB_DEVICES) },
                icon     = { Icon(Icons.Default.Devices, contentDescription = "Devices") },
                label    = { Text("Devices") },
                colors   = navItemColors(),
            )
            NavigationBarItem(
                selected = selectedTab == TAB_CLIPBOARD,
                onClick  = { onSelect(TAB_CLIPBOARD) },
                icon     = { Icon(Icons.Default.ContentPaste, contentDescription = "Clipboard") },
                label    = { Text("Clipboard") },
                colors   = navItemColors(),
            )
            NavigationBarItem(
                selected = selectedTab == TAB_FILES,
                onClick  = { onSelect(TAB_FILES) },
                icon     = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = "Files") },
                label    = { Text("Files") },
                colors   = navItemColors(),
            )
        }
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor   = LiveCColors.Accent,
    selectedTextColor   = LiveCColors.Accent,
    indicatorColor      = LiveCColors.Accent.copy(alpha = 0.12f),
    unselectedIconColor = LiveCColors.TextTertiary,
    unselectedTextColor = LiveCColors.TextTertiary,
)

// ── Devices tab ──────────────────────────────────────────────────────────────────

@Composable
private fun DevicesTabContent(
    roomToken: String,
    devices: List<DeviceInfo>,
    onPair: () -> Unit,
    onShowQr: () -> Unit,
    onLeave: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { RoomBanner(roomToken = roomToken, onPair = onPair, onShowQr = onShowQr, onLeave = onLeave) }

        if (devices.isNotEmpty()) {
            item {
                SectionHeader(title = "Devices", trailing = "${devices.size} connected")
            }
            items(devices, key = { it.id }) { DeviceCard(it) }
        } else if (roomToken.isNotEmpty()) {
            item {
                EmptyState(
                    icon  = Icons.Default.Devices,
                    title = "No devices",
                    sub   = "Other devices will appear here when they join the room",
                )
            }
        }
    }
}

// ── Clipboard tab ────────────────────────────────────────────────────────────────

@Composable
private fun ClipboardTabContent(clips: List<ClipItem>) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (clips.isNotEmpty()) {
            item { SectionHeader(title = "Clipboard", trailing = "${clips.size} items") }
            items(clips.take(50), key = { it.id }) { ClipCard(it) }
        } else {
            item {
                EmptyState(
                    icon  = Icons.Default.ContentPaste,
                    title = "Nothing yet",
                    sub   = "Copy something on any paired device",
                )
            }
        }
    }
}

// ── Files tab ────────────────────────────────────────────────────────────────────

@Composable
private fun FilesTabContent(
    transfers: List<TransferItem>,
    onSendFile: () -> Unit,
    onSave: (TransferItem) -> Unit,
    onDismiss: (TransferItem) -> Unit,
    onAccept: (TransferItem) -> Unit,
    onReject: (TransferItem) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { SendFileCta(onClick = onSendFile) }

        if (transfers.isNotEmpty()) {
            item { SectionHeader(title = "Transfers", trailing = "${transfers.size} recent") }
            items(transfers, key = { it.id }) { transfer ->
                SwipeableTransferCard(
                    transfer  = transfer,
                    onSave    = { onSave(transfer) },
                    onDismiss = { onDismiss(transfer) },
                    onAccept  = { onAccept(transfer) },
                    onReject  = { onReject(transfer) },
                )
            }
        } else {
            item {
                EmptyState(
                    icon  = Icons.AutoMirrored.Filled.InsertDriveFile,
                    title = "No transfers",
                    sub   = "Files sent from paired devices appear here",
                )
            }
        }
    }
}

// ── Shared components ────────────────────────────────────────────────────────────

@Composable
private fun LogoMark() {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(LiveCColors.Accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Sync,
            contentDescription = null,
            tint = Color(0xFF0A0A0C),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun ConnectionChip(connected: Boolean) {
    val color = if (connected) LiveCColors.SevLow else LiveCColors.TextTertiary
    val label = if (connected) "Live" else "Offline"

    val pulse = rememberInfiniteTransition(label = "conn-pulse")
    val scale by pulse.animateFloat(
        initialValue  = 1f,
        targetValue   = if (connected) 1.7f else 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.25f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(8.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (connected) 0.35f else 0f))
            )
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontSize = 10.sp)
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LiveCColors.TextTertiary,
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = LiveCColors.TextTertiary.copy(alpha = 0.7f),
            )
        }
    }
}

// ── Room banner ──────────────────────────────────────────────────────────────────

@Composable
private fun RoomBanner(roomToken: String, onPair: () -> Unit, onShowQr: () -> Unit, onLeave: () -> Unit) {
    if (roomToken.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            LiveCColors.Accent.copy(alpha = 0.06f),
                            LiveCColors.BgSurface.copy(alpha = 0.90f),
                        )
                    )
                )
                .border(1.dp, LiveCColors.Accent.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                .padding(vertical = 28.dp, horizontal = 22.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(LiveCColors.Accent.copy(alpha = 0.10f))
                        .border(1.dp, LiveCColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = LiveCColors.Accent,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Text(
                    "Connect to a room",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Scan the QR code in the LiveC Windows app to sync clipboard and files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    Button(
                        onClick = onPair,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LiveCColors.Accent,
                            contentColor   = Color(0xFF0A0A0C),
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR Code", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(LiveCColors.Accent.copy(alpha = 0.06f))
                .border(1.dp, LiveCColors.Accent.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LiveCColors.Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = LiveCColors.Accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Room",
                    style = MaterialTheme.typography.labelSmall,
                    color = LiveCColors.TextTertiary,
                )
                Text(
                    "#${roomToken.take(8)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(LiveCColors.Accent.copy(alpha = 0.12f))
                    .border(1.dp, LiveCColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                    .clickable { onShowQr() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("Share", style = MaterialTheme.typography.labelSmall, color = LiveCColors.Accent)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(LiveCColors.BgElevated.copy(alpha = 0.6f))
                    .border(1.dp, LiveCColors.Border, RoundedCornerShape(8.dp))
                    .clickable { onLeave() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    "Leave",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ── Device card ──────────────────────────────────────────────────────────────────

@Composable
private fun DeviceCard(device: DeviceInfo) {
    val (icon, iconColor) = when (device.platform.lowercase()) {
        "windows" -> Icons.Default.DesktopWindows to Color(0xFF60A5FA)
        "android" -> Icons.Default.PhoneAndroid   to LiveCColors.SevLow
        "macos", "mac" -> Icons.Default.Laptop    to LiveCColors.SevMed
        else -> Icons.Default.Devices             to LiveCColors.TextSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LiveCColors.BgSurface.copy(alpha = 0.7f))
            .border(1.dp, LiveCColors.Border.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconColor.copy(alpha = 0.10f))
                .border(1.dp, iconColor.copy(alpha = 0.18f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    device.platform.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = LiveCColors.TextTertiary,
                )
                MetaDot()
                Text(
                    "active now",
                    style = MaterialTheme.typography.labelSmall,
                    color = LiveCColors.TextTertiary,
                )
            }
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(LiveCColors.SevLow))
    }
}

// ── Clip card ────────────────────────────────────────────────────────────────────

@Composable
private fun ClipCard(clip: ClipItem) {
    val ctx      = LocalContext.current
    val isImage  = clip.kind == ClipItem.Kind.IMAGE
    val isRemote = clip.source == ClipItem.Source.REMOTE

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LiveCColors.BgSurface.copy(alpha = 0.7f))
            .border(1.dp, LiveCColors.Border.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .clickable(enabled = !isImage && !clip.text.isNullOrEmpty()) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("LiveC", clip.text))
            }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(LiveCColors.Accent.copy(alpha = 0.10f))
                    .border(1.dp, LiveCColors.Accent.copy(alpha = 0.18f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isImage) Icons.Default.Image else Icons.Default.ContentPaste,
                    contentDescription = null,
                    tint = LiveCColors.Accent,
                    modifier = Modifier.size(18.dp),
                )
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isImage) SourceBadge("Image", LiveCColors.Accent)
                    SourceBadge(
                        label = if (isRemote) "Remote" else "Local",
                        color = if (isRemote) LiveCColors.SevMed else LiveCColors.TextTertiary,
                    )
                    Text(
                        relativeTime(clip.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = LiveCColors.TextTertiary,
                    )
                }
                if (!clip.from.isNullOrEmpty()) {
                    Text(
                        "from ${clip.from.take(12)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LiveCColors.TextTertiary,
                    )
                }
            }

            if (!isImage && !clip.text.isNullOrEmpty()) {
                IconButton(
                    onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("LiveC", clip.text))
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = LiveCColors.TextTertiary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        if (isImage) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A))))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    tint = LiveCColors.Accent.copy(alpha = 0.5f),
                    modifier = Modifier.size(34.dp),
                )
            }
        } else if (!clip.text.isNullOrEmpty()) {
            Text(
                clip.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight  = 18.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun SourceBadge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.25f), CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = color,
        )
    }
}

// ── Send file CTA ────────────────────────────────────────────────────────────────

@Composable
private fun SendFileCta(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        LiveCColors.Accent.copy(alpha = 0.12f),
                        LiveCColors.Accent.copy(alpha = 0.04f),
                    )
                )
            )
            .border(1.dp, LiveCColors.Accent.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(LiveCColors.Accent.copy(alpha = 0.18f))
                .border(1.dp, LiveCColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Upload,
                contentDescription = null,
                tint = LiveCColors.Accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                "Send a file",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Pick from gallery, files or recents",
                style = MaterialTheme.typography.labelSmall,
                color = LiveCColors.TextTertiary,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = LiveCColors.TextTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── Transfer card ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTransferCard(
    transfer: TransferItem,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { onDismiss(); true } else false
        },
    )
    SwipeToDismissBox(
        state              = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent  = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(LiveCColors.SevCritical.copy(alpha = 0.15f))
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Dismiss",
                    tint = LiveCColors.SevCritical,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    ) {
        TransferCard(transfer = transfer, onSave = onSave, onAccept = onAccept, onReject = onReject)
    }
}

@Composable
private fun TransferCard(
    transfer: TransferItem,
    onSave: () -> Unit,
    onAccept: () -> Unit = {},
    onReject: () -> Unit = {},
) {
    val isIncoming = transfer.direction == TransferItem.Direction.INCOMING
    val dirColor   = if (isIncoming) LiveCColors.SevMed else LiveCColors.Accent
    val fileColor  = when (transfer.status) {
        TransferItem.Status.DONE       -> LiveCColors.SevLow
        TransferItem.Status.ERROR      -> LiveCColors.SevCritical
        TransferItem.Status.UPLOADING  -> LiveCColors.Accent
        else                           -> LiveCColors.SevMed
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LiveCColors.BgSurface.copy(alpha = 0.7f))
            .border(1.dp, LiveCColors.Border.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(fileColor.copy(alpha = 0.10f))
                .border(1.dp, fileColor.copy(alpha = 0.18f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = fileColor,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(dirColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isIncoming) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = dirColor,
                        modifier = Modifier.size(10.dp),
                    )
                }
                Text(
                    transfer.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 3.dp),
            ) {
                val ctx = LocalContext.current
                if (transfer.size > 0) {
                    Text(
                        Formatter.formatShortFileSize(ctx, transfer.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = LiveCColors.TextTertiary,
                    )
                    MetaDot()
                }
                if (transfer.from.isNotEmpty()) {
                    Text(
                        transfer.from.take(12),
                        style = MaterialTheme.typography.labelSmall,
                        color = LiveCColors.TextTertiary,
                    )
                    MetaDot()
                }
                StatusPill(transfer.status)
            }
        }

        when (transfer.status) {
            TransferItem.Status.OFFER_PENDING -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(LiveCColors.SevLow.copy(alpha = 0.12f))
                            .border(1.dp, LiveCColors.SevLow.copy(alpha = 0.3f), RoundedCornerShape(9.dp))
                            .clickable { onAccept() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Accept",
                            tint = LiveCColors.SevLow,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(LiveCColors.SevCritical.copy(alpha = 0.10f))
                            .border(1.dp, LiveCColors.SevCritical.copy(alpha = 0.25f), RoundedCornerShape(9.dp))
                            .clickable { onReject() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Reject",
                            tint = LiveCColors.SevCritical,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            TransferItem.Status.PENDING -> {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(LiveCColors.Accent.copy(alpha = 0.10f))
                        .border(1.dp, LiveCColors.Accent.copy(alpha = 0.25f), RoundedCornerShape(9.dp))
                        .clickable { onSave() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Save",
                        tint = LiveCColors.Accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            TransferItem.Status.DOWNLOADING, TransferItem.Status.UPLOADING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = LiveCColors.Accent,
                )
            }
            else -> {}
        }
    }
}

@Composable
private fun StatusPill(status: TransferItem.Status) {
    val (label, color) = when (status) {
        TransferItem.Status.OFFER_PENDING -> "Offered"     to LiveCColors.Accent
        TransferItem.Status.PENDING       -> "Pending"     to LiveCColors.Accent
        TransferItem.Status.DOWNLOADING   -> "Saving…"     to LiveCColors.SevMed
        TransferItem.Status.UPLOADING     -> "↑ Uploading" to LiveCColors.SevMed
        TransferItem.Status.DONE          -> "Done"        to LiveCColors.SevLow
        TransferItem.Status.ERROR         -> "Error"       to LiveCColors.SevCritical
        TransferItem.Status.REJECTED      -> "Rejected"    to LiveCColors.SevCritical
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.25f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = color,
        )
    }
}

@Composable
private fun MetaDot() {
    Box(
        Modifier
            .size(2.dp)
            .clip(CircleShape)
            .background(LiveCColors.TextTertiary.copy(alpha = 0.5f))
    )
}

// ── Empty state ──────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(icon: ImageVector, title: String, sub: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(LiveCColors.BgElevated.copy(alpha = 0.7f))
                .border(1.dp, LiveCColors.Border.copy(alpha = 0.8f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null,
                tint = LiveCColors.TextTertiary, modifier = Modifier.size(32.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Text(
            sub,
            style = MaterialTheme.typography.bodySmall,
            color = LiveCColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 48.dp),
        )
    }
}

// ── QR bottom sheet ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrBottomSheet(config: AppConfig, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val payload = remember(config.relayUrl, config.roomToken) {
        JSONObject()
            .put("relayUrl", config.relayUrl)
            .put("roomToken", config.roomToken)
            .toString()
    }
    val qrBitmap = remember(payload) { generateQrBitmap(payload, 512) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = LiveCColors.BgSurface,
        shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle       = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(LiveCColors.TextTertiary.copy(alpha = 0.4f))
            )
        },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Pair another device",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Open LiveC on Windows or another Android and scan this code.",
                style = MaterialTheme.typography.bodySmall,
                color = LiveCColors.TextTertiary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(14.dp),
            ) {
                Image(
                    bitmap           = qrBitmap.asImageBitmap(),
                    contentDescription = "QR code",
                    modifier         = Modifier.size(200.dp),
                    filterQuality    = FilterQuality.None,
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(LiveCColors.BgBase)
                    .border(1.dp, LiveCColors.Border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(LiveCColors.Accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null,
                        tint = LiveCColors.Accent, modifier = Modifier.size(14.dp))
                }
                Column {
                    Text("Room", style = MaterialTheme.typography.labelSmall,
                        color = LiveCColors.TextTertiary)
                    Text(
                        "#${config.roomToken.take(8)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(
                x, y,
                if (matrix[x, y]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE,
            )
        }
    }
    return bmp
}

// ── Utilities ────────────────────────────────────────────────────────────────────

private fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L     -> "just now"
        diff < 3_600_000L  -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        else               -> "${diff / 86_400_000L}d ago"
    }
}
