package com.example.antifraudagent.data.score

import android.content.Context
import android.content.SharedPreferences
import com.example.antifraudagent.data.remote.FraudApiClient
import com.example.antifraudagent.data.remote.FraudApiHttpException
import com.example.antifraudagent.data.remote.RemoteFraudLog
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
 * Indice de vulnerabilidade estilo Serasa: valor unico (0..1, exibido de 0 a 1000) calculado no servidor (GET /score,
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

    private val _status = MutableStateFlow(ScoreStatus.UNKNOWN)
    /** Resultado da ultima consulta, para o card explicar quando nao ha valor. */
    val status: StateFlow<ScoreStatus> = _status.asStateFlow()

    /** Busca o score e registra mudanca. Lanca FraudApiHttpException 401 para a tela tratar. */
    suspend fun refresh(logs: List<RemoteFraudLog>) = withContext(Dispatchers.IO) {
        val value = try {
            apiClient.getScore()
        } catch (e: FraudApiHttpException) {
            if (e.code == 401) throw e
            _status.value = ScoreStatus.ERROR
            return@withContext
        } catch (_: Exception) {
            _status.value = ScoreStatus.ERROR
            return@withContext
        }
        if (value == null) {
            // 404: a conta ainda nao tem indice no servidor (sem registro ou recalculo nao rodou).
            _status.value = ScoreStatus.NOT_CALCULATED
            return@withContext
        }
        _status.value = ScoreStatus.OK
        val entry = nextEntry(_history.value.firstOrNull(), value, logs, System.currentTimeMillis())
            ?: return@withContext
        save(listOf(entry) + _history.value.take(MAX_HISTORY - 1))
    }

    /** Ao sair da conta: o historico e de quem estava logado. */
    fun clear() {
        _status.value = ScoreStatus.UNKNOWN
        save(emptyList())
    }

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
            return ScoreEntry(value, last.value, now, reasonFor(last.value, value, fraudsSince(logs, last.observedAt), last.observedAt))
        }

        /**
         * Golpes confirmados (is_fraud) depois de `sinceMillis`: e o que o servidor conta no
         * calculate_scores, entao o motivo usa a mesma base.
         */
        fun fraudsSince(logs: List<RemoteFraudLog>, sinceMillis: Long): List<RemoteFraudLog> =
            logs.filter { log ->
                log.isFraud && (parseMillis(log.detectedAt)?.let { it > sinceMillis } == true)
            }

        /**
         * O servidor nao explica a mudanca: o indice e a posicao da sua quantidade de golpes entre a
         * dos demais usuarios (media +- 3 desvios). Por isso o motivo cruza a direcao da mudanca com
         * os golpes novos do seu historico.
         */
        fun reasonFor(previous: Float, current: Float, frauds: List<RemoteFraudLog>, sinceMillis: Long): String {
            val since = SimpleDateFormat("dd/MM", Locale("pt", "BR")).format(Date(sinceMillis))
            val up = current > previous
            if (frauds.isEmpty()) {
                return if (up) {
                    "Subiu na comparação com os demais usuários: você não teve golpes novos desde $since, " +
                        "mas os outros usuários receberam proporcionalmente menos golpes que você."
                } else {
                    "Caiu: você não teve golpes novos desde $since."
                }
            }
            val n = frauds.size
            val what = if (n == 1) "1 golpe novo" else "$n golpes novos"
            val categories = frauds.mapNotNull { categoryLabel(it.category) }.distinct().take(3)
            val detail = if (categories.isEmpty()) "" else " (${categories.joinToString(", ")})"
            return if (up) {
                "Subiu porque você teve $what desde $since$detail."
            } else {
                "Caiu mesmo com $what desde $since$detail: os demais usuários receberam " +
                    "proporcionalmente mais golpes que você."
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

enum class ScoreStatus {
    /** Ainda nao consultou nesta sessao do app. */
    UNKNOWN,
    OK,
    /** Servidor respondeu 404 ou sem valor: indice ainda nao calculado para a conta. */
    NOT_CALCULATED,
    /** Erro de rede ou do servidor (exceto 401, que leva ao login). */
    ERROR
}
