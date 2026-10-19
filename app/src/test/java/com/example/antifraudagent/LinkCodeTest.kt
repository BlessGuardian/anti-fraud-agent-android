package com.example.antifraudagent

import com.example.antifraudagent.data.guardian.GuardianContact
import com.example.antifraudagent.data.guardian.LinkCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkCodeTest {

    @Test
    fun `codigo do responsavel vai e volta com acento`() {
        val contact = GuardianContact("João da Conceição", "(11) 98765-4321", "joao@exemplo.com")
        val code = LinkCode.encode(contact)
        assertEquals(contact, LinkCode.decode(code))
        assertEquals(contact, LinkCode.decode("  $code "))
    }

    @Test
    fun `e-mail e opcional`() {
        val contact = GuardianContact("Ana", "11987654321", null)
        assertEquals(contact, LinkCode.decode(LinkCode.encode(contact)))
    }

    @Test
    fun `codigo invalido e recusado`() {
        assertNull(LinkCode.decode("BG1:179f47cf-2641-5943-ae0e-3978db3c2175"))
        assertNull(LinkCode.decode("BG2:nao-e-base64-json"))
        assertNull(LinkCode.decode("https://golpe.example"))
        assertNull(LinkCode.decode(LinkCode.encode(GuardianContact("Sem telefone", "123", null))))
    }
}
