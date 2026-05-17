package com.livec.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.livec.app.ui.AppViewModel
import com.livec.app.ui.screens.HomeScreen
import com.livec.app.ui.screens.PairingScreen
import com.livec.app.ui.screens.SettingsScreen
import com.livec.app.ui.theme.LiveCTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification permission result — accepted or denied, continue either way. */ }

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.sendSharedFile(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Ask the user to whitelist us from battery optimization on first launch.
        // Without this, OEMs (OPPO, Xiaomi, Samsung etc.) aggressively freeze the
        // foreground service mid-transfer, killing WebSockets and PATCH uploads.
        requestIgnoreBatteryOptimizations()

        // Handle share-sheet intents
        handleShareIntent(intent)

        setContent {
            LiveCTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            vm             = vm,
                            onOpenSettings = { nav.navigate("settings") },
                            onPair         = { nav.navigate("pair") },
                            onSendFile     = { fileLauncher.launch("*/*") },
                        )
                    }
                    composable("pair") {
                        PairingScreen(
                            vm     = vm,
                            onDone = { nav.popBackStack() },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    @Suppress("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            // Some OEMs disable this intent — fall back to the generic battery settings screen.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) { /* give up silently */ }
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        when {
            !text.isNullOrEmpty() -> vm.sendSharedText(text)
            uri != null -> vm.sendSharedFile(uri)
        }
    }
}
