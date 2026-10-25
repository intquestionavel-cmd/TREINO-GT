package br.com.treinogt.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import br.com.treinogt.capture.RecognitionPipeline
import br.com.treinogt.data.SessionRepository
import br.com.treinogt.recognition.ProfileConfig
import br.com.treinogt.recognition.Roi
import br.com.treinogt.recognition.RoiConfig
import br.com.treinogt.recognition.RoiKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** Editor visual de ROIs sobre a tela real do jogo: arrastar = mover; canto inferior direito = redimensionar. */
class RoiEditorView(ctx: Context, initial: RoiConfig) : View(ctx) {
    private val rects = LinkedHashMap<RoiKind, RectF>()
    private var dragging: RoiKind? = null
    private var resizing = false
    private var lx = 0f
    private var ly = 0f
    private val colors = mapOf(
        RoiKind.SPEED to Color.GREEN, RoiKind.GEAR to Color.YELLOW, RoiKind.THROTTLE to Color.CYAN,
        RoiKind.BRAKE to Color.RED, RoiKind.MINIMAP to Color.MAGENTA,
    )
    private val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val text = Paint().apply { textSize = 30f; isAntiAlias = true }

    init {
        val def = RoiConfig.placeholder()
        for (k in RoiKind.values()) {
            val r = initial.roi(k) ?: def.roi(k)!!
            rects[k] = RectF(r.left, r.top, r.right, r.bottom)
        }
    }

