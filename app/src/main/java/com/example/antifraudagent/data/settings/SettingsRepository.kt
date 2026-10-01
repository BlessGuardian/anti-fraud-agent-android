package com.example.antifraudagent.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferencias locais do usuario.
 *
 * Guarda o kill switch de envio (`capture_enabled`), a protecao de ligacoes e o modo
 * tecnico (`technical_mode`). Com o kill switch desligado,
 * nenhuma mensagem capturada pelos servicos (notificacao, acessibilidade, SMS)
 * nem analise manual vai para o backend AWS. Util para testes com dados sensiveis
 * no celular pessoal sem desinstalar o app nem revogar permissoes do sistema.
 *
 * Singleton para que UI (Compose) e MessageRepository (servicos em background)
 * observem o mesmo StateFlow.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _captureEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_CAPTURE_ENABLED, DEFAULT_CAPTURE_ENABLED)
    )

    private val _callProtectionEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_CALL_PROTECTION_ENABLED, DEFAULT_CALL_PROTECTION_ENABLED)
    )

    private val _reinforcedProtection = MutableStateFlow(
        prefs.getBoolean(KEY_REINFORCED_PROTECTION, false)
    )

    private val _technicalMode = MutableStateFlow(
        prefs.getBoolean(KEY_TECHNICAL_MODE, false)
    )

    /** Flag observavel pelo Compose. */
    val captureEnabled: StateFlow<Boolean> = _captureEnabled.asStateFlow()
    val callProtectionEnabled: StateFlow<Boolean> = _callProtectionEnabled.asStateFlow()

    /**
     * Modo tecnico: mostra o "comite" de algoritmos (LLM x modelo local) no detalhe.
     * Desligado por padrao; liberado por toques no logo. Provisorio ate existir login,
     * quando passa a valer so para administrador.
     */
    val technicalMode: StateFlow<Boolean> = _technicalMode.asStateFlow()

    /**
     * Protecao reforcada (base do futuro modo idoso/crianca): em ALTO risco o app derruba a
     * ligacao e fecha o app de conversa, alem do aviso. Provisorio no Perfil ate existir login;
     * depois quem liga/desliga e o responsavel.
     */
    val reinforcedProtection: StateFlow<Boolean> = _reinforcedProtection.asStateFlow()

    fun isReinforcedProtection(): Boolean = _reinforcedProtection.value

    fun setReinforcedProtection(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REINFORCED_PROTECTION, enabled).apply()
        _reinforcedProtection.value = enabled
    }

    /** Leitura sincrona chamada pelos servicos antes de salvar/enviar. */
    fun isCaptureEnabled(): Boolean = _captureEnabled.value

    fun setCaptureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CAPTURE_ENABLED, enabled).apply()
        _captureEnabled.value = enabled
    }

    fun isCallProtectionEnabled(): Boolean = _callProtectionEnabled.value

    fun setCallProtectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALL_PROTECTION_ENABLED, enabled).apply()
        _callProtectionEnabled.value = enabled
    }

    /** Quando a protecao continua (notificacoes + acessibilidade) foi ligada; null se esta desligada. */
    fun protectedSinceMillis(): Long? =
        prefs.getLong(KEY_PROTECTED_SINCE, 0L).takeIf { it > 0L }

    /** Chamado ao voltar ao app: guarda a data ao ligar a protecao e apaga ao desligar. */
    fun updateProtectedSince(protectionActive: Boolean) {
        val current = protectedSinceMillis()
        when {
            protectionActive && current == null ->
                prefs.edit().putLong(KEY_PROTECTED_SINCE, System.currentTimeMillis()).apply()
            !protectionActive && current != null ->
                prefs.edit().remove(KEY_PROTECTED_SINCE).apply()
        }
    }

    fun setTechnicalMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TECHNICAL_MODE, enabled).apply()
        _technicalMode.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "antifraud_settings"
        private const val KEY_CAPTURE_ENABLED = "capture_enabled"
        private const val KEY_CALL_PROTECTION_ENABLED = "call_protection_enabled"
        private const val KEY_TECHNICAL_MODE = "technical_mode"
        private const val KEY_REINFORCED_PROTECTION = "reinforced_protection"
        private const val KEY_PROTECTED_SINCE = "protected_since"
        private const val DEFAULT_CAPTURE_ENABLED = true
        private const val DEFAULT_CALL_PROTECTION_ENABLED = false

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
        }
    }
}
