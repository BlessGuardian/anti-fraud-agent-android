package com.example.antifraudagent

import com.example.antifraudagent.data.guardian.LinkCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkCodeTest {

    // Valores de referencia gerados com Python: uuid.uuid5(uuid.NAMESPACE_OID, nome)
    @Test
    fun `uuid5 igual ao do backend Python`() {
        assertEquals("b428b5d9-df19-5bb9-a1dc-115e071b836c", LinkCode.userIdForDevice("test"))
        assertEquals(
            "179f47cf-2641-5943-ae0e-3978db3c2175",
            LinkCode.userIdForDevice("3f0e2a9c-1111-4222-8333-944455556666")
        )
    }

    @Test
    fun `codigo vai e volta`() {
        val id = "179f47cf-2641-5943-ae0e-3978db3c2175"
        assertEquals("BG1:$id", LinkCode.encode(id))
        assertEquals(id, LinkCode.decode(LinkCode.encode(id)))
        assertEquals(id, LinkCode.decode("  $id "))
    }

    @Test
    fun `codigo invalido e recusado`() {
        assertNull(LinkCode.decode("BG1:nao-e-um-uuid"))
        assertNull(LinkCode.decode(""))
        assertNull(LinkCode.decode("https://golpe.example"))
    }
}