    override fun onDraw(c: Canvas) {
        for ((k, r) in rects) {
            val px = RectF(r.left * width, r.top * height, r.right * width, r.bottom * height)
            val col = colors[k] ?: Color.WHITE
            stroke.color = col; fill.color = Color.argb(40, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRect(px, fill); c.drawRect(px, stroke)
            text.color = col
            c.drawText(k.label, px.left + 6, px.top + 32, text)
            fill.color = col
            c.drawRect(px.right - 28, px.bottom - 28, px.right, px.bottom, fill)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y; dragging = null; resizing = false
                val hs = 56f
                for ((k, r) in rects.entries.reversed()) {
                    val px = RectF(r.left * w, r.top * h, r.right * w, r.bottom * h)
                    if (e.x in (px.right - hs)..(px.right + hs) && e.y in (px.bottom - hs)..(px.bottom + hs)) {
                        dragging = k; resizing = true; break
                    }
                }
                if (dragging == null) {
                    for ((k, r) in rects.entries.reversed()) {
                        val px = RectF(r.left * w, r.top * h, r.right * w, r.bottom * h)
                        if (px.contains(e.x, e.y)) { dragging = k; break }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val k = dragging ?: return true
                val r = rects[k]!!
                val dx = (e.x - lx) / w; val dy = (e.y - ly) / h
                lx = e.x; ly = e.y
                if (resizing) {
                    r.right = (r.right + dx).coerceIn(r.left + 0.01f, 1f)
                    r.bottom = (r.bottom + dy).coerceIn(r.top + 0.01f, 1f)
                } else {
                    val rw = r.width(); val rh = r.height()
                    val nl = (r.left + dx).coerceIn(0f, 1f - rw); val nt = (r.top + dy).coerceIn(0f, 1f - rh)
                    r.set(nl, nt, nl + rw, nt + rh)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = null
        }
        return true
    }

    fun toRoiConfig(base: RoiConfig, refW: Int, refH: Int, calibrated: Boolean): RoiConfig = base.copy(
        refWidth = refW, refHeight = refH, calibrated = calibrated,
        rois = rects.mapValues { (_, r) -> Roi(r.left, r.top, r.right, r.bottom) },
    )
}

/**
 * Overlay de tela cheia para calibrar as ROIs sobre o jogo real, testar a leitura e salvar o perfil.
 * Durante "LER AGORA" / "PEDAIS SOLTOS" todo o overlay é ocultado ~250 ms para não contaminar o frame capturado.
 */
class RoiCalibrationOverlay(
    private val ctx: Context,
    private val scope: CoroutineScope,   // Dispatchers.Main
    private val pipeline: RecognitionPipeline,
    private val repo: SessionRepository,
    private val frameSize: () -> Pair<Int, Int>,
    private val onClosed: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var params = pipeline.profile.params
    private val base = pipeline.profile
    private val editor = RoiEditorView(ctx, base.roi)
    private val result = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 12f; setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
        text = "Ajuste as caixas sobre o jogo. Com os pedais SOLTOS, toque em PEDAIS SOLTOS = BASE."
    }
    private val root = FrameLayout(ctx)
    private var shown = false

    private fun btn(t: String, c: String, f: () -> Unit) = Button(ctx).apply {
        text = t; textSize = 10f; isAllCaps = false; setTextColor(Color.WHITE)
        minHeight = 0; minimumHeight = 0; setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
        background = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = ctx.dp(6).toFloat() }
        setOnClickListener { f() }
    }

    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.argb(200, 0, 0, 0))
        addView(result)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lpb = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(ctx.dp(2), 0, ctx.dp(2), ctx.dp(4)) }
            addView(btn("LER AGORA", "#1565C0") { readNow() }, lpb)
            addView(btn("PEDAIS SOLTOS = BASE", "#00838F") { captureBase() }, lpb)
            addView(btn("SALVAR", "#2E7D32") { save() }, lpb)
            addView(btn("CANCELAR", "#B71C1C") { close() }, lpb)
        })
    }

    init {
        root.addView(editor, FrameLayout.LayoutParams(-1, -1))
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    fun show() {
        if (shown) return
        val lp = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(root, lp); shown = true
    }

    private fun close() {
        if (!shown) return
        runCatching { wm.removeView(root) }; shown = false; onClosed()
    }

    private fun currentRoi(calibrated: Boolean): RoiConfig {
        val (w, h) = frameSize()
        return editor.toRoiConfig(base.roi, w, h, calibrated)
    }

    /** Oculta o overlay, espera frames limpos, executa [block] fora da thread principal e reexibe. */
    private fun hiddenRun(block: suspend () -> String) {
        scope.launch {
            root.visibility = View.INVISIBLE
            delay(250)
            val msg = try { withContext(Dispatchers.Default) { block() } } catch (e: Exception) { "Erro: ${e.message}" }
            root.visibility = View.VISIBLE
            result.text = msg
        }
    }

    private fun readNow() = hiddenRun {
        val ro = pipeline.readOnce(currentRoi(true))
            ?: return@hiddenRun "Sem frame novo. Verifique se a captura está ativa e o jogo em primeiro plano."
        val s = ro.speed; val g = ro.gear
        "Frame ${ro.frameWidth}x${ro.frameHeight} | OCR ${ro.ocrMs} ms\n" +
            "Velocidade: raw='${s.raw}' -> ${s.value ?: "—"} km/h [${s.validity}, conf=${s.confidence ?: "n/d"}]\n" +
            "Marcha: raw='${g.raw}' -> ${g.value ?: "—"} [${g.validity}, conf=${g.confidence ?: "n/d"}]\n" +
            "Acelerador px-diff: ${ro.throttleRatio?.let { "%.3f".format(it) } ?: "sem base"} | " +
            "Freio px-diff: ${ro.brakeRatio?.let { "%.3f".format(it) } ?: "sem base"}"
    }

    private fun captureBase() = hiddenRun {
        val pair = pipeline.captureBaselines(currentRoi(true)) ?: return@hiddenRun "Sem frame novo; tente de novo."
        params = params.copy(throttleBaseline = pair.first, brakeBaseline = pair.second)
        pipeline.profile = pipeline.profile.copy(params = params)
        "Base 'pedais soltos' capturada. Agora pise no acelerador/freio e use LER AGORA para conferir a variação."
    }

    private fun save() {
        scope.launch {
            val roi = currentRoi(true)
            val saved = withContext(Dispatchers.IO) { repo.saveProfile(base.copy(roi = roi, params = params)) }
            pipeline.profile = saved
            pipeline.resetTrackers()
            close()
        }
    }
}
