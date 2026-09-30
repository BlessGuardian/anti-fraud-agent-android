package com.example.antifraudagent.calls

import android.content.Context
import com.example.antifraudagent.data.local.call.CallTranscript
import com.example.antifraudagent.data.local.call.CallTranscriptSyncStatus
import com.example.antifraudagent.data.local.database.AppDatabase
import com.example.antifraudagent.data.local.entity.MessageSource
import com.example.antifraudagent.data.remote.FraudApiClient
import com.example.antifraudagent.data.remote.FraudAnalysisResult
import com.example.antifraudagent.data.device.DeviceIdentityProvider

class CallTranscriptRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).callTranscriptDao()
    private val deviceId = DeviceIdentityProvider.getDeviceId(appContext)
    private val apiClient = FraudApiClient()

    suspend fun list(): List<CallTranscript> = dao.latest()

    suspend fun finalize(
        sessionId: String,
        transcript: String,
        startedAt: Long,
        endedAt: Long
    ): CallTranscript {
        val entry = CallTranscript(
            sessionId = sessionId,
            content = transcript,
            startedAt = startedAt,
            endedAt = endedAt
        )
        dao.insert(entry)
        return analyze(entry)
    }

    suspend fun retryPending() {
        dao.pending().forEach { analyze(it) }
    }

    private suspend fun analyze(entry: CallTranscript): CallTranscript {
        return try {
            val result = apiClient.detect(
                deviceId = deviceId,
                messageContent = entry.content,
                source = MessageSource.CALL
            )
            val status = if (result.dbSynced) {
                CallTranscriptSyncStatus.ANALYZED
            } else {
                CallTranscriptSyncStatus.PENDING
            }
            dao.updateAnalysis(
                sessionId = entry.sessionId,
                riskScore = result.score,
                isFraud = result.isFraud,
                explanation = result.explanation,
                syncStatus = status,
                syncError = if (result.dbSynced) null else "Servidor nao confirmou a gravacao"
            )
            entry.copy(
                riskScore = result.score,
                isFraud = result.isFraud,
                explanation = result.explanation,
                syncStatus = status,
                syncError = if (result.dbSynced) null else "Servidor nao confirmou a gravacao"
            )
        } catch (error: Exception) {
            dao.updateAnalysis(
                sessionId = entry.sessionId,
                riskScore = null,
                isFraud = null,
                explanation = null,
                syncStatus = CallTranscriptSyncStatus.FAILED,
                syncError = error.message ?: "Falha ao enviar transcricao"
            )
            entry.copy(
                syncStatus = CallTranscriptSyncStatus.FAILED,
                syncError = error.message ?: "Falha ao enviar transcricao"
            )
        }
    }
}
