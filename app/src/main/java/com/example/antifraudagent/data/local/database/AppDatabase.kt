package com.example.antifraudagent.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.antifraudagent.data.local.dao.AnalyzedMessageDao
import com.example.antifraudagent.data.local.call.CallTranscript
import com.example.antifraudagent.data.local.call.CallTranscriptDao
import com.example.antifraudagent.data.local.call.CallTranscriptSyncStatus
import com.example.antifraudagent.data.local.entity.AnalyzedMessage
import com.example.antifraudagent.data.local.entity.MessageSource
import com.example.antifraudagent.data.local.entity.MessageStatus

// -----------------------------------------------------------------------------
// Type Converters — Room só armazena tipos primitivos nativamente.
// Enums são convertidos para String e vice-versa.
// -----------------------------------------------------------------------------

class Converters {
    @TypeConverter fun sourceToString(value: MessageSource): String = value.name
    @TypeConverter fun stringToSource(value: String): MessageSource = MessageSource.valueOf(value)

    @TypeConverter fun statusToString(value: MessageStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): MessageStatus = MessageStatus.valueOf(value)

    @TypeConverter fun callSyncStatusToString(value: CallTranscriptSyncStatus): String = value.name
    @TypeConverter fun stringToCallSyncStatus(value: String): CallTranscriptSyncStatus = CallTranscriptSyncStatus.valueOf(value)
}

// -----------------------------------------------------------------------------
// AppDatabase — singleton que representa o banco SQLite do app
// -----------------------------------------------------------------------------

@Database(
    entities = [AnalyzedMessage::class, CallTranscript::class],
    version  = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun analyzedMessageDao(): AnalyzedMessageDao
    abstract fun callTranscriptDao(): CallTranscriptDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Retorna a instância única do banco.
         * @Volatile garante que mudanças em INSTANCE sejam visíveis imediatamente
         * para todas as threads — evita criar duas instâncias em paralelo.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "antifraud_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `call_transcripts` (" +
                        "`sessionId` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, " +
                        "`riskScore` REAL, `isFraud` INTEGER, `explanation` TEXT, " +
                        "`syncStatus` TEXT NOT NULL, `syncError` TEXT, " +
                        "PRIMARY KEY(`sessionId`))"
                )
            }
        }

        /** v3: historico de ligacoes com numero, trecho do alerta, justificativa e sinais. */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "`callerNumber` TEXT",
                    "`speakerUsed` INTEGER NOT NULL DEFAULT 0",
                    "`verdict` TEXT",
                    "`reasoning` TEXT",
                    "`indicators` TEXT",
                    "`localIndicators` TEXT",
                    "`alertExcerpt` TEXT",
                    "`alertAtMillis` INTEGER",
                    "`alertSource` TEXT",
                    "`captureIssue` TEXT"
                ).forEach { column ->
                    database.execSQL("ALTER TABLE `call_transcripts` ADD COLUMN $column")
                }
            }
        }
    }
}
