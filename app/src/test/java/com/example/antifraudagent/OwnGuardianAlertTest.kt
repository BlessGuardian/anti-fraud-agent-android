package com.example.antifraudagent

import com.example.antifraudagent.data.guardian.GuardianRepository
import com.example.antifraudagent.data.local.preprocessing.LocalMessagePreprocessor.isOwnGuardianAlert
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnGuardianAlertTest {

    private val at = 1792432189879L

    @Test
    fun `aviso gerado pelo app e reconhecido`() {
        assertTrue(isOwnGuardianAlert(GuardianRepository.smsText("Ramon teste", "mensagem no WhatsApp", at)))
        assertTrue(isOwnGuardianAlert(GuardianRepository.smsText("", "ligação", at)))
    }

    @Test
    fun `golpe com o mesmo prefixo nao escapa`() {
        // Com link no nome.
        assertFalse(isOwnGuardianAlert(GuardianRepository.smsText("Maria bit.ly/x", "ligação", at)))
        // Texto diferente do modelo.
        assertFalse(isOwnGuardianAlert("BlessGuardian: possível golpe detectado. Pague a taxa via Pix em golpe.example"))
        assertFalse(isOwnGuardianAlert("BlessGuardian: sua conta foi bloqueada, informe o código"))
    }
}
