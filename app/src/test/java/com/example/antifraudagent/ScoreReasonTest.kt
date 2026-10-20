package com.example.antifraudagent

import com.example.antifraudagent.data.remote.RemoteFraudLog
import com.example.antifraudagent.data.score.ScoreEntry
import com.example.antifraudagent.data.score.ScoreRepository.Companion.nextEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class ScoreReasonTest {

    private val since = OffsetDateTime.parse("2026-10-15T12:00:00-03:00").toInstant().toEpochMilli()
    private val now = since + 4 * 24 * 3600_000L
    private val last = ScoreEntry(value = 0.42f, previous = null, observedAt = since, reason = "")

    private fun log(fraud: Boolean, at: String, category: String? = "phishing", hybrid: String = "FRAUDE") =
        RemoteFraudLog(
            id = at, userId = "u", content = "x", riskScore = 0f, isFraud = fraud,
            explanation = "Veredito Híbrido: $hybrid. LLM: Fraude | Modelo Local: Fraude. Justificativa LLM: ok",
            source = "whatsapp", detectedAt = at, category = category
        )

    @Test
    fun `primeira leitura`() {
        val entry = nextEntry(null, 0.3f, emptyList(), now)!!
        assertNull(entry.previous)
        assertEquals("Primeira leitura do seu índice.", entry.reason)
    }

    @Test
    fun `sem mudanca nao gera entrada`() {
        assertNull(nextEntry(last, 0.42f, emptyList(), now))
    }

    @Test
    fun `subiu com golpes novos cita quantidade e categoria`() {
        val logs = listOf(
            log(true, "2026-10-16T10:00:00-03:00"),
            log(true, "2026-10-17T10:00:00-03:00", category = "scam"),
            log(false, "2026-10-17T11:00:00-03:00", hybrid = "AVISO"), // nao e golpe confirmado: o servidor nao conta
            log(true, "2026-10-10T10:00:00-03:00") // antes da ultima leitura: nao conta
        )
        val entry = nextEntry(last, 0.61f, logs, now)!!
        assertEquals(0.42f, entry.previous)
        assertEquals("Subiu porque você teve 2 golpes novos desde 15/10 (phishing, golpe financeiro).", entry.reason)
    }

    @Test
    fun `caiu mesmo com golpe explica a comparacao`() {
        val entry = nextEntry(last, 0.2f, listOf(log(true, "2026-10-16T10:00:00-03:00")), now)!!
        assertEquals(
            "Caiu mesmo com 1 golpe novo desde 15/10 (phishing): os demais usuários receberam " +
                "proporcionalmente mais golpes que você.",
            entry.reason
        )
    }

    @Test
    fun `mudou sem golpe novo`() {
        val up = nextEntry(last, 0.5f, listOf(log(false, "2026-10-16T10:00:00-03:00", hybrid = "SEGURO")), now)!!
        assertTrue(up.reason.startsWith("Subiu na comparação com os demais usuários"))
        val down = nextEntry(last, 0.2f, emptyList(), now)!!
        assertEquals("Caiu: você não teve golpes novos desde 15/10.", down.reason)
    }
}
