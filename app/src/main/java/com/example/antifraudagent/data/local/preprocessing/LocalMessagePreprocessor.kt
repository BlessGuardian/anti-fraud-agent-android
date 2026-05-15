package com.example.antifraudagent.data.local.preprocessing

import android.util.Log

/**
 * Camada local de higiene de dados para capturas automaticas.
 *
 * Recebe o texto bruto capturado por NotificationListener / Accessibility / SMS,
 * normaliza, rejeita ruidos conhecidos (horarios, datas, notificacoes de sistema,
 * nomes isolados) e deduplica capturas iguais em janela curta.
 *
 * Nao calcula score nem decide risco de fraude. A pontuacao oficial comeca no
 * backend Python via POST /detect. A aba Analisar nao passa por este filtro.
 */
object LocalMessagePreprocessor {

    private const val TAG = "MsgPreprocessor"

    private const val DEDUP_WINDOW_MS = 15_000L
    private const val MAX_DEDUP_CACHE = 300
    private const val SHORT_TEXT_THRESHOLD = 10

    sealed class PreprocessResult {
        data class Accepted(val normalizedText: String) : PreprocessResult()
        data class Rejected(val reason: String) : PreprocessResult()
    }

    private val dedupCache = LinkedHashMap<String, Long>()
    private val lock = Any()

    fun process(
        content: String,
        sender: String = "",
        packageName: String = "",
        timestampMs: Long = System.currentTimeMillis()
    ): PreprocessResult {
        val normalized = normalize(content)

        if (normalized.isEmpty()) {
            return reject("vazio apos normalizacao", packageName)
        }

        val hasRelevantSignal = hasRelevantContentSignal(normalized)

        if (isTimeOnly(normalized)) {
            return reject("horario isolado", packageName)
        }

        if (isDateOrSeparator(normalized)) {
            return reject("data ou separador", packageName)
        }

        if (!hasRelevantSignal && isKnownSystemNoise(normalized)) {
            return reject("ruido de sistema", packageName)
        }

        if (!hasRelevantSignal && isJustSenderName(normalized, sender)) {
            return reject("apenas nome do remetente", packageName)
        }

        if (!hasRelevantSignal && normalized.length < SHORT_TEXT_THRESHOLD) {
            return reject("curto sem sinais de conteudo relevante", packageName)
        }

        val fingerprint = buildFingerprint(normalized)
        synchronized(lock) {
            evictExpired(timestampMs)
            val lastSeen = dedupCache[fingerprint]
            if (lastSeen != null && (timestampMs - lastSeen) < DEDUP_WINDOW_MS) {
                val ageSec = (timestampMs - lastSeen) / 1000
                return reject("duplicata (${ageSec}s)", packageName)
            }
            if (dedupCache.size >= MAX_DEDUP_CACHE) {
                val oldestKey = dedupCache.entries.iterator().next().key
                dedupCache.remove(oldestKey)
            }
            dedupCache[fingerprint] = timestampMs
        }

        return PreprocessResult.Accepted(normalized)
    }

    /**
     * Limpa o cache de deduplicacao. Util em testes ou troca de usuario.
     */
    fun clearCache() {
        synchronized(lock) { dedupCache.clear() }
    }

    private fun reject(reason: String, packageName: String): PreprocessResult.Rejected {
        Log.d(TAG, "rejected[$packageName] -> $reason")
        return PreprocessResult.Rejected(reason)
    }

    private fun normalize(content: String): String =
        content
            .replace('\t', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex(" {2,}"), " ")
            .trim()

    private fun isTimeOnly(text: String): Boolean =
        TIME_ONLY_REGEX.matches(text)

    private fun isDateOrSeparator(text: String): Boolean {
        val lower = text.lowercase()
        if (lower in DATE_SEPARATORS) return true
        if (DATE_NUMERIC_REGEX.matches(text)) return true
        if (DATE_WRITTEN_REGEX.matches(text)) return true
        return false
    }

