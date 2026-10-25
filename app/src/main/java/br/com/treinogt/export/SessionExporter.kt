package br.com.treinogt.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import br.com.treinogt.data.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Gera os arquivos no cache do app e abre o seletor de compartilhamento do Android (sem rede, sem servidor). */
object SessionExporter {
    suspend fun share(ctx: Context, repo: SessionRepository, sessionId: Long): Boolean {
        val files = withContext(Dispatchers.IO) {
            val d = repo.load(sessionId) ?: return@withContext null
            val dir = File(ctx.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val base = "treinogt_sessao_${d.session.id}"
            listOf(
                File(dir, "${base}_amostras.csv").apply { writeText(ExportFormatter.samplesCsv(d)) },
                File(dir, "${base}_passagens.csv").apply { writeText(ExportFormatter.lapsCsv(d)) },
                File(dir, "$base.json").apply { writeText(ExportFormatter.sessionJson(d, System.currentTimeMillis())) },
            )
        } ?: return false
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Exportar sessão").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
