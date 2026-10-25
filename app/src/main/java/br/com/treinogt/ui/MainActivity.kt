package br.com.treinogt.ui

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import br.com.treinogt.GtApp
import br.com.treinogt.engine.CaptureStatus
import br.com.treinogt.export.SessionExporter
import br.com.treinogt.service.CollectionService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as GtApp).container

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) CollectionService.startCapture(this, r.resultCode, data)
            else container.engine.onCaptureLost(CaptureStatus.DENIED)   // negada: registra o estado, não quebra a sessão
        }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* estado relido no ON_RESUME */ }

    private val actions by lazy {
        ControlActions(
            requestOverlayPermission = {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            requestNotificationPermission = {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            launchProjection = {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                // Android 14+: força captura da TELA INTEIRA (a opção "um app" não enxergaria o GT Rivals).
                val intent = if (Build.VERSION.SDK_INT >= 34)
                    mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else mpm.createScreenCaptureIntent()
                projectionLauncher.launch(intent)
            },
            startManualService = { CollectionService.startManual(this) },
            stopService = { CollectionService.stop(this) },
            exportSession = { id -> lifecycleScope.launch { SessionExporter.share(this@MainActivity, container.repository, id) } },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { AppNav(actions) }
            }
        }
    }
}

@Composable
private fun AppNav(actions: ControlActions) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "history") {
        composable("history") {
            HistoryScreen(onNew = { nav.navigate("control") }, onOpen = { nav.navigate("report/$it") }, onExport = actions.exportSession)
        }
        composable("control") {
            ControlScreen(actions, onBack = { nav.popBackStack() }, onOpenReport = { nav.navigate("report/$it") })
        }
        composable("report/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: -1L
            ReportScreen(id, onBack = { nav.popBackStack() }, onExport = actions.exportSession)
        }
    }
}
