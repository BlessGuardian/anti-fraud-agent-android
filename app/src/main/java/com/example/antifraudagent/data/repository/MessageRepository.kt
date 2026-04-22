package com.example.antifraudagent.data.repository

import android.content.Context
import android.util.Log
import com.example.antifraudagent.data.local.database.AppDatabase
import com.example.antifraudagent.data.local.entity.AnalyzedMessage
import com.example.antifraudagent.data.local.entity.MessageSource
import com.example.antifraudagent.data.local.entity.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Camada de repositório — ponto único de acesso ao banco local para todo o app.
 *
 * Encapsula o DAO e aplica as regras de negócio decididas no Chat 3:
 *  - Só salva mensagens com layer1Score ≥ 0.4.
 *  - A fila de PENDING tem limite máximo de [MAX_PENDING_MESSAGES].
 *  - Quando o limite é atingido, o registro PENDING com menor score é descartado
 *    para dar lugar ao novo (prioriza as suspeitas mais graves).
 */
class MessageRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).analyzedMessageDao()

    companion object {
        private const val TAG = "MessageRepository"

        /** Limite máximo de mensagens aguardando análise remota. ~50KB no SQLite. */
        private const val MAX_PENDING_MESSAGES = 100

        /** Score mínimo da Camada 1 para que uma mensagem seja salva. */
        const val MIN_SCORE_TO_SAVE = 0.4f
    }

    // -------------------------------------------------------------------------
    // Salvar mensagem capturada (chamado pelos serviços de captura)
    // -------------------------------------------------------------------------

    /**
     * Avalia se a mensagem deve ser salva e, em caso positivo, persiste no banco.
     *
     * @param sender     Remetente da mensagem.
     * @param content    Conteúdo da mensagem.
     * @param packageName Package do app de origem (convertido para [MessageSource]).
     * @param layer1Score Score calculado pela heurística local (Camada 1).
     *
     * Fluxo:
     *  1. Se layer1Score < 0.4 → descarta em memória, não salva nada.
     *  2. Se a fila de PENDING já tem 100 itens → descarta o de menor score.
     *  3. Salva a nova mensagem com status PENDING.
     */
    suspend fun saveIfSuspicious(
        sender      : String,
        content     : String,
        packageName : String,
        layer1Score : Float
    ) = withContext(Dispatchers.IO) {

        // Regra 1: score abaixo do limiar → descarta em memória
        if (layer1Score < MIN_SCORE_TO_SAVE) {
            Log.d(TAG, "Score $layer1Score < $MIN_SCORE_TO_SAVE — descartado em memória")
            return@withContext
        }

        // Regra 2: fila cheia → descarta o menos suspeito para abrir espaço
        val pendingCount = dao.countPending()
        if (pendingCount >= MAX_PENDING_MESSAGES) {
            val lowestScore = dao.getPendingWithLowestScore()
            if (lowestScore != null) {
                // Só descarta o existente se o novo for mais suspeito
                if (layer1Score > (lowestScore.layer1Score)) {
                    dao.delete(lowestScore)
                    Log.d(TAG, "Fila cheia: descartado registro id=${lowestScore.id} " +
                               "(score=${lowestScore.layer1Score}) para salvar novo (score=$layer1Score)")
                } else {
                    Log.d(TAG, "Fila cheia e novo score ($layer1Score) não supera o menor " +
                               "existente (${lowestScore.layer1Score}) — descartado")
                    return@withContext
                }
            }
        }

        // Regra 3: salva com status PENDING
        val message = AnalyzedMessage(
            sender      = sender,
            content     = content,
            source      = MessageSource.fromPackage(packageName),
            layer1Score = layer1Score,
            status      = MessageStatus.PENDING
        )
        val id = dao.insert(message)
        Log.d(TAG, "Mensagem salva com id=$id | source=${message.source} | score=$layer1Score")
    }

    // -------------------------------------------------------------------------
    // Consultas (usadas pela UI e pelo Chat 6)
    // -------------------------------------------------------------------------

    /** Retorna o histórico de golpes confirmados (para exibir ao usuário). */
    suspend fun getConfirmedFrauds(): List<AnalyzedMessage> =
        withContext(Dispatchers.IO) { dao.getAllConfirmedFrauds() }

    /** Retorna a fila de pendentes (para o Chat 6 enviar ao servidor quando houver internet). */
    suspend fun getPendingMessages(): List<AnalyzedMessage> =
        withContext(Dispatchers.IO) { dao.getAllPending() }

    // -------------------------------------------------------------------------
    // Atualização com resultado do servidor (chamado no Chat 6)
    // -------------------------------------------------------------------------

    /**
     * Atualiza um registro PENDING com o resultado retornado pela FastAPI.
     * Se confirmado como golpe → status CONFIRMED_FRAUD (fica no histórico).
     * Se descartado → status DISMISSED, depois limpa com [cleanupDismissed].
     */
    suspend fun updateWithServerResult(
        id          : Long,
        layer2Score : Float,
        riskScore   : Float,
        fraudType   : String,
        explanation : String,
        isConfirmed : Boolean
    ) = withContext(Dispatchers.IO) {
        val newStatus = if (isConfirmed) MessageStatus.CONFIRMED_FRAUD else MessageStatus.DISMISSED
        dao.updateWithServerResult(id, layer2Score, riskScore, fraudType, explanation, newStatus)
        Log.d(TAG, "Registro id=$id atualizado → status=$newStatus | riskScore=$riskScore")
    }

    /** Remove registros descartados pelo servidor para liberar espaço. */
    suspend fun cleanupDismissed() = withContext(Dispatchers.IO) {
        dao.deleteDismissed()
        Log.d(TAG, "Registros DISMISSED removidos do banco")
    }
}
