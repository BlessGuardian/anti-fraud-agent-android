package com.example.antifraudagent.calls

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import com.example.antifraudagent.MainActivity
import com.example.antifraudagent.R
import com.example.antifraudagent.data.remote.FraudAnalysisResult
import com.example.antifraudagent.data.remote.HybridVerdict
import com.example.antifraudagent.sourceLabel

/**
 * Alerta de golpe em MENSAGEM, no mesmo padrao da ligacao: pop-up por cima de qualquer app
 * (overlay de acessibilidade), notificacao de alta prioridade e vibracao.
 *
 * Mensagens antigas (fila offline enviada depois) so geram notificacao: um pop-up minutos depois
 * da mensagem chegar confundiria mais do que ajudaria.
 */
object SuspiciousMessageAlert {
    private const val FRESH_WINDOW_MS = 5 * 60 * 1000L

    fun show(
        context: Context,
        result: FraudAnalysisResult,
        content: String,
        sourceName: String,
        capturedAt: Long
    ) {
        val appContext = context.applicationContext
        val origin = sourceLabel(sourceName)
        val reason = reasonFor(result)
        val fresh = System.currentTimeMillis() - capturedAt <= FRESH_WINDOW_MS

        CallNotifications.createChannels(appContext)
        notify(appContext, origin, reason, content)

        if (fresh) {
            Handler(Looper.getMainLooper()).post {
                CallOverlays.showScamAlert(
                    context = appContext,
                    reason = "$origin: $reason",
                    excerpt = content,
                    headline = "⚠  Possível GOLPE nesta mensagem",
                    tips = CallOverlays.MESSAGE_TIPS
                )
            }
            vibrate(appContext)
        }
    }

    private fun reasonFor(result: FraudAnalysisResult): String =
        result.hybrid?.justification?.takeIf { it.isNotBlank() }
            ?: result.verdict.takeIf { it.isNotBlank() && HybridVerdict.parse(it) == null }
            ?: result.indicators.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(prefix = "Sinais: ")
            ?: "Esta mensagem tem sinais de golpe."

    private fun notify(context: Context, origin: String, reason: String, content: String) {
        val body = buildString {
            append(reason)
            append("\n\n“${content.trim().take(220)}”")
            append("\n\n${CallOverlays.MESSAGE_TIPS}")
        }
        val openHistory = PendingIntent.getActivity(
            context,
            5,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_HISTORY)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(NotificationManager::class.java).notify(
            // Um aviso por mensagem (varias podem chegar seguidas).
            MESSAGE_NOTIFICATION_BASE + (content.hashCode() and 0xFFFF),
            Notification.Builder(context, CallNotifications.ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_shield)
                .setContentTitle("⚠ Possível golpe no $origin")
                .setContentText(reason)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(openHistory)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun vibrate(context: Context) {
        try {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)
            )
        } catch (_: Exception) { }
    }

    private const val MESSAGE_NOTIFICATION_BASE = 7400_000
}
