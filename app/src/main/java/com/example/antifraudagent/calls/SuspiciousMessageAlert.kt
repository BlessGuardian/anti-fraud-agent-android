package com.example.antifraudagent.calls

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.antifraudagent.data.remote.FraudAnalysisResult

/** Aviso dispensavel para uma mensagem que o backend classificou como fraude. */
object SuspiciousMessageAlert {
    fun show(context: Context, result: FraudAnalysisResult) {
        val text = result.explanation.ifBlank { "Esta mensagem apresenta sinais de golpe." }
        if (Settings.canDrawOverlays(context)) {
            FraudAlertOverlay(context).show(text, result.score)
        } else {
            CallNotifications.createChannels(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                7303,
                Notification.Builder(context, CallNotifications.ALERT_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("Mensagem suspeita detectada")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}

private class FraudAlertOverlay(private val context: Context) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun show(explanation: String, score: Float) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 30, 36, 28)
            setBackgroundColor(Color.rgb(92, 20, 26))
        }
        panel.addView(TextView(context).apply {
            text = "Mensagem suspeita detectada"
            setTextColor(Color.WHITE)
            textSize = 20f
        })
        panel.addView(TextView(context).apply {
            text = "Risco ${(score * 100).toInt()}%. $explanation"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 14, 0, 16)
        })
        panel.addView(Button(context).apply {
            text = "Entendi, continuar"
            setOnClickListener {
                try { manager.removeView(it.parent as android.view.View) } catch (_: Exception) { }
            }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        try { manager.addView(panel, params) } catch (_: Exception) { }
    }
}
