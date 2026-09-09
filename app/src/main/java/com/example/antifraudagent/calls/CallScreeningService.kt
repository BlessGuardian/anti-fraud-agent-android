package com.example.antifraudagent.calls

import android.app.NotificationManager
import android.content.Context
import android.telecom.Call
import android.telecom.CallScreeningService
import com.example.antifraudagent.data.settings.SettingsRepository

/**
 * Recebe chamadas para oferecer ao usuario a abertura voluntaria da protecao.
 * Nao bloqueia, recusa nem responde chamadas automaticamente.
 */
class CallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        respondToCall(callDetails, CallResponse.Builder().build())
        if (!SettingsRepository.getInstance(this).isCallProtectionEnabled()) return

        CallNotifications.createChannels(this)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            CallNotifications.PROTECTION_NOTIFICATION_ID - 1,
            android.app.Notification.Builder(this, CallNotifications.ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("Ligacao detectada")
                .setContentText("Toque para iniciar a protecao e transcricao.")
                .setContentIntent(CallNotifications.startProtectionIntent(this))
                .setAutoCancel(true)
                .build()
        )
    }
}
