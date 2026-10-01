package com.example.antifraudagent.data.local.trusted

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Contato que o usuario marcou como confiavel. Fica so no aparelho: a lista nunca vai ao backend.
 *
 * Guarda nome e numero porque cada captura conhece um deles: notificacao de WhatsApp, Telegram e
 * Instagram traz o NOME salvo na agenda; SMS (e ligacao) traz o NUMERO.
 */
@Entity(tableName = "trusted_contacts")
data class TrustedContact(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String? = null,
    /** Numero como o usuario/agenda escreveu, so para exibir. */
    val phone: String? = null,
    /** So os digitos de [phone], para comparar. */
    val phoneDigits: String? = null,
    val origin: TrustedContactOrigin = TrustedContactOrigin.MANUAL,
    val createdAt: Long = System.currentTimeMillis()
)

enum class TrustedContactOrigin { MANUAL, CONTACTS_APP }

@Dao
interface TrustedContactDao {
    @Insert
    suspend fun insert(contact: TrustedContact): Long

    @Delete
    suspend fun delete(contact: TrustedContact)

    @Query("SELECT * FROM trusted_contacts ORDER BY name COLLATE NOCASE, phone")
    fun observeAll(): Flow<List<TrustedContact>>

    @Query("SELECT * FROM trusted_contacts")
    suspend fun getAll(): List<TrustedContact>
}
