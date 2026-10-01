package com.example.antifraudagent

import com.example.antifraudagent.data.local.trusted.TrustedContact
import com.example.antifraudagent.data.trusted.TrustedContactRepository.Companion.digitsOf
import com.example.antifraudagent.data.trusted.TrustedContactRepository.Companion.matches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedContactMatchingTest {

    private fun contact(name: String?, phone: String?) =
        TrustedContact(name = name, phone = phone, phoneDigits = phone?.let { digitsOf(it) })

    private val mae = contact("Mãe", "(11) 98765-4321")

    @Test
    fun `numero do SMS com +55 bate com o numero salvo na agenda`() {
        assertTrue(matches("+5511987654321", listOf(mae)))
    }

    @Test
    fun `contato antigo salvo sem o 9 extra ainda bate`() {
        assertTrue(matches("+5511987654321", listOf(contact(null, "11 8765-4321"))))
    }

    @Test
    fun `nome da notificacao bate ignorando acento e maiusculas`() {
        assertTrue(matches("mae", listOf(mae)))
        assertTrue(matches("  MÃE ", listOf(mae)))
    }

    @Test
    fun `outro numero nao bate`() {
        assertFalse(matches("+5511912345678", listOf(mae)))
    }

    @Test
    fun `nome diferente nao bate`() {
        assertFalse(matches("Banco Seguro", listOf(mae)))
    }

    @Test
    fun `nome com digitos nao e tratado como telefone`() {
        assertFalse(matches("Promo 4321", listOf(contact(null, "4321"))))
    }

    @Test
    fun `numero curto de servico so bate se for igual`() {
        val banco = contact(null, "29000")
        assertTrue(matches("29000", listOf(banco)))
        assertFalse(matches("129000", listOf(banco)))
    }

    @Test
    fun `lista vazia nunca bate`() {
        assertFalse(matches("Mãe", emptyList()))
    }
}
