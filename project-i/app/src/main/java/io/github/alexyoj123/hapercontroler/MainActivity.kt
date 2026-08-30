package io.github.alexyoj123.hapercontroler

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alexyoj123.hapercontroler.ui.AppViewModel
import io.github.alexyoj123.hapercontroler.ui.HaperApp
import io.github.alexyoj123.hapercontroler.ui.theme.HaperControlerTheme

class MainActivity : ComponentActivity() {

    private var pendingSharedApk: Uri? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* el estado real lo consulta cada pantalla; aca no hay nada que hacer */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingSharedApk = extractSharedApk(intent)
        requestRuntimePermissions()

        setContent {
            val vm: AppViewModel = viewModel()
            val modoTema by vm.themeMode.collectAsState()
            HaperControlerTheme(mode = modoTema) {
                pendingSharedApk?.let {
                    vm.offerApk(it)
                    pendingSharedApk = null
                }
                HaperApp(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSharedApk = extractSharedApk(intent)
    }

    /** Un APK compartido desde otra app llega como EXTRA_STREAM. */
    private fun extractSharedApk(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
                add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                add(android.Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        runCatching { permissionLauncher.launch(wanted.toTypedArray()) }
    }
}
