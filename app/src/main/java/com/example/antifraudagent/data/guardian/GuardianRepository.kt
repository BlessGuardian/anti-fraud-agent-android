package com.example.antifraudagent.data.guardian

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.example.antifraudagent.BuildConfig
import com.example.antifraudagent.MainActivity
import com.example.antifraudagent.R
import com.example.antifraudagent.calls.CallNotifications
import com.example.antifraudagent.data.device.DeviceIdentityProvider
import com.example.antifraudagent.data.remote.FraudApiClient
import com.example.antifraudagent.data.remote.RemoteFraudLog
import com.example.antifraudagent.data.remote.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * Modo idoso/crianca, versao sem endpoint de vinculo no backend.
 *
 * - Celular da pessoa protegida: mostra o codigo de vinculo (`LinkCode`, = user_id dos logs).
 * - Celular do responsavel: guarda as pessoas vinculadas e consulta `GET /logs?user_id=` de tempos
 *   em tempos (GuardianWorker, ~15 min; tambem ao abrir o app). Golpe novo -> notificacao local.
 *
 * Limitacoes (aguardando backend): vinculo por codigo/QR em vez de nome de usuario; aviso com atraso;
 * depende do /logs aceitar consulta sem token. Quando houver endpoint de vinculo, so este repositorio muda.
 */
class GuardianRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val apiClient = FraudApiClient()
    private val checkMutex = Mutex()

    private val _people = MutableStateFlow(loadPeople())
    val people: StateFlow<List<ProtectedPerson>> = _people.asStateFlow()

    /** Codigo deste celular, para a pessoa protegida mostrar ao responsavel. */
    fun ownLinkCode(): String =
        LinkCode.encode(LinkCode.userIdForDevice(DeviceIdentityProvider.getDeviceId(appContext)))

    /**
     * Vincula uma pessoa. Busca o historico dela na hora: valida o codigo e marca os alertas antigos
     * como vistos (so golpes novos notificam).
     */
    suspend fun add(name: String, rawCode: String): AddResult = withContext(Dispatchers.IO) {
        val userId = LinkCode.decode(rawCode) ?: return@withContext AddResult.INVALID_CODE
        // No build debug o proprio celular pode se vincular: demonstra os dois lados com um aparelho so.
        if (!BuildConfig.DEBUG && userId == LinkCode.userIdForDevice(DeviceIdentityProvider.getDeviceId(appContext))) {
            return@withContext AddResult.OWN_DEVICE
        }
        if (_people.value.any { it.userId == userId }) return@withContext AddResult.ALREADY_LINKED
        val logs = try {
            apiClient.getLogs(userId = userId, limit = HISTORY_LIMIT)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao validar o codigo de vinculo", e)
            return@withContext AddResult.OFFLINE
        }
        save(_people.value + ProtectedPerson(userId, name.trim().ifBlank { "Pessoa protegida" }, latest(logs)))
        AddResult.ADDED
    }

    fun remove(person: ProtectedPerson) = save(_people.value.filterNot { it.userId == person.userId })

    suspend fun history(person: ProtectedPerson): List<RemoteFraudLog> =
        apiClient.getLogs(userId = person.userId, limit = HISTORY_LIMIT)

    /** Procura golpes novos de cada pessoa vinculada e notifica. Seguro chamar de varios lugares. */
    suspend fun checkForNewAlerts() = withContext(Dispatchers.IO) {
        if (_people.value.isEmpty()) return@withContext
        checkMutex.withLock {
            val updated = _people.value.map { person ->
                val logs = try {
                    apiClient.getLogs(userId = person.userId, limit = HISTORY_LIMIT)
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao consultar ${person.name}", e)
                    return@map person
                }
                newAlerts(logs, person.lastSeenAt).forEach { notify(person, it) }
                person.copy(lastSeenAt = latest(logs) ?: person.lastSeenAt)
            }
            save(updated)
        }
    }

    private fun notify(person: ProtectedPerson, log: RemoteFraudLog) {
        CallNotifications.createChannels(appContext)
        val high = log.riskLevel == RiskLevel.HIGH
        val title = if (high) "⚠ Golpe detectado no celular de ${person.name}" else "Atenção: mensagem suspeita para ${person.name}"
        val reason = log.hybrid?.justification ?: log.explanation
        val body = buildString {
            if (reason.isNotBlank()) append(reason).append("\n\n")
            append("“${log.content.trim().take(200)}”\n\n")
            append("Converse com ${person.name} e oriente a não clicar em links, não passar códigos e não fazer Pix.")
        }
        val open = PendingIntent.getActivity(
            appContext,
            7,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        appContext.getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_BASE + (log.id.hashCode() and 0xFFFF),
            Notification.Builder(appContext, CallNotifications.ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_shield)
                .setContentTitle(title)
                .setContentText(reason.ifBlank { log.content })
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun save(list: List<ProtectedPerson>) {
        val json = JSONArray()
        list.forEach {
            json.put(JSONObject().put("user_id", it.userId).put("name", it.name).put("last_seen_at", it.lastSeenAt ?: ""))
        }
        prefs.edit().putString(KEY_PEOPLE, json.toString()).apply()
        _people.value = list
        if (list.isEmpty()) GuardianWorker.cancel(appContext) else GuardianWorker.schedule(appContext)
    }

    /** Este celular e de uma pessoa protegida (idoso/crianca): mostra o codigo e liga a protecao reforcada. */
    private val _protectedDevice = MutableStateFlow(prefs.getBoolean(KEY_PROTECTED_DEVICE, false))
    val protectedDevice: StateFlow<Boolean> = _protectedDevice.asStateFlow()

    fun setProtectedDevice(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROTECTED_DEVICE, enabled).apply()
        _protectedDevice.value = enabled
    }

    private fun loadPeople(): List<ProtectedPerson> = try {
        val json = JSONArray(prefs.getString(KEY_PEOPLE, "[]"))
        (0 until json.length()).map { i ->
            val item = json.getJSONObject(i)
            ProtectedPerson(
                userId = item.getString("user_id"),
                name = item.optString("name", "Pessoa protegida"),
                lastSeenAt = item.optString("last_seen_at").takeIf { it.isNotBlank() }
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    enum class AddResult { ADDED, INVALID_CODE, OWN_DEVICE, ALREADY_LINKED, OFFLINE }

    companion object {
        private const val TAG = "GuardianRepository"
        private const val PREFS_NAME = "guardian_links"
        private const val KEY_PEOPLE = "people"
        private const val KEY_PROTECTED_DEVICE = "protected_device"
        private const val HISTORY_LIMIT = 30
        private const val NOTIFICATION_BASE = 7600_000

        @Volatile
        private var instance: GuardianRepository? = null

        fun getInstance(context: Context): GuardianRepository =
            instance ?: synchronized(this) {
                instance ?: GuardianRepository(context).also { instance = it }
            }

        /**
         * Alertas mais novos que o ultimo visto, do mais antigo ao mais novo.
         * Mensagem: atencao ou alto risco. Ligacao: so alto risco, e um aviso por consulta, porque cada
         * ligacao grava varios checkpoints no servidor.
         */
        fun newAlerts(logs: List<RemoteFraudLog>, lastSeenAt: String?): List<RemoteFraudLog> {
            val since = lastSeenAt?.let { parse(it) }
            val fresh = logs.filter { log ->
                val at = parse(log.detectedAt)
                at != null && (since == null || at.isAfter(since))
            }
            val (calls, messages) = fresh.partition { it.source.equals("call", ignoreCase = true) }
            val messageAlerts = messages.filter { it.riskLevel != RiskLevel.SAFE }
            val callAlert = calls.filter { it.riskLevel == RiskLevel.HIGH }.maxByOrNull { it.detectedAt }
            return (messageAlerts + listOfNotNull(callAlert)).sortedBy { it.detectedAt }
        }

        fun latest(logs: List<RemoteFraudLog>): String? =
            logs.mapNotNull { log -> parse(log.detectedAt)?.let { it to log.detectedAt } }.maxByOrNull { it.first }?.second

        private fun parse(value: String): OffsetDateTime? = try {
            OffsetDateTime.parse(value)
        } catch (_: Exception) {
            null
        }
    }
}

data class ProtectedPerson(
    val userId: String,
    val name: String,
    /** detected_at do log mais novo ja visto; alertas ate ele nao notificam de novo. */
    val lastSeenAt: String?
)
