package com.livec.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livec.app.ui.AppViewModel
import com.livec.app.ui.theme.LiveCColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    val ctx    = LocalContext.current

    var deviceName by remember(config?.deviceName) {
        mutableStateOf(config?.deviceName ?: Build.MODEL)
    }
    var nameSaved by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LiveCColors.BgSurface.copy(alpha = 0.85f),
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // ── Device name ──────────────────────────────────────────────────
            SettingsGroup(label = "Identity") {
                OutlinedTextField(
                    value    = deviceName,
                    onValueChange = {
                        deviceName = it
                        nameSaved  = false
                    },
                    label = { Text("Device Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (deviceName.isNotBlank()) {
                            vm.setDeviceName(deviceName.trim())
                            nameSaved = true
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = LiveCColors.Accent,
                        unfocusedBorderColor = LiveCColors.Border,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        if (deviceName.isNotBlank()) {
                            vm.setDeviceName(deviceName.trim())
                            nameSaved = true
                        }
                    },
                    enabled = deviceName.isNotBlank(),
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = if (nameSaved) LiveCColors.SevLow.copy(alpha = 0.15f)
                                         else LiveCColors.Accent,
                        contentColor   = if (nameSaved) LiveCColors.SevLow
                                         else LiveCColors.BgBase,
                    ),
                    shape   = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Icon(
                        if (nameSaved) Icons.Default.Check else Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (nameSaved) "Saved" else "Save Name")
                }
            }

            // ── Connection info ───────────────────────────────────────────────
            SettingsGroup(label = "Connection") {
                InfoRow(
                    icon  = Icons.Default.Cloud,
                    label = "Relay URL",
                    value = config?.relayUrl?.ifEmpty { "—" } ?: "—",
                )

                Spacer(Modifier.height(2.dp))

                // Device ID — tap to copy
                val deviceId = config?.deviceId ?: ""
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
                        }
                        .background(LiveCColors.BgElevated)
                        .border(1.dp, LiveCColors.Border, MaterialTheme.shapes.small)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Device ID",
                            style = MaterialTheme.typography.labelSmall,
                            color = LiveCColors.TextTertiary)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            deviceId.ifEmpty { "—" },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                        )
                    }
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy",
                        modifier = Modifier.size(16.dp),
                        tint = LiveCColors.TextTertiary)
                }
            }

            // ── Danger zone ───────────────────────────────────────────────────
            SettingsGroup(label = "Room") {
                OutlinedButton(
                    onClick  = { vm.leaveRoom() },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error),
                    border   = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    shape    = MaterialTheme.shapes.small,
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = null,
                        modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Leave Room")
                }

                TextButton(
                    onClick  = { vm.clearTransfers() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null,
                        modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear Transfer History",
                        color = LiveCColors.TextSecondary)
                }
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(LiveCColors.BgSurface.copy(alpha = 0.7f))
            .border(1.dp, LiveCColors.Border, MaterialTheme.shapes.large)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LiveCColors.TextTertiary,
            letterSpacing = androidx.compose.ui.unit.TextUnit(
                value = 0.08f, type = androidx.compose.ui.unit.TextUnitType.Em
            ),
        )
        HorizontalDivider(color = LiveCColors.Border, thickness = 0.5.dp)
        content()
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(LiveCColors.BgElevated)
            .border(1.dp, LiveCColors.Border, MaterialTheme.shapes.small)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null,
            tint = LiveCColors.TextTertiary, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = LiveCColors.TextTertiary)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
