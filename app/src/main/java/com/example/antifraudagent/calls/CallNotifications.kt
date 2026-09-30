package com.example.antifraudagent.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.antifraudagent.MainActivity
import com.example.antifraudagent.R

object CallNotifications {
    const val PROTECTION_CHANNEL = "call_protection"
    const val ALERT_CHANNEL = "call_risk_alert"
    const val PROTECTION_NOTIFICATION_ID = 7301
    const val ALERT_NOTIFICATION_ID = 7302
    const val ISSUE_NOTIFICATION_ID = 7304

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                PROTECTION_CHANNEL,
                "Proteção de chamadas",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Aparece enquanto uma ligação atendida está sendo protegida." }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL,
                "Alertas de golpe",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisa sobre sinais de golpe em ligações e mensagens."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            }
        )
    }

    fun protectionNotification(context: Context, snapshot: CallProtectionSnapshot): Notification {
        val title = when {
            snapshot.alertShown -> "Possível golpe nesta ligação"
            snapshot.route == CallAudioRoute.SPEAKER -> "Ligação protegida"
            else -> "Ative o viva-voz para proteger a ligação"
        }
        val detail = snapshot.partial.ifBlank { snapshot.transcript }.takeLast(140).ifBlank { snapshot.status }
        return Notification.Builder(context, PROTECTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(openCallsIntent(context))
            .build()
    }

    fun riskNotification(context: Context, reason: String, excerpt: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val body = buildString {
            append(reason)
            if (!excerpt.isNullOrBlank()) append("\n\n“${excerpt.trim().take(200)}”")
            append("\n\nNão informe senhas, códigos ou dados do cartão e não faça Pix.")
        }
        manager.notify(
            ALERT_NOTIFICATION_ID,
            Notification.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_shield)
                .setContentTitle("⚠ Possível golpe nesta ligação")
                .setContentText(reason)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(openCallsIntent(context))
                .setAutoCancel(true)
                .build()
        )
    }

    /** Algo impediu a protecao desta ligacao (permissao, sistema recusou o microfone...). */
    fun issueNotification(context: Context, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(
            ISSUE_NOTIFICATION_ID,
            Notification.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_shield)
                .setContentTitle("Não foi possível proteger esta ligação")
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(openCallsIntent(context))
                .setAutoCancel(true)
                .build()
        )
    }

    fun openCallsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        4,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_CALLS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
