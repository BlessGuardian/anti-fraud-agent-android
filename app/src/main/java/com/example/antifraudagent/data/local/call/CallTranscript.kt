package com.example.antifraudagent.data.local.call

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Uma ligacao recebida e atendida com a protecao ativa. Nenhum audio e salvo: somente o texto
 * transcrito, o veredito e o trecho que disparou o alerta ficam no aparelho.
 */
@Entity(tableName = "call_transcripts")
data class CallTranscript(
    @PrimaryKey val sessionId: String,
    /** Transcricao completa da ligacao. */
    val content: String,
    /** Quando a ligacao foi atendida. */
    val startedAt: Long,
    val endedAt: Long,
    val riskScore: Float? = null,
    val isFraud: Boolean? = null,
    /** Veredito + indicadores numa linha (legado). */
    val explanation: String? = null,
    val syncStatus: CallTranscriptSyncStatus = CallTranscriptSyncStatus.PENDING,
    val syncError: String? = null,
    /** Numero de quem ligou, quando o filtro de chamadas do Android informou. */
    val callerNumber: String? = null,
    @ColumnInfo(defaultValue = "0") val speakerUsed: Boolean = false,
    /** veredito_curto do servidor. */
    val verdict: String? = null,
    /** raciocinio do LLM, quando o servidor devolveu. */
    val reasoning: String? = null,
    /** Indicadores do servidor, um por linha. */
    val indicators: String? = null,
    /** Sinais das regras locais, um por linha. */
    val localIndicators: String? = null,
    /** Trecho da conversa que levou ao alerta. */
    val alertExcerpt: String? = null,
    /** Momento do primeiro alerta (epoch ms). */
    val alertAtMillis: Long? = null,
    /** "local" (regras no aparelho) ou "servidor" (LLM). */
    val alertSource: String? = null,
    /** Problema de captura percebido na sessao (sem audio, sem viva-voz...). */
    val captureIssue: String? = null
) {
    val indicatorList: List<String> get() = indicators.splitLines()
    val localIndicatorList: List<String> get() = localIndicators.splitLines()
    val alerted: Boolean get() = alertAtMillis != null || isFraud == true
}

private fun String?.splitLines(): List<String> =
    this?.split('\n')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

enum class CallTranscriptSyncStatus {
    /** Analise final ainda nao chegou ao servidor (pode ser reenviada). */
    PENDING,
    ANALYZED,
    /** Envio incerto ou recusado; nao e reenviado automaticamente para evitar duplicatas. */
    FAILED
}
