package br.com.treinogt.core

import java.util.Locale

object Formatters {
    /** mm:ss.d ou h:mm:ss.d. Trunca em décimos de segundo. */
    fun duration(nanos: Long): String {
        val neg = nanos < 0
        val tenths = Math.abs(nanos) / 100_000_000L
        val h = tenths / 36000
        val m = (tenths / 600) % 60
        val s = (tenths / 10) % 60
        val d = tenths % 10
        val body = if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d.%d", h, m, s, d)
        else String.format(Locale.ROOT, "%02d:%02d.%d", m, s, d)
        return if (neg) "-$body" else body
    }

    fun seconds3(nanos: Long): String = String.format(Locale.ROOT, "%.3f", nanos / 1e9)
}
