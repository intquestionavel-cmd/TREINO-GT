package br.com.treinogt.core

/** Relógio controlável para testes. NÃO representa dados de corrida. */
class FakeTime(var now: Long = 1_000_000_000L) : TimeSource {
    override fun nanos(): Long = now
    fun advanceSec(s: Double) { now += (s * 1e9).toLong() }
}
