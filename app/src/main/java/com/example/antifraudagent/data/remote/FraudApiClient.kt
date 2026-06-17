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
                throw IOException("FastAPI returned HTTP $responseCode: $responseBody")
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
                throw IOException("FastAPI returned HTTP $responseCode: $responseBody")
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

        val indicators = analysis.optJSONArray("indicadores")
        val indicatorText = if (indicators == null || indicators.length() == 0) {
            ""
        } else {
            (0 until indicators.length())
                .mapNotNull { indicators.optString(it).takeIf { value -> value.isNotBlank() } }
                .joinToString(separator = "; ")
        }

        val verdict = analysis.optString("veredito_curto", "").trim()
        val explanation = when {
            verdict.isNotBlank() && indicatorText.isNotBlank() -> "$verdict | Indicadores: $indicatorText"
            verdict.isNotBlank() -> verdict
            indicatorText.isNotBlank() -> indicatorText
            else -> "Analise concluida pelo servidor."
        }

        return FraudAnalysisResult(
            isFraud = analysis.optBoolean("tentativa_fraude", false),
            score = normalizedScore,
            category = analysis.optString("categoria", "outro").ifBlank { "outro" },
            explanation = explanation,
            dbSynced = root.optBoolean("status_db", false)
        )
    }

    private fun parseLogs(responseBody: String): List<RemoteFraudLog> {
        val root = JSONObject(responseBody)
        val data = root.optJSONArray("data") ?: return emptyList()

        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            RemoteFraudLog(
                id = item.optString("id"),
                userId = item.optString("user_id"),
                content = item.optString("content"),
                riskScore = normalizeScore(item.optDouble("risk_score", 0.0)).toFloat(),
                isFraud = item.optBoolean("is_fraud", false),
                explanation = item.optString("explanation"),
                source = item.optString("source"),
                detectedAt = item.optString("detected_at")
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

data class FraudAnalysisResult(
    val isFraud: Boolean,
    val score: Float,
    val category: String,
    val explanation: String,
    val dbSynced: Boolean
)

data class RemoteFraudLog(
    val id: String,
    val userId: String,
    val content: String,
    val riskScore: Float,
    val isFraud: Boolean,
    val explanation: String,
    val source: String,
    val detectedAt: String
)
