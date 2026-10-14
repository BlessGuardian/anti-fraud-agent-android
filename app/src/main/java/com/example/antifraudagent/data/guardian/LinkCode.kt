package com.example.antifraudagent.data.guardian

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Codigo de vinculo da pessoa protegida: o mesmo `user_id` que o backend grava nos logs.
 *
 * O backend deriva `user_id = uuid5(NAMESPACE_OID, device_id)`; o app calcula igual, entao o codigo
 * existe mesmo sem conta. Formato do codigo (texto e QR): `BG1:<user_id>`.
 */
object LinkCode {
    private const val PREFIX = "BG1:"

    /** uuid.NAMESPACE_OID do Python. */
    private val NAMESPACE_OID: UUID = UUID.fromString("6ba7b812-9dad-11d1-80b4-00c04fd430c8")

    fun userIdForDevice(deviceId: String): String = uuid5(NAMESPACE_OID, deviceId).toString()

    fun encode(userId: String): String = "$PREFIX$userId"

    /** Aceita `BG1:<uuid>` ou so o uuid (colado de uma mensagem). Devolve o user_id ou null. */
    fun decode(raw: String): String? {
        val text = raw.trim().removePrefix(PREFIX).trim()
        return try {
            UUID.fromString(text).toString().takeIf { it.length == 36 }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** UUID versao 5 (SHA-1), igual ao `uuid.uuid5` do Python. */
    fun uuid5(namespace: UUID, name: String): UUID {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array())
        val hash = digest.digest(name.toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()   // versao 5
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()   // variante RFC 4122
        val buffer = ByteBuffer.wrap(hash, 0, 16)
        return UUID(buffer.long, buffer.long)
    }
}
