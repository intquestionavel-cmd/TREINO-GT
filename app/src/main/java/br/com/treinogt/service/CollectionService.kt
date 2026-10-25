package br.com.treinogt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import br.com.treinogt.GtApp
import br.com.treinogt.capture.MlKitOcrEngine
import br.com.treinogt.capture.RecognitionPipeline
import br.com.treinogt.capture.ScreenCapturer
import br.com.treinogt.core.AndroidTimeSource
import br.com.treinogt.engine.CaptureStatus
import br.com.treinogt.engine.PauseReason
import br.com.treinogt.engine.Phase
import br.com.treinogt.overlay.OverlayPanel
import br.com.treinogt.overlay.RoiCalibrationOverlay
import br.com.treinogt.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Serviço em primeiro plano. Dois modos:
 *  - CAPTURA: tipo mediaProjection (após consentimento do usuário) + pipeline de reconhecimento + janela flutuante.
 *  - MANUAL: tipo specialUse, sem captura; só a janela flutuante (marco zero e passagens manuais).
 * Não usa AccessibilityService. Revogação da captura => engine.onCaptureLost (registra o motivo e pausa).
 */
class CollectionService : Service() {
    private val container get() = (application as GtApp).container
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var capturer: ScreenCapturer? = null
    private var pipeline: RecognitionPipeline? = null
    private var ocr: MlKitOcrEngine? = null
    private var overlay: OverlayPanel? = null
    private var calibration: RoiCalibrationOverlay? = null
    private var pipelineJob: Job? = null
    @Volatile private var intervalMs = 300L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> startCapture(intent)
            ACTION_START_MANUAL -> startManual()
            else -> shutdown()
        }
        return START_NOT_STICKY
    }

    private fun startManual() {
        enterForeground(mediaProjection = false)
        teardownCapture()
        container.engine.setCaptureStatus(CaptureStatus.NOT_REQUESTED)
        showOverlay(withCalibration = false)
    }

    private fun startCapture(intent: Intent) {
        // Android 14+: startForeground(mediaProjection) ANTES de obter o MediaProjection.
        enterForeground(mediaProjection = true)
        teardownCapture()
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val engine = container.engine
        if (data == null) { engine.onCaptureLost(CaptureStatus.FAILED); showOverlay(false); return }

        val projection = try {
            (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data)
        } catch (e: Exception) { null }
        if (projection == null) { engine.onCaptureLost(CaptureStatus.FAILED); showOverlay(false); return }

        val cap = ScreenCapturer(this, projection) {
            // Usuário/sistema revogou: o capturador já liberou seus recursos.
            mainScope.launch { teardownCapture(); engine.onCaptureLost(CaptureStatus.REVOKED) }
        }
        try { cap.start() } catch (e: Exception) {
            runCatching { cap.stop() }
            engine.onCaptureLost(CaptureStatus.FAILED); showOverlay(false); return
        }
        capturer = cap
        engine.setCaptureStatus(CaptureStatus.ACTIVE)

        val engineOcr = MlKitOcrEngine().also { ocr = it }
        val pl = RecognitionPipeline(cap, engineOcr, engine, AndroidTimeSource, container.metrics)
        pipeline = pl
        container.metrics.reset()
        engine.setPerfProvider { container.metrics.snapshot.value.toJson() }
        pipelineJob = workScope.launch {
            launch { container.settings.intervalMs.collectLatest { intervalMs = it } }
            pl.profile = container.repository.loadProfile()
            pl.run { intervalMs }
        }
        showOverlay(withCalibration = true)
    }

    private fun showOverlay(withCalibration: Boolean) {
        overlay?.hide(); overlay = null
        if (!Settings.canDrawOverlays(this)) return   // sem permissão: o controle fica no app
        val p = OverlayPanel(
            ctx = this, engine = container.engine, scope = mainScope,
            configJson = { captureConfigJson() },
            onCalibrate = { if (withCalibration) openCalibration() },
        )
        overlay = p; p.show()
    }

    private fun openCalibration() {
        val pl = pipeline ?: return
        val cap = capturer ?: return
        if (container.engine.phase.value != Phase.IDLE) return
        calibration?.let { return }
        overlay?.hide()
        calibration = RoiCalibrationOverlay(
            ctx = this, scope = mainScope, pipeline = pl, repo = container.repository,
            frameSize = { cap.width to cap.height },
            onClosed = { calibration = null; overlay?.show() },
        ).also { it.show() }
    }

    private fun captureConfigJson(): String = JSONObject()
        .put("analysisIntervalMs", intervalMs)
        .put("captureActive", container.engine.captureStatus.value == CaptureStatus.ACTIVE)
        .put("frameWidth", capturer?.width ?: 0).put("frameHeight", capturer?.height ?: 0)
        .put("device", Build.MODEL).put("androidSdk", Build.VERSION.SDK_INT)
        .put("roi", pipeline?.profile?.roi?.let { JSONObject(br.com.treinogt.recognition.ProfileCodec.roiToJson(it)) } ?: JSONObject.NULL)
        .toString()

    private fun teardownCapture() {
        pipelineJob?.cancel(); pipelineJob = null
        pipeline = null
        capturer?.stop(); capturer = null
        ocr?.close(); ocr = null
    }

    private fun shutdown() {
        container.engine.pause(PauseReason.SERVICE_STOPPED)   // no-op se não estiver coletando
        container.engine.setCaptureStatus(CaptureStatus.NOT_REQUESTED)
        teardownCapture()
        overlay?.hide(); overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        teardownCapture()
        overlay?.hide()
        mainScope.cancel(); workScope.cancel()
        super.onDestroy()
    }

    // ---------------- notificação obrigatória ----------------
    private fun enterForeground(mediaProjection: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Coleta de treino", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, CollectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Treino GT")
            .setContentText(if (mediaProjection) "Captura de tela ativa (processamento local)" else "Modo manual (sem captura de tela)")
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "Parar", stop).build()
        val type = when {
            mediaProjection -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    companion object {
        const val ACTION_START_CAPTURE = "br.com.treinogt.START_CAPTURE"
        const val ACTION_START_MANUAL = "br.com.treinogt.START_MANUAL"
        const val ACTION_STOP = "br.com.treinogt.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL = "collection"
        private const val NOTIF_ID = 42

        fun startCapture(ctx: Context, resultCode: Int, data: Intent) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, CollectionService::class.java).setAction(ACTION_START_CAPTURE)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_DATA, data))

        fun startManual(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, CollectionService::class.java).setAction(ACTION_START_MANUAL))

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CollectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
