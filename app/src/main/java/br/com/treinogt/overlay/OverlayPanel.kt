package br.com.treinogt.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import br.com.treinogt.core.Formatters
import br.com.treinogt.engine.CaptureStatus
import br.com.treinogt.engine.Phase
import br.com.treinogt.engine.SessionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/**
 * Janela flutuante mínima: só controla a coleta (sem gráficos/velocidade/estatísticas durante a corrida).
 * Arrastável pela barra de título. Encerrar exige 2 toques (o 1º arma por 3 s) para evitar toque acidental.
 */
class OverlayPanel(
    private val ctx: Context,
    private val engine: SessionEngine,
    private val scope: CoroutineScope,          // Dispatchers.Main
    private val configJson: () -> String,
    private val onCalibrate: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var shown = false
    private var endArmedUntil = 0L
    private var flash: String? = null
    private var flashUntil = 0L
    private var lastSavedNote = false
    private var phaseJob: Job? = null

    private val title = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 11f; setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val btnStart = button("INICIAR COLETA\nMARCO ZERO", "#2E7D32") { onStart() }
    private val btnCal = button("CALIBRAR", "#455A64") { onCalibrate() }
    private val btnLap = button("PASSAGEM", "#1565C0") { onLap() }
    private val btnPause = button("PAUSAR", "#EF6C00") { onPauseResume() }
    private val btnEnd = button("ENCERRAR", "#B71C1C") { onEnd() }

    private val row1 = row(btnStart, btnCal)
    private val row2 = row(btnLap, btnPause, btnEnd)
    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Color.argb(190, 20, 20, 20)); cornerRadius = ctx.dp(8).toFloat() }
        addView(title); addView(row1); addView(row2)
    }
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = ctx.dp(8); y = ctx.dp(80) }

    private val ticker = object : Runnable {
        override fun run() { updateTitle(); handler.postDelayed(this, 200) }
    }

    init {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        title.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                    if (shown) wm.updateViewLayout(root, lp)
                }
            }
            true
        }
    }

    fun show() {
        if (shown) return
        wm.addView(root, lp); shown = true
        phaseJob = engine.phase.onEach { render() }.launchIn(scope)
        render(); handler.post(ticker)
    }

    fun hide() {
        if (!shown) return
        handler.removeCallbacks(ticker)
        phaseJob?.cancel(); phaseJob = null
        runCatching { wm.removeView(root) }
        shown = false
    }

    private fun onStart() {
        engine.start(null, configJson())
        lastSavedNote = false
    }

    private fun onLap() {
        val r = engine.markLap() ?: return
        flash = "Passagem #${r.sequence}" + if (r.isReference) " (ref.)" else ""
        flashUntil = System.currentTimeMillis() + 1200
    }

    private fun onPauseResume() {
        if (engine.phase.value == Phase.RUNNING) engine.pause() else engine.resume()
    }

    private fun onEnd() {
        val now = System.currentTimeMillis()
        if (now > endArmedUntil) {
            endArmedUntil = now + 3000
            btnEnd.text = "CONFIRMAR?"
            handler.postDelayed({ if (System.currentTimeMillis() >= endArmedUntil) btnEnd.text = "ENCERRAR" }, 3100)
        } else {
            endArmedUntil = 0
            btnEnd.text = "ENCERRAR"
            scope.launch { lastSavedNote = engine.end() }
        }
    }

    private fun render() {
        val phase = engine.phase.value
        row1.visibility = if (phase == Phase.IDLE) View.VISIBLE else View.GONE
        row2.visibility = if (phase == Phase.IDLE) View.GONE else View.VISIBLE
        btnPause.text = if (phase == Phase.PAUSED) "CONTINUAR" else "PAUSAR"
        btnLap.isEnabled = phase == Phase.RUNNING
        btnLap.alpha = if (phase == Phase.RUNNING) 1f else 0.4f
        updateTitle()
    }

    private fun updateTitle() {
        val phase = engine.phase.value
        val el = engine.elapsed()
        val cap = when (engine.captureStatus.value) {
            CaptureStatus.ACTIVE -> "cap✔"
            CaptureStatus.NOT_REQUESTED -> "manual"
            CaptureStatus.DENIED -> "cap NEGADA"
            CaptureStatus.REVOKED -> "cap REVOGADA"
            CaptureStatus.FAILED -> "cap FALHOU"
        }
        val state = when (phase) { Phase.IDLE -> if (lastSavedNote) "SALVA" else "PARADO"; Phase.RUNNING -> "COLETANDO"; Phase.PAUSED -> "PAUSADO" }
        val f = if (System.currentTimeMillis() < flashUntil) " | $flash" else ""
        title.text = "⠿ $state ${el?.let { Formatters.duration(it.relativeNanos) } ?: ""} [$cap]$f" +
            if (engine.persistenceError.value != null) " ⚠ERRO GRAV." else ""
    }

    private fun button(text: String, color: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; textSize = 10f; setTextColor(Color.WHITE); isAllCaps = false
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
        background = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = ctx.dp(6).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(ctx.dp(4), ctx.dp(2), ctx.dp(4), ctx.dp(4))
        v.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(ctx.dp(2), 0, ctx.dp(2), 0) }) }
    }
}
