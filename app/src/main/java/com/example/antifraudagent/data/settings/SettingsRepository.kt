package com.example.antifraudagent.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferencias locais do usuario.
 *
 * Hoje guarda apenas o kill switch de envio (`capture_enabled`). Quando desligado,
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

    /** Flag observavel pelo Compose. */
    val captureEnabled: StateFlow<Boolean> = _captureEnabled.asStateFlow()

    /** Leitura sincrona chamada pelos servicos antes de salvar/enviar. */
    fun isCaptureEnabled(): Boolean = _captureEnabled.value

    fun setCaptureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CAPTURE_ENABLED, enabled).apply()
        _captureEnabled.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "antifraud_settings"
        private const val KEY_CAPTURE_ENABLED = "capture_enabled"
        private const val DEFAULT_CAPTURE_ENABLED = true

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
        }
    }
}
