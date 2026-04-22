package com.example.antifraudagent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Representa uma mensagem suspeita armazenada localmente.
 *
 * Regras de armazenamento (decididas no Chat 3):
 *  - Mensagens com score < 0.4 na Camada 1 são descartadas em memória — nunca chegam aqui.
 *  - Mensagens com score ≥ 0.4 são salvas com status [MessageStatus.PENDING].
 *  - Após análise do servidor Python, o status muda para:
 *      • [MessageStatus.CONFIRMED_FRAUD] → fica no histórico para sempre.
 *      • [MessageStatus.DISMISSED]       → deletado do banco.
 *  - A fila de PENDING tem limite máximo de 100 registros (ver [MessageRepository]).
 */
@Entity(tableName = "analyzed_messages")
data class AnalyzedMessage(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Remetente da mensagem (nome/número exibido na notificação ou acessibilidade). */
    val sender: String,

    /** Conteúdo da mensagem capturada. */
    val content: String,

    /** Canal de origem da mensagem. */
    val source: MessageSource,

    /** Score gerado pela heurística local (Camada 1). Sempre preenchido. */
    val layer1Score: Float,

    /**
     * Score retornado pelo servidor Python (Camada 2).
     * Null enquanto a análise remota ainda não foi realizada (status PENDING).
     */
    val layer2Score: Float? = null,

    /**
     * Score final combinado exibido ao usuário.
     * Null enquanto pendente, preenchido após resposta do servidor.
     */
    val riskScore: Float? = null,

    /**
     * Tipo de golpe identificado pelo servidor (ex: "golpe_pix", "falsa_central").
     * Null enquanto pendente.
     */
    val fraudType: String? = null,

    /**
     * Explicação gerada pelo algoritmo justificando a classificação.
     * Null enquanto pendente.
     */
    val explanation: String? = null,

    /** Estado atual deste registro no fluxo de análise. */
    val status: MessageStatus,

    /** Timestamp Unix (ms) de quando a mensagem foi capturada. */
    val capturedAt: Long = System.currentTimeMillis()
)

/**
 * Canal de origem da mensagem capturada.
 */
enum class MessageSource {
    WHATSAPP,
    TELEGRAM,
    INSTAGRAM,
    SMS,
    UNKNOWN;

    companion object {
        /** Converte o packageName do app para o enum correspondente. */
        fun fromPackage(packageName: String): MessageSource = when (packageName) {
            "com.whatsapp"                       -> WHATSAPP
            "org.telegram.messenger"             -> TELEGRAM
            "com.instagram.android"              -> INSTAGRAM
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging"      -> SMS
            else                                 -> UNKNOWN
        }
    }
}

/**
 * Estado do registro no fluxo de análise.
 */
enum class MessageStatus {
    /** Score ≥ 0.4 na Camada 1, aguardando análise do servidor Python. */
    PENDING,

    /** Servidor confirmou como golpe — permanece no histórico. */
    CONFIRMED_FRAUD,

    /** Servidor descartou — será deletado do banco. */
    DISMISSED
}
