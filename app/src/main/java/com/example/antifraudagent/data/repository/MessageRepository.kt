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
import com.example.antifraudagent.data.remote.FraudApiClient
import com.example.antifraudagent.data.remote.FraudAnalysisResult
import com.example.antifraudagent.data.remote.RemoteFraudLog
import com.example.antifraudagent.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class MessageRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).analyzedMessageDao()
    private val apiClient = FraudApiClient()
    private val deviceId = DeviceIdentityProvider.getDeviceId(appContext)
    private val settings = SettingsRepository.getInstance(appContext)

    companion object {
        private const val TAG = "MessageRepository"
        private const val MAX_PENDING_MESSAGES = 100
        private const val MIN_MESSAGE_LENGTH = 4

        const val MIN_SCORE_TO_SAVE = 0.4f
    }

    /**
     * Ponto de entrada dos servicos de captura.
     *
     * Com internet, envia direto ao servidor, que persiste o registro no historico oficial.
     * Sem internet, salva no Room apenas como fila PENDING de envio.
     */
    suspend fun saveIfSuspicious(
        sender: String,
        content: String,
        packageName: String,
        layer1Score: Float
    ) = withContext(Dispatchers.IO) {
        if (!settings.isCaptureEnabled()) {
            Log.d(TAG, "Envio pausado pelo usuario; mensagem descartada antes de Room/HTTP")
            return@withContext
        }

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

    suspend fun getConfirmedFrauds(): List<RemoteFraudLog> =
        withContext(Dispatchers.IO) { apiClient.getLogs(deviceId = deviceId) }

    suspend fun analyzeManualMessage(content: String): FraudAnalysisResult =
        withContext(Dispatchers.IO) {
            if (!settings.isCaptureEnabled()) {
                throw IllegalStateException("Envio pausado em Perfil. Reative para analisar mensagens.")
            }

            val trimmedContent = content.trim()
            if (trimmedContent.length < MIN_MESSAGE_LENGTH) {
                throw IllegalArgumentException("Digite uma mensagem com pelo menos $MIN_MESSAGE_LENGTH caracteres.")
            }

            val result = apiClient.detect(
                deviceId = deviceId,
                messageContent = trimmedContent,
                source = MessageSource.MANUAL
            )

            if (!result.dbSynced) {
                throw IOException("Servidor analisou a mensagem, mas nao confirmou gravacao no historico oficial")
            }

            result
        }

    suspend fun getPendingMessages(): List<AnalyzedMessage> =
        withContext(Dispatchers.IO) { dao.getAllPending() }

    suspend fun processPendingMessages() = withContext(Dispatchers.IO) {
        if (!settings.isCaptureEnabled()) {
            Log.d(TAG, "Envio pausado pelo usuario; fila PENDING nao sera processada")
            return@withContext
        }
        if (!isOnline()) return@withContext
        processPendingMessagesInternal()
    }

    private suspend fun processPendingMessagesInternal() {
        val pendingMessages = dao.getAllPending()
        if (pendingMessages.isEmpty()) return

        for (pending in pendingMessages) {
            try {
                analyzeAndClearPending(pending)
            } catch (e: Exception) {
                Log.w(TAG, "Interrompendo fila PENDING apos falha no id=${pending.id}", e)
                break
            }
        }
    }

    private suspend fun analyzeAndPersist(message: AnalyzedMessage) {
        val result = apiClient.detect(
            deviceId = deviceId,
            messageContent = message.content,
            source = message.source
        )

        if (!result.dbSynced) {
            throw IOException("Servidor analisou a mensagem, mas nao confirmou gravacao no historico oficial")
        }

        if (result.isFraud) {
            Log.d(TAG, "Fraude detectada pelo servidor | score=${result.score} | dbSynced=${result.dbSynced}")
        } else {
            Log.d(TAG, "Servidor descartou mensagem online")
        }
    }

    private suspend fun analyzeAndClearPending(message: AnalyzedMessage) {
        analyzeAndPersist(message)
        dao.delete(message)
        Log.d(TAG, "PENDING id=${message.id} enviado ao servidor e removido do Room")
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
