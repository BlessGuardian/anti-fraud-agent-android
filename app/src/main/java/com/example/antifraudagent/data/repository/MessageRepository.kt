package com.example.antifraudagent.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.antifraudagent.data.device.DeviceIdentityProvider
import com.example.antifraudagent.data.local.database.AppDatabase
import com.example.antifraudagent.data.local.entity.AnalyzedMessage
import com.example.antifraudagent.data.local.entity.MessageSource
import com.example.antifraudagent.data.local.entity.MessageStatus
import com.example.antifraudagent.data.remote.FraudAnalysisResult
import com.example.antifraudagent.data.remote.FraudApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MessageRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).analyzedMessageDao()
    private val apiClient = FraudApiClient()
    private val userId = DeviceIdentityProvider.getUserId(appContext)

    companion object {
        private const val TAG = "MessageRepository"
        private const val MAX_PENDING_MESSAGES = 100
        private const val MIN_MESSAGE_LENGTH = 4

        const val MIN_SCORE_TO_SAVE = 0.4f
    }

    /**
     * Ponto de entrada dos servicos de captura.
     *
     * Com internet, envia direto ao servidor e salva localmente apenas fraudes confirmadas.
     * Sem internet, salva como PENDING para reprocessar quando uma nova captura ocorrer online.
     */
    suspend fun saveIfSuspicious(
        sender: String,
        content: String,
        packageName: String,
        layer1Score: Float
    ) = withContext(Dispatchers.IO) {
        val trimmedContent = content.trim()
        if (!passesMinimumQuality(trimmedContent, layer1Score)) return@withContext

        val source = MessageSource.fromPackage(packageName)
        val message = AnalyzedMessage(
            sender = sender.ifBlank { source.name },
            content = trimmedContent,
            source = source,
            layer1Score = layer1Score,
            status = MessageStatus.PENDING
        )

        if (!isOnline()) {
            enqueuePending(message)
            return@withContext
        }

        processPendingMessagesInternal()

        try {
            analyzeAndPersist(message)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao enviar mensagem atual; salvando como PENDING", e)
            enqueuePending(message)
        }
    }

    suspend fun getConfirmedFrauds(): List<AnalyzedMessage> =
        withContext(Dispatchers.IO) { dao.getAllConfirmedFrauds() }

    suspend fun getPendingMessages(): List<AnalyzedMessage> =
        withContext(Dispatchers.IO) { dao.getAllPending() }

    suspend fun processPendingMessages() = withContext(Dispatchers.IO) {
        if (!isOnline()) return@withContext
        processPendingMessagesInternal()
    }

    suspend fun updateWithServerResult(
        id: Long,
        layer2Score: Float,
        riskScore: Float,
        fraudType: String,
        explanation: String,
        isConfirmed: Boolean
    ) = withContext(Dispatchers.IO) {
        val newStatus = if (isConfirmed) MessageStatus.CONFIRMED_FRAUD else MessageStatus.DISMISSED
        dao.updateWithServerResult(id, layer2Score, riskScore, fraudType, explanation, newStatus)
        Log.d(TAG, "Registro id=$id atualizado -> status=$newStatus | riskScore=$riskScore")
    }

    suspend fun cleanupDismissed() = withContext(Dispatchers.IO) {
        dao.deleteDismissed()
        Log.d(TAG, "Registros DISMISSED removidos do banco")
    }

    private suspend fun processPendingMessagesInternal() {
        val pendingMessages = dao.getAllPending()
        if (pendingMessages.isEmpty()) return

        for (pending in pendingMessages) {
            try {
                analyzeAndPersist(pending)
            } catch (e: Exception) {
                Log.w(TAG, "Interrompendo fila PENDING apos falha no id=${pending.id}", e)
                break
            }
        }
    }

    private suspend fun analyzeAndPersist(message: AnalyzedMessage) {
        val result = apiClient.detect(
            userId = userId,
            messageContent = message.content,
            source = message.source
        )

        if (result.isFraud) {
            persistConfirmedFraud(message, result)
        } else if (message.id != 0L) {
            dao.delete(message)
            Log.d(TAG, "Servidor descartou id=${message.id}; registro removido")
        } else {
            Log.d(TAG, "Servidor descartou mensagem online; nada salvo localmente")
        }
    }

    private suspend fun persistConfirmedFraud(
        message: AnalyzedMessage,
        result: FraudAnalysisResult
    ) {
        if (message.id == 0L) {
            val confirmed = message.copy(
                layer2Score = result.score,
                riskScore = result.score,
                fraudType = result.category,
                explanation = result.explanation,
                status = MessageStatus.CONFIRMED_FRAUD
            )
            val id = dao.insert(confirmed)
            Log.d(TAG, "Fraude confirmada salva com id=$id | score=${result.score} | dbSynced=${result.dbSynced}")
        } else {
            updateWithServerResult(
                id = message.id,
                layer2Score = result.score,
                riskScore = result.score,
                fraudType = result.category,
                explanation = result.explanation,
                isConfirmed = true
            )
        }
    }

    private suspend fun enqueuePending(message: AnalyzedMessage) {
        val pendingCount = dao.countPending()
        if (pendingCount >= MAX_PENDING_MESSAGES) {
            val lowestScore = dao.getPendingWithLowestScore()
            if (lowestScore != null) {
                if (message.layer1Score > lowestScore.layer1Score) {
                    dao.delete(lowestScore)
                    Log.d(TAG, "Fila cheia: removido id=${lowestScore.id} para salvar nova mensagem")
                } else {
                    Log.d(TAG, "Fila cheia: nova mensagem descartada por menor prioridade")
                    return
                }
            }
        }

        val id = dao.insert(message.copy(status = MessageStatus.PENDING))
        Log.d(TAG, "Mensagem salva como PENDING id=$id | source=${message.source}")
    }

    private fun passesMinimumQuality(content: String, layer1Score: Float): Boolean {
        if (content.length < MIN_MESSAGE_LENGTH) {
            Log.d(TAG, "Mensagem curta demais; descartada")
            return false
        }

        if (layer1Score < MIN_SCORE_TO_SAVE) {
            Log.d(TAG, "Score $layer1Score < $MIN_SCORE_TO_SAVE; descartado em memoria")
            return false
        }

        return true
    }

    private fun isOnline(): Boolean {
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
