package br.com.treinogt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.com.treinogt.analysis.ChartData

private const val PAD_L = 56f
private const val PAD_R = 12f
private const val PAD_T = 14f
private const val PAD_B = 26f
private const val BREAK_SEC = 1.5f   // não liga pontos separados por mais que isso (nunca interpola lacunas)

private fun label(c: DrawScope, text: String, x: Float, y: Float, color: Int, size: Float = 26f) {
    c.drawIntoCanvas {
        val p = android.graphics.Paint().apply { this.color = color; textSize = size; isAntiAlias = true }
        it.nativeCanvas.drawText(text, x, y, p)
    }
}

private fun DrawScope.background(d: ChartData, w: Float, textColor: Int) {
    val plotW = w - PAD_L - PAD_R
    // pausas (cinza) e lacunas (vermelho translúcido)
    for ((a, b) in d.pausesSec) drawRect(Color(0x33888888), Offset(PAD_L + a / d.totalSec * plotW, PAD_T),
        Size((b - a) / d.totalSec * plotW, size.height - PAD_T - PAD_B))
    for ((a, b) in d.gapsSec) drawRect(Color(0x33FF5252), Offset(PAD_L + a / d.totalSec * plotW, PAD_T),
        Size((b - a) / d.totalSec * plotW, size.height - PAD_T - PAD_B))
    // eixo de tempo
    val step = listOf(1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f, 600f).firstOrNull { d.totalSec / it <= (plotW / 110f) } ?: 900f
    var t = 0f
    while (t <= d.totalSec) {
        val x = PAD_L + t / d.totalSec * plotW
        drawLine(Color(0x22FFFFFF), Offset(x, PAD_T), Offset(x, size.height - PAD_B))
        label(this, "${t.toInt()}s", x - 12f, size.height - 6f, textColor, 22f)
        t += step
    }
    // passagens
    for ((s, seq) in d.lapsSec) {
        val x = PAD_L + s / d.totalSec * plotW
        drawLine(Color(0xFF42A5F5), Offset(x, PAD_T), Offset(x, size.height - PAD_B), 3f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        label(this, "P$seq", x + 4f, PAD_T + 22f, 0xFF42A5F5.toInt(), 22f)
    }
}

@Composable
fun SpeedChart(d: ChartData, width: Dp, textColor: Int) {
    Canvas(Modifier.width(width).height(210.dp)) {
        val plotW = size.width - PAD_L - PAD_R
        val plotH = size.height - PAD_T - PAD_B
        val ymax = (((d.maxSpeed + 49) / 50) * 50).toFloat()
        fun y(v: Float) = PAD_T + plotH * (1f - v / ymax)
        var g = 0f
        while (g <= ymax) {
            drawLine(Color(0x22FFFFFF), Offset(PAD_L, y(g)), Offset(size.width - PAD_R, y(g)))
            label(this, "${g.toInt()}", 4f, y(g) + 8f, textColor, 22f)
            g += 50f
        }
        background(d, size.width, textColor)
        val path = Path(); var pen = false; var prev = 0f
        for (i in d.tSec.indices) {
            val v = d.speed[i]
            if (v < 0) { pen = false; continue }
            val px = PAD_L + d.tSec[i] / d.totalSec * plotW; val py = y(v.toFloat())
            if (!pen || d.tSec[i] - prev > BREAK_SEC) path.moveTo(px, py) else path.lineTo(px, py)
            pen = true; prev = d.tSec[i]
        }
        drawPath(path, Color(0xFF66BB6A), style = Stroke(3.5f))
        label(this, "km/h", PAD_L + 6f, PAD_T + 44f, textColor, 22f)
    }
}

@Composable
fun PedalChart(d: ChartData, width: Dp, textColor: Int) {
    Canvas(Modifier.width(width).height(110.dp)) {
        val plotW = size.width - PAD_L - PAD_R
        val rowH = (size.height - PAD_T - PAD_B) / 2f
        background(d, size.width, textColor)
        fun row(states: ByteArray, idx: Int, on: Color, name: String) {
            val top = PAD_T + idx * rowH
            label(this, name, 2f, top + rowH / 2f + 8f, textColor, 20f)
            for (i in d.tSec.indices) {
                if (states[i] < 0) continue
                val end = if (i + 1 < d.tSec.size) minOf(d.tSec[i + 1], d.tSec[i] + BREAK_SEC) else d.tSec[i] + 0.3f
                val x = PAD_L + d.tSec[i] / d.totalSec * plotW
                val wpx = ((end - d.tSec[i]) / d.totalSec * plotW).coerceAtLeast(1.5f)
                drawRect(if (states[i].toInt() == 1) on else Color(0x22FFFFFF), Offset(x, top + 4f), Size(wpx, rowH - 8f))
            }
        }
        row(d.throttle, 0, Color(0xFF26C6DA), "Acel.")
        row(d.brake, 1, Color(0xFFEF5350), "Freio")
    }
}

@Composable
fun GearChart(d: ChartData, width: Dp, textColor: Int) {
    Canvas(Modifier.width(width).height(130.dp)) {
        val plotW = size.width - PAD_L - PAD_R
        val plotH = size.height - PAD_T - PAD_B
        val gmax = ((d.gear.maxOrNull() ?: 1).coerceAtLeast(2)).toFloat()
        fun y(v: Float) = PAD_T + plotH * (1f - (v - 0.5f) / gmax)
        background(d, size.width, textColor)
        for (gv in 1..gmax.toInt()) label(this, "$gv", 18f, y(gv.toFloat()) + 8f, textColor, 22f)
        for (i in d.tSec.indices) {
            val gv = d.gear[i]
            if (gv < 0) continue
            val end = if (i + 1 < d.tSec.size) minOf(d.tSec[i + 1], d.tSec[i] + BREAK_SEC) else d.tSec[i] + 0.3f
            val x1 = PAD_L + d.tSec[i] / d.totalSec * plotW
            val x2 = PAD_L + end / d.totalSec * plotW
            drawLine(Color(0xFFFFCA28), Offset(x1, y(gv.toFloat())), Offset(x2, y(gv.toFloat())), 5f)
        }
    }
}
