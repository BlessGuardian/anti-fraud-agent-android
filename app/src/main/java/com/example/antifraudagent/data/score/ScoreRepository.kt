package com.example.antifraudagent.data.score

import android.content.Context
import android.content.SharedPreferences
import com.example.antifraudagent.data.remote.FraudApiClient
import com.example.antifraudagent.data.remote.RemoteFraudLog
import com.example.antifraudagent.data.remote.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Indice de vulnerabilidade estilo Serasa: valor unico (0..1) calculado no servidor (GET /score,
 * recalculado em periodo fixo pelo backend) que so muda quando ha um motivo.
 *
 * O backend devolve apenas o valor atual. O app guarda cada valor que viu (`score_history`, vai no
 * backup) e, quando o valor muda, monta o motivo a partir dos alertas do historico desde a mudanca
 * anterior. A data e a de quando o app percebeu a mudanca. Se o backend passar a devolver valor
 * anterior, data e motivo, so este repositorio muda.
 */
class ScoreRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val apiClient = FraudApiClient()

    private val _history = MutableStateFlow(load())
    /** Mais recente primeiro. */
    val history: StateFlow<List<ScoreEntry>> = _history.asStateFlow()

    /** Busca o score e registra mudanca. Lanca FraudApiHttpException (ex.: 401) para a tela tratar. */
    suspend fun refresh(logs: List<RemoteFraudLog>) = withContext(Dispatchers.IO) {
        val value = apiClient.getScore() ?: return@withContext
        val entry = nextEntry(_history.value.firstOrNull(), value, logs, System.currentTimeMillis())
            ?: return@withContext
        save(listOf(entry) + _history.value.take(MAX_HISTORY - 1))
    }

    /** Ao sair da conta: o historico e de quem estava logado. */
    fun clear() = save(emptyList())

    private fun save(list: List<ScoreEntry>) {
        val json = JSONArray()
        list.forEach {
            json.put(
                JSONObject()
                    .put("value", it.value.toDouble())
                    .put("previous", it.previous?.toDouble() ?: JSONObject.NULL)
                    .put("observed_at", it.observedAt)
                    .put("reason", it.reason)
            )
        }
        prefs.edit().putString(KEY_HISTORY, json.toString()).apply()
        _history.value = list
    }

    private fun load(): List<ScoreEntry> = try {
        val json = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        (0 until json.length()).map { i ->
            val item = json.getJSONObject(i)
            ScoreEntry(
                value = item.getDouble("value").toFloat(),
                previous = if (item.isNull("previous")) null else item.getDouble("previous").toFloat(),
                observedAt = item.getLong("observed_at"),
                reason = item.optString("reason")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val PREFS_NAME = "score_history"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 20
        private const val EPSILON = 0.0001f

        @Volatile
        private var instance: ScoreRepository? = null

        fun getInstance(context: Context): ScoreRepository =
            instance ?: synchronized(this) {
                instance ?: ScoreRepository(context).also { instance = it }
            }

        /** Nova entrada do historico, ou null se o valor nao mudou. */
        fun nextEntry(last: ScoreEntry?, value: Float, logs: List<RemoteFraudLog>, now: Long): ScoreEntry? {
            if (last == null) {
                return ScoreEntry(value, null, now, "Primeira leitura do seu índice.")
            }
            if (abs(value - last.value) < EPSILON) return null
            return ScoreEntry(value, last.value, now, reasonFor(last.value, value, alertsSince(logs, last.observedAt), last.observedAt))
        }

        /** Alertas (atencao ou alto risco) detectados depois de `sinceMillis`. */
        fun alertsSince(logs: List<RemoteFraudLog>, sinceMillis: Long): List<RemoteFraudLog> =
            logs.filter { log ->
                log.riskLevel != RiskLevel.SAFE &&
                    (parseMillis(log.detectedAt)?.let { it > sinceMillis } == true)
            }

        fun reasonFor(previous: Float, current: Float, alerts: List<RemoteFraudLog>, sinceMillis: Long): String {
            val since = SimpleDateFormat("dd/MM", Locale("pt", "BR")).format(Date(sinceMillis))
            val general = "o servidor recalculou o índice comparando você com os demais usuários"
            if (alerts.isEmpty()) {
                return if (current < previous) {
                    "Caiu: nenhum golpe novo desde $since e $general."
                } else {
                    "Atualização geral: $general."
                }
            }
            val n = alerts.size
            val what = if (n == 1) "1 golpe ou alerta foi detectado" else "$n golpes ou alertas foram detectados"
            val categories = alerts.mapNotNull { categoryLabel(it.category) }.distinct().take(3)
            val detail = if (categories.isEmpty()) "" else " (${categories.joinToString(", ")})"
            return if (current > previous) {
                "Subiu porque $what desde $since$detail."
            } else {
                "Atualização geral: $general. Desde $since, $what$detail."
            }
        }

        private fun categoryLabel(category: String?): String? = when (category?.lowercase()) {
            null, "", "seguro", "desconhecido" -> null
            "phishing" -> "phishing"
            "scam" -> "golpe financeiro"
            "spam" -> "spam"
            else -> category.lowercase()
        }

        private fun parseMillis(value: String): Long? = try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}

data class ScoreEntry(
    val value: Float,
    /** Valor anterior visto pelo app; null na primeira leitura. */
    val previous: Float?,
    /** Quando o app percebeu a mudanca. */
    val observedAt: Long,
    val reason: String
)
