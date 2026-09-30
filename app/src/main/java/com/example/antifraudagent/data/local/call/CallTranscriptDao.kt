package com.example.antifraudagent.data.local.call

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CallTranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transcript: CallTranscript)

    @Query("SELECT * FROM call_transcripts ORDER BY endedAt DESC LIMIT :limit")
    suspend fun latest(limit: Int = 100): List<CallTranscript>

    @Query("SELECT * FROM call_transcripts WHERE syncStatus IN ('PENDING', 'FAILED') ORDER BY endedAt ASC")
    suspend fun pending(): List<CallTranscript>

    @Query("UPDATE call_transcripts SET riskScore = :riskScore, isFraud = :isFraud, explanation = :explanation, syncStatus = :syncStatus, syncError = :syncError WHERE sessionId = :sessionId")
    suspend fun updateAnalysis(
        sessionId: String,
        riskScore: Float?,
        isFraud: Boolean?,
        explanation: String?,
        syncStatus: CallTranscriptSyncStatus,
        syncError: String?
    )
}
