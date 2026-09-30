package com.example.antifraudagent.calls

import android.content.Context
import android.util.Log
import com.example.antifraudagent.data.device.DeviceIdentityProvider
import com.example.antifraudagent.data.local.call.CallTranscript
import com.example.antifraudagent.data.local.call.CallTranscriptSyncStatus
import com.example.antifraudagent.data.local.database.AppDatabase
import com.example.antifraudagent.data.local.entity.MessageSource
import com.example.antifraudagent.data.remote.FraudAnalysisResult
import com.example.antifraudagent.data.remote.FraudApiClient
import com.example.antifraudagent.data.remote.FraudApiHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * Historico local das ligacoes + envio ao backend (source=call).
 *
 * Cada POST /detect grava um registro no DynamoDB, entao este repositorio nunca reenvia algo
 * que pode ter chegado ao servidor: so reenvia quando a conexao nem foi estabelecida.
 */
class CallTranscriptRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).callTranscriptDao()
    private val deviceId = DeviceIdentityProvider.getDeviceId(appContext)
    private val apiClient = FraudApiClient()

    fun observe(): Flow<List<CallTranscript>> = dao.observeLatest()

    suspend fun save(entry: CallTranscript) = withContext(Dispatchers.IO) { dao.upsert(entry) }

    /** Analise no meio da ligacao. Devolve null se o servidor nao respondeu. */
    suspend fun analyzeCheckpoint(transcript: String): FraudAnalysisResult? = withContext(Dispatchers.IO) {
        try {
            apiClient.detect(deviceId = deviceId, messageContent = transcript, source = MessageSource.CALL)
        } catch (error: Exception) {
            Log.w(TAG, "Checkpoint da ligacao falhou", error)
            null
        }
    }

    /**
     * Grava o registro final. Com [sendText], faz a analise final no servidor; sem ele (ligacao
     * curta, envio pausado ou checkpoint recente), mantem a ultima analise ja conhecida.
     */
    suspend fun finalize(entry: CallTranscript, sendText: String?): CallTranscript = withContext(Dispatchers.IO) {
        if (sendText.isNullOrBlank()) {
            dao.upsert(entry)
            return@withContext entry
        }
        // Durante o envio o registro NAO fica PENDING: se o processo morrer no meio, o envio e
        // incerto (pode ter gravado no servidor) e nao deve ser repetido.
        dao.upsert(entry.copy(syncStatus = CallTranscriptSyncStatus.FAILED, syncError = SENDING_NOTE))
        val updated = sendFinal(entry, sendText)
        dao.upsert(updated)
        updated
    }

    /** Reenvia so as ligacoes cuja analise final nunca chegou ao servidor. */
    suspend fun retryPending() = withContext(Dispatchers.IO) {
        if (!retryMutex.tryLock()) return@withContext
        try {
            dao.pending().forEach { entry ->
                if (entry.content.isBlank()) return@forEach
                dao.upsert(sendFinal(entry, windowForFinal(entry.content)))
            }
        } finally {
            retryMutex.unlock()
        }
    }

    private suspend fun sendFinal(entry: CallTranscript, text: String): CallTranscript {
        return try {
            val result = apiClient.detect(deviceId = deviceId, messageContent = text, source = MessageSource.CALL)
            entry.withServerResult(result, keepAlert = true).copy(
                syncStatus = if (result.dbSynced) CallTranscriptSyncStatus.ANALYZED else CallTranscriptSyncStatus.FAILED,
                syncError = if (result.dbSynced) null else "Servidor não confirmou a gravação"
            )
        } catch (error: Exception) {
            val neverReached = error is ConnectException || error is UnknownHostException
            val permanent = error is FraudApiHttpException && error.isPermanent
            entry.copy(
                syncStatus = if (neverReached && !permanent) CallTranscriptSyncStatus.PENDING else CallTranscriptSyncStatus.FAILED,
                syncError = if (neverReached) "Sem conexão com o servidor" else "Falha ao enviar a análise"
            )
        }
    }

    companion object {
        private const val TAG = "CallTranscriptRepo"
        private val retryMutex = Mutex()

        const val SENDING_NOTE = "Enviando a análise ao servidor"

        /** Limite do contrato /detect e da janela de ~30s do API Gateway. */
        private const val FINAL_HEAD = 2_000
        private const val FINAL_TAIL = 9_500
        private const val CHECKPOINT_HEAD = 1_500
        private const val CHECKPOINT_TAIL = 4_500

        fun windowForFinal(text: String) = window(text, FINAL_HEAD, FINAL_TAIL)
        fun windowForCheckpoint(text: String) = window(text, CHECKPOINT_HEAD, CHECKPOINT_TAIL)

        private fun window(text: String, head: Int, tail: Int): String {
            val clean = text.trim()
            if (clean.length <= head + tail) return clean
            return clean.take(head) + "\n[...]\n" + clean.takeLast(tail)
        }
    }
}

/** Aplica o resultado do servidor no registro; o alerta local ja emitido nunca e desfeito. */
fun CallTranscript.withServerResult(result: FraudAnalysisResult, keepAlert: Boolean): CallTranscript {
    val serverSaysFraud = result.isFraud || result.hybrid?.status?.let { !it.equals("SEGURO", ignoreCase = true) } == true
    val keepLocalVerdict = keepAlert && isFraud == true && !serverSaysFraud && !verdict.isNullOrBlank()
    return copy(
        riskScore = maxOf(riskScore ?: 0f, result.score),
        isFraud = (isFraud == true && keepAlert) || serverSaysFraud,
        explanation = result.explanation,
        verdict = if (keepLocalVerdict) verdict else result.verdict.ifBlank { verdict },
        reasoning = result.reasoning.ifBlank { reasoning },
        indicators = result.indicators.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: indicators
    )
}
