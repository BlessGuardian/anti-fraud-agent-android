package com.example.antifraudagent

import com.example.antifraudagent.calls.MessageAlertDedup
import com.example.antifraudagent.calls.MessageAlertDedup.claim
import com.example.antifraudagent.calls.MessageAlertDedup.fingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAlertDedupTest {

    private val now = 1792432189879L
    private val golpe = "Seu CPF foi bloqueado. Regularize em bit.ly/x"

    @Test
    fun `mesma mensagem so alerta uma vez`() {
        val entries = mutableMapOf<String, Long>()
        assertTrue(claim(entries, fingerprint(golpe), now))
        assertFalse(claim(entries, fingerprint(golpe), now + 60_000))
    }

    @Test
    fun `notificacao e tela com espacos e maiusculas diferentes contam como a mesma`() {
        assertEquals(fingerprint(golpe), fingerprint("  seu cpf foi BLOQUEADO.\nRegularize em   bit.ly/x "))
        assertNotEquals(fingerprint(golpe), fingerprint("Seu CPF foi bloqueado. Regularize em bit.ly/y"))
    }

    @Test
    fun `depois de 24h a mesma mensagem volta a alertar`() {
        val entries = mutableMapOf<String, Long>()
        assertTrue(claim(entries, fingerprint(golpe), now))
        assertFalse(claim(entries, fingerprint(golpe), now + MessageAlertDedup.WINDOW_MS - 1))
        assertTrue(claim(entries, fingerprint(golpe), now + MessageAlertDedup.WINDOW_MS))
    }

    @Test
    fun `limite de entradas descarta a mais antiga`() {
        val entries = mutableMapOf<String, Long>()
        repeat(500) { claim(entries, "k$it", now + it) }
        assertTrue(claim(entries, "nova", now + 1_000))
        assertEquals(500, entries.size)
        assertFalse("k0" in entries)
    }
}
