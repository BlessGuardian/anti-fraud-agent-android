package com.example.antifraudagent.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.util.Log
import com.example.antifraudagent.services.FraudAccessibilityService

/**
 * Encerra a ligacao em andamento (protecao reforcada, so em alto risco).
 *
 * Duas tentativas, nesta ordem:
 * 1. `TelecomManager.endCall()` com ANSWER_PHONE_CALLS. Depreciado desde o Android 10 e, segundo a
 *    documentacao, restrito ao discador padrao em alguns aparelhos; por isso o retorno e conferido.
 * 2. Servico de acessibilidade toca no botao "encerrar" da tela de chamada.
 */
object CallTerminator {
    private const val TAG = "CallTerminator"

    enum class Result { TELECOM, ACCESSIBILITY, FAILED }

    fun endCall(context: Context): Result {
        if (tryTelecom(context)) return Result.TELECOM.also { Log.d(TAG, "Ligacao encerrada via TelecomManager") }
        if (FraudAccessibilityService.instance?.clickEndCallButton() == true) {
            return Result.ACCESSIBILITY.also { Log.d(TAG, "Ligacao encerrada via acessibilidade") }
        }
        Log.w(TAG, "Nao foi possivel encerrar a ligacao")
        return Result.FAILED
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun tryTelecom(context: Context): Boolean {
        if (!hasPermission(context)) return false
        return try {
            context.getSystemService(TelecomManager::class.java)?.endCall() == true
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager.endCall falhou", e)
            false
        }
    }
}
