package com.example.antifraudagent.data.trusted

import android.content.Context
import com.example.antifraudagent.data.local.database.AppDatabase
import com.example.antifraudagent.data.local.trusted.TrustedContact
import com.example.antifraudagent.data.local.trusted.TrustedContactOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * Lista local de contatos confiaveis.
 *
 * Regra de alerta (MessageRepository): mensagem de contato confiavel continua sendo analisada,
 * mas so gera alerta em alto risco (os dois algoritmos concordam) — protecao contra conta clonada.
 *
 * Ponto unico de acesso: quando existir login, a sincronizacao com o backend entra aqui sem
 * mudar telas nem captura.
 */
class TrustedContactRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context.applicationContext).trustedContactDao()

    fun observeAll(): Flow<List<TrustedContact>> = dao.observeAll()

    /** @return false quando nao ha nome nem numero valido, ou o contato ja esta na lista. */
    suspend fun add(name: String?, phone: String?, origin: TrustedContactOrigin): Boolean =
        withContext(Dispatchers.IO) {
            val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }
            val cleanPhone = phone?.trim()?.takeIf { it.isNotEmpty() }
            val digits = cleanPhone?.let { digitsOf(it) }?.takeIf { it.length >= MIN_PHONE_DIGITS }
            if (cleanName == null && digits == null) return@withContext false

            val candidate = TrustedContact(
                name = cleanName,
                phone = cleanPhone?.takeIf { digits != null },
                phoneDigits = digits,
                origin = origin
            )
            if (dao.getAll().any { it.sameAs(candidate) }) return@withContext false
            dao.insert(candidate)
            true
        }

    suspend fun remove(contact: TrustedContact) = withContext(Dispatchers.IO) { dao.delete(contact) }

    /** [sender] e o que a captura conhece: nome (notificacao) ou numero (SMS). */
    suspend fun isTrusted(sender: String?): Boolean = withContext(Dispatchers.IO) {
        if (sender.isNullOrBlank()) return@withContext false
        val contacts = dao.getAll()
        contacts.isNotEmpty() && matches(sender, contacts)
    }

    companion object {
        /** Abaixo disso nao e telefone (ex.: "190"); numeros curtos de servico entram por nome. */
        const val MIN_PHONE_DIGITS = 5

        /** Brasil: compara os ultimos 8 digitos, que ignoram +55, DDD e o 9 extra do celular. */
        private const val PHONE_SUFFIX = 8

        fun digitsOf(value: String): String = value.filter { it.isDigit() }

        fun normalizeName(value: String): String =
            Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("\\s+"), " ")
                .lowercase()

        fun samePhone(a: String, b: String): Boolean {
            if (a.length < MIN_PHONE_DIGITS || b.length < MIN_PHONE_DIGITS) return false
            if (a.length < PHONE_SUFFIX || b.length < PHONE_SUFFIX) return a == b
            return a.takeLast(PHONE_SUFFIX) == b.takeLast(PHONE_SUFFIX)
        }

        fun matches(sender: String, contacts: List<TrustedContact>): Boolean {
            val senderDigits = digitsOf(sender)
            // Remetente "numerico" (SMS, ou WhatsApp de numero nao salvo): compara por numero.
            val looksLikePhone = senderDigits.length >= MIN_PHONE_DIGITS &&
                senderDigits.length >= sender.count { it.isLetter() }
            val senderName = normalizeName(sender)
            return contacts.any { contact ->
                val byPhone = looksLikePhone && contact.phoneDigits?.let { samePhone(it, senderDigits) } == true
                val byName = contact.name?.let { normalizeName(it) == senderName } == true
                byPhone || byName
            }
        }

        private fun TrustedContact.sameAs(other: TrustedContact): Boolean {
            val phoneEqual = phoneDigits != null && other.phoneDigits != null &&
                samePhone(phoneDigits, other.phoneDigits)
            val nameEqual = phoneDigits == null && other.phoneDigits == null &&
                name != null && other.name != null && normalizeName(name) == normalizeName(other.name)
            return phoneEqual || nameEqual
        }
    }
}
