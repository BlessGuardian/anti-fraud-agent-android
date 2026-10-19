package com.example.antifraudagent.data.guardian

import org.json.JSONObject
import java.util.Base64

/**
 * QR de vinculo do RESPONSAVEL: carrega nome, telefone e e-mail dele. A pessoa protegida le esse QR
 * e passa a avisar o responsavel (SMS na hora; e-mail quando o backend enviar).
 *
 * Formato: `BG2:<base64url(JSON {"n","p","e"})>`. O `BG1:<user_id>` antigo (responsavel consultando
 * o /logs da pessoa) deixou de funcionar quando o /logs passou a exigir token (18/10/2026).
 */
object LinkCode {
    private const val PREFIX = "BG2:"

    fun encode(contact: GuardianContact): String {
        val json = JSONObject().put("n", contact.name).put("p", contact.phone)
        contact.email?.let { json.put("e", it) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    /** Devolve o contato ou null se o texto nao for um codigo BG2 valido (com telefone). */
    fun decode(raw: String): GuardianContact? {
        val text = raw.trim()
        if (!text.startsWith(PREFIX)) return null
        return try {
            val json = JSONObject(String(Base64.getUrlDecoder().decode(text.removePrefix(PREFIX)), Charsets.UTF_8))
            val phone = json.optString("p").trim()
            if (phone.count { it.isDigit() } < MIN_PHONE_DIGITS) return null
            GuardianContact(
                name = json.optString("n").trim().ifBlank { "Responsável" },
                phone = phone,
                email = json.optString("e").trim().takeIf { it.contains('@') }
            )
        } catch (_: Exception) {
            null
        }
    }

    const val MIN_PHONE_DIGITS = 10
}

data class GuardianContact(
    val name: String,
    /** Com DDD; e para onde vai o SMS. */
    val phone: String,
    /** Guardado para o e-mail de alerta, quando o backend enviar (ainda nao envia). */
    val email: String?
)
