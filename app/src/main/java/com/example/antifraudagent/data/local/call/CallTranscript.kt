package com.example.antifraudagent.data.local.call

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Registro local da transcricao final de uma chamada. O audio e temporario:
 * ele e removido quando a sessao termina e somente o texto fica disponivel
 * para revisao e envio ao backend.
 */
@Entity(tableName = "call_transcripts")
data class CallTranscript(
    @PrimaryKey val sessionId: String,
    val content: String,
    val startedAt: Long,
    val endedAt: Long,
    val riskScore: Float? = null,
    val isFraud: Boolean? = null,
    val explanation: String? = null,
    val syncStatus: CallTranscriptSyncStatus = CallTranscriptSyncStatus.PENDING,
    val syncError: String? = null
)

enum class CallTranscriptSyncStatus {
    PENDING,
    ANALYZED,
    FAILED
}
