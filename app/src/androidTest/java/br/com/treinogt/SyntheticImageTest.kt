package br.com.treinogt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.treinogt.capture.LumaGridBuilder
import br.com.treinogt.capture.MlKitOcrEngine
import br.com.treinogt.recognition.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IMAGENS SINTÉTICAS (números desenhados por Canvas) — NÃO são capturas do GT Rivals.
 * Servem para detectar regressões grosseiras no OCR/parsers. A precisão real depende da fonte do jogo
 * e só pode ser validada com capturas reais (use a tela de calibração: LER AGORA).
 */
@RunWith(AndroidJUnit4::class)
class SyntheticImageTest {
    private fun digits(text: String, w: Int = 260, h: Int = 110): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.BLACK)
        val p = Paint().apply { color = Color.WHITE; textSize = 84f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        c.drawText(text, w / 2f, h * 0.75f, p)
        return b
    }

    @Test fun ocrReadsSyntheticSpeedNumbers() = runBlocking {
        val ocr = MlKitOcrEngine()
        var ok = 0
        val cases = listOf("0", "45", "128", "187", "256", "312")
        for (n in cases) {
            val r = ocr.read(digits(n))
            if (SpeedParser.parse(r.text, r.confidence).value == n.toInt()) ok++
        }
        ocr.close()
        assertTrue("OCR sintético acertou $ok/${cases.size}", ok >= cases.size - 1)   // tolera 1 falha; documente o resultado real
    }

    @Test fun pedalGridDetectsSyntheticStateChange() {
        fun bar(fillFrac: Float): Bitmap {
            val b = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Color.rgb(20, 20, 20))
            c.drawRect(0f, 100f * (1 - fillFrac), 200f, 100f, Paint().apply { color = Color.rgb(230, 230, 230) })
            return b
        }
        val rect = PxRect(0, 0, 200, 100)
        val base = LumaGridBuilder.build(bar(0f), rect)
        val released = PedalMath.changedRatio(LumaGridBuilder.build(bar(0f), rect), base)!!
        val pressed = PedalMath.changedRatio(LumaGridBuilder.build(bar(0.6f), rect), base)!!
        assertTrue(released < 0.02f); assertTrue(pressed > 0.4f)
        assertEquals(PedalState.PRESSED, PedalTracker().update(pressed).state)
    }
}
