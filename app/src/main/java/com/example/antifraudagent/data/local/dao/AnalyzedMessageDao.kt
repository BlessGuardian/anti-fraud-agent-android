package com.example.antifraudagent.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.antifraudagent.data.local.entity.AnalyzedMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalyzedMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AnalyzedMessage): Long

    @Query("SELECT * FROM analyzed_messages WHERE status = 'PENDING' ORDER BY capturedAt ASC")
    suspend fun getAllPending(): List<AnalyzedMessage>

    @Query("SELECT COUNT(*) FROM analyzed_messages WHERE status = 'PENDING'")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM analyzed_messages WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM analyzed_messages WHERE status = 'PENDING' ORDER BY layer1Score ASC LIMIT 1")
    suspend fun getPendingWithLowestScore(): AnalyzedMessage?

    @Delete
    suspend fun delete(message: AnalyzedMessage)

    @Query("DELETE FROM analyzed_messages WHERE status = 'PENDING'")
    suspend fun deleteAllPending(): Int
}
