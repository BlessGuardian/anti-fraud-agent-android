package com.example.antifraudagent.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.antifraudagent.MainActivity

object CallNotifications {
    const val PROTECTION_CHANNEL = "call_protection"
    const val ALERT_CHANNEL = "call_risk_alert"
    const val PROTECTION_NOTIFICATION_ID = 7301
    const val ALERT_NOTIFICATION_ID = 7302

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                PROTECTION_CHANNEL,
                "Protecao de chamadas",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Mostra que a transcricao de chamada esta ativa." }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL,
                "Alertas de golpe em chamadas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Avisa sobre sinais fortes de golpe durante uma chamada." }
        )
    }

    fun protectionNotification(context: Context, transcript: String): Notification {
        val detail = transcript.takeLast(110).ifBlank { "Aguardando fala. Ative o viva-voz para testar a captura." }
        val stopIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, CallRecordingService::class.java).setAction(CallRecordingService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(context, PROTECTION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Protecao de chamada ativa")
            .setContentText(detail)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar", stopIntent).build())
            .build()
    }

    fun startProtectionIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        3,
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_START_CALL_PROTECTION)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun riskNotification(context: Context, indicators: List<String>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val intent = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_CALLS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            ALERT_NOTIFICATION_ID,
            Notification.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Possivel golpe detectado")
                .setContentText(indicators.take(3).joinToString())
                .setStyle(Notification.BigTextStyle().bigText(
                    "A conversa menciona ${indicators.take(3).joinToString()}. " +
                        "Nao informe senha, token ou codigo recebido por SMS."
                ))
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
        )
    }
}
