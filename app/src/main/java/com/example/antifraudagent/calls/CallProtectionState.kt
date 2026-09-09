package com.example.antifraudagent.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CallProtectionSnapshot(
    val active: Boolean = false,
    val sessionId: String? = null,
    val startedAt: Long? = null,
    val transcript: String = "",
    val riskScore: Float = 0f,
    val alertShown: Boolean = false,
    val status: String = "Protecao de chamadas desativada"
)

/** Estado em memoria para a tela mostrar a transcricao enquanto a chamada ocorre. */
object CallProtectionState {
    private val mutableState = MutableStateFlow(CallProtectionSnapshot())
    val state: StateFlow<CallProtectionSnapshot> = mutableState.asStateFlow()

    fun start(sessionId: String, startedAt: Long) {
        mutableState.value = CallProtectionSnapshot(
            active = true,
            sessionId = sessionId,
            startedAt = startedAt,
            status = "Ouvindo pelo microfone. Ative o viva-voz para testar a captura."
        )
    }

    fun appendTranscript(text: String, score: Float, alertShown: Boolean) {
        val previous = mutableState.value
        val combined = listOf(previous.transcript, text.trim())
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .take(MAX_TRANSCRIPT_CHARS)
        mutableState.value = previous.copy(
            transcript = combined,
            riskScore = maxOf(previous.riskScore, score),
            alertShown = previous.alertShown || alertShown,
            status = "Transcricao em andamento"
        )
    }

    fun status(message: String) {
        mutableState.value = mutableState.value.copy(status = message)
    }

    fun finish(message: String) {
        mutableState.value = mutableState.value.copy(active = false, status = message)
    }

    // Mesmo limite aceito pelo contrato /detect do backend.
    private const val MAX_TRANSCRIPT_CHARS = 12_000
}
