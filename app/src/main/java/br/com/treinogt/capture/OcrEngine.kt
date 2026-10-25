package br.com.treinogt.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class OcrResult(val text: String, val confidence: Float?)

interface OcrEngine {
    suspend fun read(bitmap: Bitmap): OcrResult
    fun close() {}
}

/** ML Kit Text Recognition (Latin, modelo embutido no APK, processamento local). */
class MlKitOcrEngine : OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun read(bitmap: Bitmap): OcrResult {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        // Confiança conservadora: a MENOR entre os elementos reconhecidos (null se o ML Kit não informar).
        val conf = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }
            .map { it.confidence }.minOrNull()
        return OcrResult(result.text, conf)
    }

    override fun close() = recognizer.close()
}
