package com.livec.app.ui.screens

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.livec.app.ui.AppViewModel
import com.livec.app.ui.theme.LiveCColors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(vm: AppViewModel, onDone: () -> Unit, onBack: (() -> Unit)? = null) {
    val perm = rememberPermissionState(Manifest.permission.CAMERA)
    LaunchedEffect(Unit) {
        if (!perm.status.isGranted) perm.launchPermissionRequest()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Code") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LiveCColors.BgSurface.copy(alpha = 0.85f),
                ),
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (!perm.status.isGranted) {
                CameraPermissionRequired(onRequest = { perm.launchPermissionRequest() })
            } else {
                QrScannerView(onQrDetected = { rawValue ->
                    vm.pairFromQr(rawValue).onSuccess { onDone() }
                })
            }
        }
    }
}

@Composable
private fun QrScannerView(onQrDetected: (String) -> Unit) {
    val ctx           = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView   = remember { PreviewView(ctx) }
    val scanner       = remember { BarcodeScanning.getClient() }
    var locked        by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val provider = suspendCoroutine<ProcessCameraProvider> { cont ->
            ProcessCameraProvider.getInstance(ctx).apply {
                addListener({ cont.resume(get()) }, ContextCompat.getMainExecutor(ctx))
            }
        }
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
            if (locked) { proxy.close(); return@setAnalyzer }
            val mediaImage = proxy.image
            if (mediaImage != null) {
                val img = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                scanner.process(img)
                    .addOnSuccessListener { codes ->
                        codes.firstOrNull()?.rawValue?.let { value ->
                            locked = true
                            onQrDetected(value)
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            } else {
                proxy.close()
            }
        }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Viewfinder overlay
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // Dimmed surround
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
            // Amber-bordered scan window
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(Color.Transparent)
                    .border(
                        width = 2.dp,
                        color = LiveCColors.Accent,
                        shape = MaterialTheme.shapes.large,
                    ),
            )
        }

        // Hint text at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Point at the QR code in the LiveC Windows app",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CameraPermissionRequired(onRequest: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(LiveCColors.Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = LiveCColors.Accent,
            )
        }
        Text("Camera access required",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Text(
            "LiveC needs camera access to scan the QR code shown on your Windows app.",
            style = MaterialTheme.typography.bodySmall,
            color = LiveCColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(
                containerColor = LiveCColors.Accent,
                contentColor   = LiveCColors.BgBase,
            ),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Grant Permission")
        }
    }
}
