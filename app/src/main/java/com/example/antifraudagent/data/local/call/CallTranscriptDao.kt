package com.example.antifraudagent.data.local.call

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallTranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transcript: CallTranscript)

    @Query("SELECT * FROM call_transcripts ORDER BY startedAt DESC LIMIT :limit")
    fun observeLatest(limit: Int = 100): Flow<List<CallTranscript>>

    @Query("SELECT * FROM call_transcripts WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): CallTranscript?

    @Query("SELECT * FROM call_transcripts WHERE syncStatus = 'PENDING' ORDER BY endedAt ASC")
    suspend fun pending(): List<CallTranscript>
}
