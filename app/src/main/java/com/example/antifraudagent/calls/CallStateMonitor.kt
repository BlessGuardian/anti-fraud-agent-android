package com.example.antifraudagent.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.antifraudagent.data.settings.SettingsRepository

/**
 * Deixa a protecao "armada" sem botao: observa o estado do telefone e so dispara quando uma
 * ligacao RECEBIDA e ATENDIDA (RINGING -> OFFHOOK). Ligacoes feitas pelo usuario (IDLE ->
 * OFFHOOK) sao ignoradas; IDLE encerra a sessao.
 *
 * Vive dentro do FraudAccessibilityService: o sistema mantem esse servico ligado, e e o fato de o
 * app ter acessibilidade ativa que permite (a) iniciar o servico de microfone em segundo plano
 * e (b) captar audio durante a ligacao.
 */
class CallStateMonitor(private val context: Context) {

    private class Registration(val manager: TelephonyManager, val callback: TelephonyCallback)

    private val registrations = mutableListOf<Registration>()
    private val lastStates = mutableMapOf<Int, Int>()
    private val ringingSubs = mutableSetOf<Int>()

    fun start() {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE ausente; protecao de chamadas desarmada")
            return
        }
        val base = context.getSystemService(TelephonyManager::class.java) ?: return
        // Em dual-SIM, cada TelephonyManager so informa a propria linha.
        val subscriptionIds = try {
            context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                ?.map { it.subscriptionId }
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        val managers = if (subscriptionIds.isEmpty()) {
            listOf(SubscriptionManager.INVALID_SUBSCRIPTION_ID to base)
        } else {
            subscriptionIds.map { it to base.createForSubscriptionId(it) }
        }
        managers.forEach { (subId, manager) ->
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handle(subId, state)
            }
            try {
                manager.registerTelephonyCallback(context.mainExecutor, callback)
                registrations += Registration(manager, callback)
            } catch (error: SecurityException) {
                Log.w(TAG, "Sem permissao para observar chamadas (sub=$subId)", error)
            }
        }
        Log.i(TAG, "Protecao de chamadas armada em ${registrations.size} linha(s)")
    }

    fun stop() {
        registrations.forEach {
            try { it.manager.unregisterTelephonyCallback(it.callback) } catch (_: Exception) { }
        }
        registrations.clear()
        lastStates.clear()
        ringingSubs.clear()
    }

    private fun handle(subId: Int, state: Int) {
        // O primeiro evento apos registrar e o estado atual, nao uma transicao: so memoriza
        // (se ja estiver tocando, o atendimento logo depois ainda conta).
        val previous = lastStates.put(subId, state) ?: run {
            if (state == TelephonyManager.CALL_STATE_RINGING) ringingSubs += subId
            return
        }
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // RINGING vindo de OFFHOOK e chamada em espera: nao inicia outra sessao.
                if (previous == TelephonyManager.CALL_STATE_IDLE) ringingSubs += subId
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val answeredIncoming = previous == TelephonyManager.CALL_STATE_RINGING && subId in ringingSubs
                ringingSubs -= subId
                if (answeredIncoming) onIncomingAnswered()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                ringingSubs -= subId
                val allIdle = lastStates.values.all { it == TelephonyManager.CALL_STATE_IDLE }
                if (allIdle && CallRecordingService.isRunning) CallRecordingService.stop(context)
            }
        }
    }

    private fun onIncomingAnswered() {
        if (!SettingsRepository.getInstance(context).isCallProtectionEnabled()) return
        if (CallRecordingService.isRunning) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            CallNotifications.createChannels(context)
            CallNotifications.issueNotification(context, "Permita o uso do microfone na aba Ligações do BlessGuardian.")
            return
        }
        // Mostrar o aviso ANTES de iniciar o servico: uma janela visivel do app tambem conta como
        // "em uso" para o Android liberar o microfone em segundo plano.
        CallRecordingService.speakerPromptDismissed = false
        CallOverlays.showSpeakerPrompt(context, CallAudioRoute.UNKNOWN) {
            CallRecordingService.speakerPromptDismissed = true
        }
        CallRecordingService.startForAnsweredCall(context, IncomingCallRegistry.consume())
    }

    companion object {
        private const val TAG = "CallStateMonitor"
    }
}
