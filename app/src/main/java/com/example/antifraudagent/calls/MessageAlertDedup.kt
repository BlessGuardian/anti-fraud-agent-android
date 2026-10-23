package com.example.antifraudagent.calls

import android.content.Context
import java.security.MessageDigest

/**
 * Garante UM alerta por mensagem de golpe: a mesma mensagem volta a ser capturada quando o usuario
 * abre a conversa (acessibilidade depois da notificacao), quando o app reinicia ou quando o app de
 * mensagens atualiza a notificacao. O filtro do LocalMessagePreprocessor so cobre 15s em memoria.
 *
 * Guarda a impressao digital do texto normalizado (nunca o texto) em SharedPreferences por 24h.
 * Vale para todos os caminhos de captura, inclusive a mesma mensagem em apps diferentes.
 */
object MessageAlertDedup {
    const val WINDOW_MS = 24 * 60 * 60 * 1000L
    private const val MAX_ENTRIES = 500
    private const val PREFS = "alerted_messages"

    private val lock = Any()

    /** true se a mensagem ainda nao alertou nas ultimas 24h (e ja marca como alertada). */
    fun tryClaim(context: Context, content: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            val entries = LinkedHashMap<String, Long>()
            prefs.all.forEach { (key, value) -> if (value is Long) entries[key] = value }
            val claimed = claim(entries, fingerprint(content), nowMs)
            val editor = prefs.edit().clear()
            entries.forEach { (key, at) -> editor.putLong(key, at) }
            editor.apply()
            return claimed
        }
    }

    /** Logica pura (testada): limpa vencidos, recusa repetida e respeita o limite de entradas. */
    internal fun claim(entries: MutableMap<String, Long>, key: String, nowMs: Long): Boolean {
        entries.entries.removeAll { nowMs - it.value >= WINDOW_MS }
        if (key in entries) return false
        if (entries.size >= MAX_ENTRIES) {
            entries.entries.minByOrNull { it.value }?.let { entries.remove(it.key) }
        }
        entries[key] = nowMs
        return true
    }

    /** Ignora maiusculas e espacos/quebras de linha, que mudam entre notificacao e tela. */
    internal fun fingerprint(content: String): String {
        val normalized = content.lowercase().replace(Regex("\\s+"), " ").trim()
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }
}