    private fun isKnownSystemNoise(text: String): Boolean {
        val lower = text.lowercase()
        if (lower in SYSTEM_EXACT_MATCHES) return true
        return SYSTEM_SUBSTRING_PATTERNS.any { lower.contains(it) }
    }

    private fun isJustSenderName(text: String, sender: String): Boolean {
        if (sender.isBlank()) return false
        return text.equals(sender.trim(), ignoreCase = true)
    }

    private fun hasRelevantContentSignal(text: String): Boolean {
        val lower = text.lowercase()
        return RELEVANT_CONTENT_SIGNALS.any { lower.contains(it) }
    }

    private fun buildFingerprint(text: String): String =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]"), "")
            .take(160)

    private fun evictExpired(currentTimeMs: Long) {
        val iterator = dedupCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTimeMs - entry.value > DEDUP_WINDOW_MS) {
                iterator.remove()
            } else {
                break
            }
        }
    }

    private val TIME_ONLY_REGEX =
        Regex("""^\d{1,2}:\d{2}(:\d{2})?(\s*(AM|PM|am|pm))?$""")

    private val DATE_NUMERIC_REGEX =
        Regex("""^\d{1,2}[/\-.]\d{1,2}([/\-.]\d{2,4})?$""")

    private val DATE_WRITTEN_REGEX =
        Regex("""^\d{1,2}\s+de\s+\p{L}+(\s+de\s+\d{4})?$""", RegexOption.IGNORE_CASE)

    private val DATE_SEPARATORS = setOf(
        "hoje", "ontem", "anteontem", "amanha", "amanhã",
        "segunda", "terca", "terça", "quarta", "quinta", "sexta", "sabado", "sábado", "domingo",
        "segunda-feira", "terca-feira", "terça-feira",
        "quarta-feira", "quinta-feira", "sexta-feira",
        "janeiro", "fevereiro", "marco", "março", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"
    )

    private val SYSTEM_EXACT_MATCHES = setOf(
        "foto", "video", "vídeo", "audio", "áudio", "figurinha", "sticker",
        "gif", "documento", "localizacao", "localização", "contato", "imagem",
        "enquete", "chamada de voz", "chamada de video", "chamada de vídeo",
        "chamada perdida", "chamada de voz perdida", "chamada de video perdida",
        "chamada de vídeo perdida", "digitando", "digitando...",
        "gravando audio", "gravando áudio", "gravando audio...", "gravando áudio...",
        "online", "offline", "mensagem apagada", "esta mensagem foi apagada",
        "conectado", "desconectado", "..."
    )

    private val SYSTEM_SUBSTRING_PATTERNS = listOf(
        "backup em andamento", "fazendo backup", "backup concluido",
        "backup concluído", "backup pendente",
        "sincronizando mensagens", "sincronizacao concluida",
        "sincronização concluída", "sincronizando backup",
        "whatsapp web ativo", "whatsapp web conectado",
        "procurando mensagens", "verificando mensagens",
        "buscando mensagens novas",
        "criptografia de ponta a ponta",
        "mensagens e chamadas sao criptografadas",
        "mensagens e chamadas são criptografadas",
        "toque para configurar", "toque para ativar",
        "notificacoes silenciadas", "notificações silenciadas",
        "nova notificacao do sistema", "nova notificação do sistema"
    )

    private val RELEVANT_CONTENT_SIGNALS = listOf(
        "http://", "https://", "www.", ".com", ".net", ".org", ".br",
        "bit.ly", "encurtador",
        "pix", "cpf", "cnpj",
        "banco", "conta", "agencia", "agência",
        "cartao", "cartão",
        "senha", "codigo", "código", "token", "otp",
        "boleto", "fatura", "vencimento",
        "r$", "reais", "valor",
        "transferencia", "transferência",
        "urgente", "urgência", "urgencia", "imediato",
        "bloqueado", "suspenso", "encerrado",
        "clique aqui", "acesse", "cadastre",
        "premio", "prêmio", "ganhou", "selecionado", "contemplado",
        "verifique", "confirme", "valide"
    )
}
