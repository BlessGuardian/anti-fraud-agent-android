package com.example.antifraudagent.calls

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Alerta opcional sobre a tela da chamada; cai para notificacao quando negado. */
class CallRiskOverlay(private val context: Context) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: LinearLayout? = null

    fun show(indicators: List<String>) {
        if (!Settings.canDrawOverlays(context) || view != null) return
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 30, 36, 28)
            setBackgroundColor(Color.rgb(92, 20, 26))
        }
        panel.addView(TextView(context).apply {
            text = "Possivel golpe detectado"
            setTextColor(Color.WHITE)
            textSize = 20f
        })
        panel.addView(TextView(context).apply {
            text = "Sinais: ${indicators.take(3).joinToString()}. Nao informe senha, token ou codigo por telefone."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 14, 0, 16)
        })
        panel.addView(Button(context).apply {
            text = "Continuar chamada"
            setOnClickListener { dismiss() }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        try {
            manager.addView(panel, params)
            view = panel
        } catch (_: Exception) {
            view = null
        }
    }

    fun dismiss() {
        view?.let {
            try { manager.removeView(it) } catch (_: Exception) { }
        }
        view = null
    }
}
