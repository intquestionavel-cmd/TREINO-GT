package br.com.treinogt.core

/** Fonte de tempo monotônica em nanossegundos. Injetável para permitir testes determinísticos. */
fun interface TimeSource {
    fun nanos(): Long
}

/** Produção: SystemClock.elapsedRealtimeNanos() — monotônico, não é afetado por mudança da hora do sistema. */
object AndroidTimeSource : TimeSource {
    override fun nanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()
}
