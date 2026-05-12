package com.example.antifraudagent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Mensagem mantida temporariamente no aparelho.
 *
 * O Room funciona como fila offline: mensagens capturadas sem internet ficam
 * PENDING ate serem enviadas ao servidor Python. A fonte oficial do historico
 * e o banco em nuvem, consultado via API.
 */
@Entity(tableName = "analyzed_messages")
data class AnalyzedMessage(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sender: String,
    val content: String,
    val source: MessageSource,
    val layer1Score: Float,

    // Campos mantidos por compatibilidade com o schema local anterior.
    val layer2Score: Float? = null,
    val riskScore: Float? = null,
    val fraudType: String? = null,
    val explanation: String? = null,

    val status: MessageStatus,
    val capturedAt: Long = System.currentTimeMillis()
)

enum class MessageSource {
    WHATSAPP,
    TELEGRAM,
    INSTAGRAM,
    SMS,
    UNKNOWN;

    companion object {
        fun fromPackage(packageName: String): MessageSource = when (packageName.lowercase()) {
            "com.whatsapp" -> WHATSAPP
            "org.telegram.messenger" -> TELEGRAM
            "com.instagram.android" -> INSTAGRAM
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "sms" -> SMS
            else -> UNKNOWN
        }
    }
}

enum class MessageStatus {
    PENDING,

    // Mantidos por compatibilidade com versoes antigas do schema local.
    CONFIRMED_FRAUD,
    DISMISSED
}
