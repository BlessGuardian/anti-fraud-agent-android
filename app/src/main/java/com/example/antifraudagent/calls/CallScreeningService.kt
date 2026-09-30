package com.example.antifraudagent.calls

import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * So registra o numero de quem esta ligando para aparecer no historico de ligacoes.
 * Nao bloqueia, nao recusa e nao e o gatilho da protecao: o Android nao repassa ligacoes
 * de contatos (sem READ_CONTACTS) nem de numeros ocultos, que sao comuns em golpes.
 * O gatilho real e o CallStateMonitor (TelephonyCallback no servico de acessibilidade).
 */
class CallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        respondToCall(callDetails, CallResponse.Builder().build())
        if (callDetails.callDirection == Call.Details.DIRECTION_INCOMING) {
            IncomingCallRegistry.record(callDetails.handle?.schemeSpecificPart)
        }
    }
}

/** Ultimo numero recebido pelo filtro de chamadas, valido por alguns segundos. */
object IncomingCallRegistry {
    private const val MAX_AGE_MS = 90_000L

    @Volatile private var number: String? = null
    @Volatile private var recordedAt = 0L

    fun record(value: String?) {
        number = value?.takeIf { it.isNotBlank() }
        recordedAt = SystemClock.elapsedRealtime()
    }

    /** Devolve (e consome) o numero se ele for da ligacao que acabou de tocar. */
    fun consume(): String? {
        val fresh = SystemClock.elapsedRealtime() - recordedAt <= MAX_AGE_MS
        val value = if (fresh) number else null
        number = null
        return value
    }
}
