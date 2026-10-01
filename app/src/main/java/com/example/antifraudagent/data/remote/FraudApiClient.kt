package com.example.antifraudagent.data.remote

import com.example.antifraudagent.data.local.entity.MessageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

class FraudApiClient(
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    suspend fun detect(
        deviceId: String,
        messageContent: String,
        source: MessageSource
    ): FraudAnalysisResult = withContext(Dispatchers.IO) {
        val connection = (URL("$baseUrl/detect").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        val payload = JSONObject()
            .put("device_id", deviceId)
            .put("message_content", messageContent)
            .put("source", source.name.lowercase())
            .toString()
            .toByteArray(Charsets.UTF_8)

        try {
            connection.outputStream.use { it.write(payload) }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            if (responseCode !in 200..299) {
                throw FraudApiHttpException(responseCode, responseBody)
            }

            parseResponse(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun getLogs(
        deviceId: String,
        limit: Int = 50,
        offset: Int = 0
    ): List<RemoteFraudLog> = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl/logs?device_id=$deviceId&limit=$limit&offset=$offset")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
        }

        try {
            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            if (responseCode !in 200..299) {
                throw FraudApiHttpException(responseCode, responseBody)
            }

            parseLogs(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(responseBody: String): FraudAnalysisResult {
        val root = JSONObject(responseBody)
        val analysis = root.optJSONObject("analise") ?: root
        val rawScore = analysis.optDouble("score", 0.0)
        val normalizedScore = normalizeScore(rawScore).toFloat()

        val indicatorArray = analysis.optJSONArray("indicadores")
        val indicators = if (indicatorArray == null) {
            emptyList()
        } else {
            (0 until indicatorArray.length())
                .mapNotNull { indicatorArray.optString(it).trim().takeIf { value -> value.isNotBlank() && value != "null" } }
        }
        val indicatorText = indicators.joinToString(separator = "; ")

        val verdict = analysis.optStringOrNull("veredito_curto").orEmpty()
        val explanation = when {
            verdict.isNotBlank() && indicatorText.isNotBlank() -> "$verdict | Indicadores: $indicatorText"
            verdict.isNotBlank() -> verdict
            indicatorText.isNotBlank() -> indicatorText
            else -> "Analise concluida pelo servidor."
        }

        // A versao hibrida (LLM + ONNX) publicada pode devolver detalhes_hibridos na raiz ou em
        // "analise"; quando nao vem, o veredito_curto costuma trazer o mesmo conteudo em texto.
        val hybridJson = root.optJSONObject("detalhes_hibridos") ?: analysis.optJSONObject("detalhes_hibridos")
        val parsedFromText = HybridVerdict.parse(verdict)
        val hybrid = if (hybridJson != null) {
            HybridVerdict(
                status = hybridJson.optStringOrNull("status_final") ?: parsedFromText?.status,
                llm = decisionLabel(hybridJson, "decisao_llm") ?: parsedFromText?.llm,
                localModel = decisionLabel(hybridJson, "decisao_onnx") ?: parsedFromText?.localModel,
                justification = parsedFromText?.justification
            )
        } else {
            parsedFromText
        }

        return FraudAnalysisResult(
            isFraud = analysis.optBoolean("tentativa_fraude", false),
            score = normalizedScore,
            category = analysis.optStringOrNull("categoria") ?: "outro",
            explanation = explanation,
            dbSynced = root.optBoolean("status_db", false),
            verdict = verdict,
            reasoning = analysis.optStringOrNull("raciocinio").orEmpty(),
            indicators = indicators,
            hybrid = hybrid
        )
    }

    /** decisao_llm/decisao_onnx chegam como 0/1 (ou texto): 1 = fraude. */
    private fun decisionLabel(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val value = json.opt(key)) {
            is Number -> if (value.toInt() == 1) "Fraude" else "Seguro"
            is Boolean -> if (value) "Fraude" else "Seguro"
            else -> value?.toString()?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private fun parseLogs(responseBody: String): List<RemoteFraudLog> {
        val root = JSONObject(responseBody)
        val data = root.optJSONArray("data") ?: return emptyList()

        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            RemoteFraudLog(
                id = item.optStringOrNull("id").orEmpty(),
                userId = item.optStringOrNull("user_id").orEmpty(),
                content = item.optStringOrNull("content").orEmpty(),
                riskScore = normalizeScore(item.optDouble("risk_score", 0.0)).toFloat(),
                isFraud = item.optBoolean("is_fraud", false),
                explanation = item.optStringOrNull("explanation").orEmpty(),
                source = item.optStringOrNull("source").orEmpty(),
                detectedAt = item.optStringOrNull("detected_at").orEmpty()
            )
        }
    }

    private fun normalizeScore(score: Double): Double {
        val normalized = if (score > 1.0) score / 100.0 else score
        return min(1.0, max(0.0, normalized))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://e5skpalp7g.execute-api.us-east-1.amazonaws.com"
    }
}

/**
 * Resposta HTTP fora de 2xx. Erros 4xx (exceto 408/429) sao definitivos: reenviar a mesma
 * mensagem nunca vai funcionar. 5xx, 408 e 429 sao transitorios.
 */
class FraudApiHttpException(
    val code: Int,
    body: String
) : IOException("FastAPI returned HTTP $code: $body") {
    val isPermanent: Boolean
        get() = code in 400..499 && code != 408 && code != 429
}

data class FraudAnalysisResult(
    val isFraud: Boolean,
    val score: Float,
    val category: String,
    /** Veredito + indicadores numa linha (notificacoes e compatibilidade). */
    val explanation: String,
    val dbSynced: Boolean,
    /** veredito_curto como veio do servidor. */
    val verdict: String = "",
    /** raciocinio do LLM (nao e persistido no DynamoDB; so existe na resposta do /detect). */
    val reasoning: String = "",
    val indicators: List<String> = emptyList(),
    val hybrid: HybridVerdict? = null
) {
    val riskLevel: RiskLevel get() = RiskLevel.of(isFraud, hybrid)
}

data class RemoteFraudLog(
    val id: String,
    val userId: String,
    val content: String,
    val riskScore: Float,
    val isFraud: Boolean,
    val explanation: String,
    val source: String,
    val detectedAt: String
) {
    /** O backend hibrido grava o veredito estruturado dentro de `explanation`. */
    val hybrid: HybridVerdict? get() = HybridVerdict.parse(explanation)

    val riskLevel: RiskLevel get() = RiskLevel.of(isFraud, hybrid)
}

/**
 * Nivel de risco exibido ao usuario. O backend hibrido grava is_fraud=true tanto para
 * FRAUDE (LLM e modelo local concordam) quanto para AVISO (so um deles acusou); por isso
 * o nivel vem do status_final, e is_fraud so decide quando nao ha veredito hibrido.
 */
enum class RiskLevel {
    SAFE, ATTENTION, HIGH;

    companion object {
        fun of(isFraud: Boolean, hybrid: HybridVerdict?): RiskLevel =
            when (hybrid?.status?.trim()?.lowercase()) {
                "fraude" -> HIGH
                "aviso" -> ATTENTION
                "seguro" -> SAFE
                else -> if (isFraud) HIGH else SAFE
            }
    }
}

/** O backend hibrido ainda grava score=0; zero significa "sem pontuacao", nao "0% de risco". */
fun Float?.meaningfulScore(): Float? = this?.takeIf { it > 0f }

/**
 * Decisao do backend hibrido. Formato gravado em `explanation`:
 * "Veredito Híbrido: SEGURO. LLM: Seguro | Modelo Local: Seguro. Justificativa LLM: Mensagem segura."
 */
data class HybridVerdict(
    val status: String?,
    val llm: String?,
    val localModel: String?,
    val justification: String?
) {
    companion object {
        private val PATTERN = Regex(
            """Veredito\s+H[ií]brido:\s*([^.|]+?)\.\s*LLM:\s*([^|]+?)\s*\|\s*Modelo\s+Local:\s*([^.]+?)\.\s*(?:Justificativa(?:\s+LLM)?:\s*(.*))?$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        fun parse(text: String?): HybridVerdict? {
            if (text.isNullOrBlank()) return null
            val match = PATTERN.find(text.trim()) ?: return null
            val (status, llm, local, justification) = match.destructured
            return HybridVerdict(
                status = status.trim().ifBlank { null },
                llm = llm.trim().ifBlank { null },
                localModel = local.trim().ifBlank { null },
                justification = justification.trim().ifBlank { null }
            )
        }
    }
}

/** optString devolve o texto "null" para JSON null; aqui null vira null de verdade. */
internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
}
