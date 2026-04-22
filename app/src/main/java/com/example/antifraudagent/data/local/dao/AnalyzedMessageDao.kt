package com.example.antifraudagent.data.local.dao

import androidx.room.*
import com.example.antifraudagent.data.local.entity.AnalyzedMessage
import com.example.antifraudagent.data.local.entity.MessageStatus

@Dao
interface AnalyzedMessageDao {

    // -------------------------------------------------------------------------
    // Inserção
    // -------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AnalyzedMessage): Long

    // -------------------------------------------------------------------------
    // Consultas — Histórico (somente golpes confirmados)
    // -------------------------------------------------------------------------

    /** Retorna todos os golpes confirmados, do mais recente ao mais antigo. */
    @Query("SELECT * FROM analyzed_messages WHERE status = 'CONFIRMED_FRAUD' ORDER BY capturedAt DESC")
    suspend fun getAllConfirmedFrauds(): List<AnalyzedMessage>

    // -------------------------------------------------------------------------
    // Consultas — Fila de pendentes (para envio ao servidor quando há internet)
    // -------------------------------------------------------------------------

    /** Retorna todos os registros ainda aguardando análise remota. */
    @Query("SELECT * FROM analyzed_messages WHERE status = 'PENDING' ORDER BY capturedAt ASC")
    suspend fun getAllPending(): List<AnalyzedMessage>

    /** Conta quantos registros PENDING existem no momento. */
    @Query("SELECT COUNT(*) FROM analyzed_messages WHERE status = 'PENDING'")
    suspend fun countPending(): Int

    /**
     * Retorna o registro PENDING com menor layer1Score.
     * Usado para descartar o menos suspeito quando a fila atinge o limite de 100.
     */
    @Query("SELECT * FROM analyzed_messages WHERE status = 'PENDING' ORDER BY layer1Score ASC LIMIT 1")
    suspend fun getPendingWithLowestScore(): AnalyzedMessage?

    // -------------------------------------------------------------------------
    // Atualização — resultado da análise remota
    // -------------------------------------------------------------------------

    /**
     * Atualiza um registro PENDING com o resultado retornado pelo servidor Python.
     * Chamado no Chat 6 após receber a resposta da FastAPI.
     */
    @Query("""
        UPDATE analyzed_messages
        SET layer2Score = :layer2Score,
            riskScore   = :riskScore,
            fraudType   = :fraudType,
            explanation = :explanation,
            status      = :status
        WHERE id = :id
    """)
    suspend fun updateWithServerResult(
        id          : Long,
        layer2Score : Float,
        riskScore   : Float,
        fraudType   : String,
        explanation : String,
        status      : MessageStatus
    )

    // -------------------------------------------------------------------------
    // Deleção
    // -------------------------------------------------------------------------

    @Delete
    suspend fun delete(message: AnalyzedMessage)

    /** Remove todos os registros com status DISMISSED (descartados pelo servidor). */
    @Query("DELETE FROM analyzed_messages WHERE status = 'DISMISSED'")
    suspend fun deleteDismissed()
}
