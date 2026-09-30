package com.example.antifraudagent.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Para onde o audio da ligacao esta indo (define se o microfone consegue ouvir o outro lado). */
enum class CallAudioRoute { UNKNOWN, EARPIECE, SPEAKER, HEADSET }

data class CallProtectionSnapshot(
    val active: Boolean = false,
    val sessionId: String? = null,
    val startedAt: Long? = null,
    val callerNumber: String? = null,
    /** Trechos finais reconhecidos, em ordem. */
    val transcript: String = "",
    /** Trecho ainda em reconhecimento (muda enquanto a pessoa fala). */
    val partial: String = "",
    val route: CallAudioRoute = CallAudioRoute.UNKNOWN,
    /** true quando o microfone recebe som de verdade (nao silenciado pelo sistema). */
    val hearingAudio: Boolean = false,
    val riskLevel: CallRiskLevel = CallRiskLevel.LOW,
    val indicators: List<String> = emptyList(),
    val alertShown: Boolean = false,
    val alertReason: String? = null,
    val analyzing: Boolean = false,
    val status: String = "Aguardando ligações"
)

/** Estado em memoria da ligacao em andamento, observado pela aba Ligacoes. */
object CallProtectionState {
    private val mutableState = MutableStateFlow(CallProtectionSnapshot())
    val state: StateFlow<CallProtectionSnapshot> = mutableState.asStateFlow()

    fun start(sessionId: String, startedAt: Long, callerNumber: String?) {
        mutableState.value = CallProtectionSnapshot(
            active = true,
            sessionId = sessionId,
            startedAt = startedAt,
            callerNumber = callerNumber,
            status = "Ligação atendida. Ative o viva-voz para a proteção ouvir a conversa."
        )
    }

    fun update(transform: (CallProtectionSnapshot) -> CallProtectionSnapshot) {
        mutableState.update(transform)
    }

    fun status(message: String) = update { it.copy(status = message) }

    fun finish(message: String) = update {
        it.copy(active = false, partial = "", analyzing = false, status = message)
    }
}
