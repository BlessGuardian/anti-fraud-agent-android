package com.example.antifraudagent.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.antifraudagent.data.local.dao.AnalyzedMessageDao
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
}

// -----------------------------------------------------------------------------
// AppDatabase — singleton que representa o banco SQLite do app
// -----------------------------------------------------------------------------

@Database(
    entities = [AnalyzedMessage::class],
    version  = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun analyzedMessageDao(): AnalyzedMessageDao

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
                ).build().also { INSTANCE = it }
            }
        }
    }
}
