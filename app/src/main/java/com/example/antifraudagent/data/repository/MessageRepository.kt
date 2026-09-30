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
import com.example.antifraudagent.data.local.preprocessing.LocalMessagePreprocessor
import com.example.antifraudagent.data.remote.FraudApiClient
import com.example.antifraudagent.data.remote.FraudAnalysisResult
import com.example.antifraudagent.data.remote.FraudApiHttpException
import com.example.antifraudagent.data.remote.RemoteFraudLog
import com.example.antifraudagent.data.settings.SettingsRepository
import com.example.antifraudagent.calls.SuspiciousMessageAlert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
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

        // Compartilhados entre as instancias (Activity, servicos de captura, SmsReceiver) do
        // mesmo processo: garante um unico envio da fila por vez, evitando registros duplicados.
        private val pendingQueueMutex = Mutex()

        /** A fila roda no escopo do processo: sair da tela nao pode cancelar o envio. */
        private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _syncState = MutableStateFlow(SyncState.IDLE)
        val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    }

    enum class SyncState {
        /** Nada em andamento; a ultima tentativa (se houve) funcionou. */
        IDLE,
        /** Enviando a fila PENDING ao servidor. */
        SYNCING,
        /** Ha internet, mas o servidor falhou ou nao respondeu. */
        SERVER_UNAVAILABLE,
        /** Aparelho sem internet. */
        OFFLINE
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

        val preprocessed = LocalMessagePreprocessor.process(
            content = content,
            sender = sender,
            packageName = packageName
        )
        val normalizedContent = when (preprocessed) {
            is LocalMessagePreprocessor.PreprocessResult.Rejected -> {
                Log.d(TAG, "Captura descartada pelo preprocessor: ${preprocessed.reason}")
                return@withContext
            }
            is LocalMessagePreprocessor.PreprocessResult.Accepted -> preprocessed.normalizedText
        }

        if (!passesMinimumQuality(normalizedContent, layer1Score)) return@withContext

        val source = MessageSource.fromPackage(packageName)
        val message = AnalyzedMessage(
            sender = sender.ifBlank { source.name },
            content = normalizedContent,
            source = source,
            layer1Score = layer1Score,
            status = MessageStatus.PENDING
        )

        if (!isOnline()) {
            _syncState.value = SyncState.OFFLINE
            enqueuePending(message)
            return@withContext
        }

        // A mensagem atual vai primeiro: com a fila cheia, esvaziar antes atrasaria o alerta
        // em minutos (cada analise leva ~10s no servidor).
        try {
            analyzeAndPersist(message)
        } catch (e: FraudApiHttpException) {
            if (e.isPermanent) {
                Log.w(TAG, "Servidor rejeitou a mensagem atual (HTTP ${e.code}); descartada", e)
            } else {
                Log.w(TAG, "Falha ao enviar mensagem atual; salvando como PENDING", e)
                _syncState.value = SyncState.SERVER_UNAVAILABLE
                enqueuePending(message)
            }
            return@withContext
        } catch (e: CancellationException) {
            // Cancelado (nao e falha do servidor): guarda a mensagem para nao perde-la.
            withContext(NonCancellable) { enqueuePending(message) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao enviar mensagem atual; salvando como PENDING", e)
            _syncState.value = SyncState.SERVER_UNAVAILABLE
            enqueuePending(message)
            return@withContext
        }

        processPendingMessagesInternal()
    }

    /**
     * Historico de MENSAGENS. As analises de ligacao (source=call, varias por ligacao durante a
     * conversa) ficam na aba Ligacoes, com historico proprio.
     */
    suspend fun getConfirmedFrauds(): List<RemoteFraudLog> =
        withContext(Dispatchers.IO) {
            apiClient.getLogs(deviceId = deviceId, limit = 200)
                .filterNot { it.source.equals("call", ignoreCase = true) }
        }

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

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    /** Envia a fila em segundo plano, independente da tela que pediu. */
    fun drainQueueInBackground(): Job = queueScope.launch { processPendingMessages() }

    suspend fun processPendingMessages() = withContext(Dispatchers.IO) {
        if (!settings.isCaptureEnabled()) {
            Log.d(TAG, "Envio pausado pelo usuario; fila PENDING nao sera processada")
            return@withContext
        }
        if (!isOnline()) {
            _syncState.value = SyncState.OFFLINE
            return@withContext
        }
        processPendingMessagesInternal()
    }

    private suspend fun processPendingMessagesInternal() {
        // Se outra chamada ja esta enviando a fila, nao envia de novo em paralelo.
        if (!pendingQueueMutex.tryLock()) return
        try {
            val pendingMessages = dao.getAllPending()
            if (pendingMessages.isEmpty()) {
                _syncState.value = SyncState.IDLE
                return
            }

            _syncState.value = SyncState.SYNCING
            for (pending in pendingMessages) {
                try {
                    analyzeAndClearPending(pending)
                } catch (e: FraudApiHttpException) {
                    if (e.isPermanent) {
                        // Reenviar nunca vai funcionar; manter travaria a fila para sempre.
                        dao.delete(pending)
                        Log.w(TAG, "PENDING id=${pending.id} rejeitado (HTTP ${e.code}); removido da fila", e)
                        continue
                    }
                    Log.w(TAG, "Interrompendo fila PENDING apos falha no id=${pending.id}", e)
                    _syncState.value = SyncState.SERVER_UNAVAILABLE
                    return
                } catch (e: CancellationException) {
                    // Envio cancelado (ex.: tela fechada) nao significa servidor indisponivel.
                    _syncState.value = SyncState.IDLE
                    throw e
                } catch (e: Exception) {
                    // Falha transitoria (timeout, rede, 5xx): o servidor provavelmente esta fora;
                    // para aqui e tenta de novo no proximo gatilho, sem martelar a API.
                    Log.w(TAG, "Interrompendo fila PENDING apos falha no id=${pending.id}", e)
                    _syncState.value = if (isOnline()) SyncState.SERVER_UNAVAILABLE else SyncState.OFFLINE
                    return
                }
            }
            _syncState.value = SyncState.IDLE
        } finally {
            pendingQueueMutex.unlock()
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
            SuspiciousMessageAlert.show(
                context = appContext,
                result = result,
                content = message.content,
                sourceName = message.source.name,
                capturedAt = message.capturedAt
            )
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
