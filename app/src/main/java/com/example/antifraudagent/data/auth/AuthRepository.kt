package com.example.antifraudagent.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.antifraudagent.data.device.DeviceIdentityProvider
import com.example.antifraudagent.data.remote.FraudApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Conta BlessGuardian (POST /register e /login do backend).
 *
 * Login obrigatorio para usar o app (desde 19/10/2026): o GET /logs e o GET /score exigem o
 * token. A captura e o POST /detect continuam funcionando antes do login (protecao nao para).
 * A sessao fica em SharedPreferences proprias (`auth_session`), fora do backup do Android, para o
 * token nao ir para a nuvem. O token vale 24h e nao ha renovacao: expirado ou recusado (401), o
 * app volta para a tela de login com o e-mail preenchido.
 */
class AuthRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val apiClient = FraudApiClient()

    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    /** Ultimo e-mail usado: preenche a tela de login depois de sair ou expirar. */
    fun lastEmail(): String = prefs.getString(KEY_LAST_EMAIL, null).orEmpty()

    /** Mensagem para a tela de login quando a sessao caiu sozinha (expirou ou foi recusada). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        AuthToken.current = _session.value?.token
    }

    /** Cria a conta ligada a este celular (o backend deriva o user_id do device_id) e ja entra. */
    suspend fun register(email: String, password: String): AuthSession {
        apiClient.register(DeviceIdentityProvider.getDeviceId(appContext), email.trim(), password)
        return login(email, password)
    }

    suspend fun login(email: String, password: String): AuthSession {
        val result = apiClient.login(email.trim(), password)
        val session = AuthSession(
            email = email.trim().lowercase(),
            userId = result.userId,
            role = result.role,
            token = result.accessToken,
            expiresAtMillis = jwtExpiryMillis(result.accessToken)
        )
        prefs.edit()
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_ROLE, session.role)
            .putString(KEY_TOKEN, session.token)
            .putLong(KEY_EXPIRES_AT, session.expiresAtMillis ?: 0L)
            .putString(KEY_LAST_EMAIL, session.email)
            .apply()
        AuthToken.current = session.token
        _notice.value = null
        _session.value = session
        return session
    }

    fun logout() = endSession(notice = null)

    /** Servidor recusou o token (401) ou ele venceu: volta ao login sem perder o e-mail. */
    fun expire() = endSession(notice = "Sua sessão expirou. Entre de novo para ver seu histórico.")

    private fun endSession(notice: String?) {
        val email = _session.value?.email ?: lastEmail()
        prefs.edit().clear().putString(KEY_LAST_EMAIL, email).apply()
        AuthToken.current = null
        _notice.value = notice
        _session.value = null
    }

    private fun loadSession(): AuthSession? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L).takeIf { it > 0L }
        if (expiresAt != null && expiresAt < System.currentTimeMillis()) {
            // Sessao vencida ao abrir o app: pede login de novo (o e-mail fica guardado).
            prefs.edit().remove(KEY_TOKEN).apply()
            return null
        }
        return AuthSession(
            email = prefs.getString(KEY_EMAIL, null).orEmpty(),
            userId = prefs.getString(KEY_USER_ID, null).orEmpty(),
            role = prefs.getString(KEY_ROLE, null) ?: "default",
            token = token,
            expiresAtMillis = prefs.getLong(KEY_EXPIRES_AT, 0L).takeIf { it > 0L }
        )
    }

    companion object {
        /** Fora do backup: ver res/xml/data_extraction_rules.xml. */
        const val PREFS_NAME = "auth_session"
        private const val KEY_EMAIL = "email"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ROLE = "role"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_LAST_EMAIL = "last_email"

        @Volatile
        private var instance: AuthRepository? = null

        fun getInstance(context: Context): AuthRepository =
            instance ?: synchronized(this) {
                instance ?: AuthRepository(context).also { instance = it }
            }

        /** Le o campo `exp` (segundos) do payload do JWT, sem validar a assinatura. */
        fun jwtExpiryMillis(token: String): Long? = try {
            val payload = token.split('.').getOrNull(1) ?: return null
            val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            JSONObject(json).optLong("exp", 0L).takeIf { it > 0L }?.times(1000)
        } catch (_: Exception) {
            null
        }
    }
}

data class AuthSession(
    val email: String,
    val userId: String,
    /** Papel definido no backend: "default" ou "admin". */
    val role: String,
    val token: String,
    val expiresAtMillis: Long?
) {
    /** Regra do orientador: o comite de algoritmos (modo tecnico) e para administrador. */
    val isAdmin: Boolean get() = role.equals("admin", ignoreCase = true)

    val isExpired: Boolean get() = expiresAtMillis?.let { it < System.currentTimeMillis() } == true
}
