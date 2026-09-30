package com.example.antifraudagent.calls

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.antifraudagent.services.FraudAccessibilityService

/**
 * Avisos desenhados por cima da tela da ligacao.
 *
 * Preferimos TYPE_ACCESSIBILITY_OVERLAY (criado com o contexto do servico de acessibilidade):
 * nao depende da permissao "sobrepor apps" e o discador nao consegue esconde-lo. Sem o servico,
 * cai para TYPE_APPLICATION_OVERLAY quando a permissao existe. Sempre na thread principal.
 */
object CallOverlays {
    private const val TAG = "CallOverlays"

    private class Shown(val view: View, val manager: WindowManager)

    private var speakerPrompt: Shown? = null
    private var scamAlert: Shown? = null

    val isSpeakerPromptVisible: Boolean get() = speakerPrompt != null

    fun canShow(context: Context): Boolean =
        FraudAccessibilityService.instance != null || Settings.canDrawOverlays(context)

    fun showSpeakerPrompt(context: Context, route: CallAudioRoute, onDismiss: () -> Unit) {
        hideSpeakerPrompt()
        val host = host(context) ?: return
        val body = if (route == CallAudioRoute.HEADSET) {
            "Com fone conectado, a proteção só ouve você. Para ouvir quem ligou, tire o fone e toque em Alto-falante."
        } else {
            "Para o BlessGuardian ouvir quem ligou e te avisar sobre golpes, toque em Alto-falante na tela da chamada."
        }
        val card = card(context, background = "#14213E", border = "#3D86FF")
        card.addView(title(context, "Ative o viva-voz", "#F4F7FF"))
        card.addView(text(context, body, "#C9D4F5"))
        card.addView(action(context, "Entendi", "#3D86FF") {
            hideSpeakerPrompt()
            onDismiss()
        })
        speakerPrompt = add(host, wrap(context, card), Gravity.TOP)
    }

    fun hideSpeakerPrompt() {
        speakerPrompt?.let { remove(it) }
        speakerPrompt = null
    }

    fun showScamAlert(context: Context, reason: String, excerpt: String?) {
        hideScamAlert()
        val host = host(context) ?: return
        val card = card(context, background = "#3A0F17", border = "#FF4D5E")
        card.addView(title(context, "⚠  Possível GOLPE nesta ligação", "#FFFFFF"))
        card.addView(text(context, reason, "#FFE3E6"))
        if (!excerpt.isNullOrBlank()) {
            card.addView(quote(context, "“${excerpt.trim().take(180)}”"))
        }
        card.addView(text(
            context,
            "Não informe senhas, códigos ou dados do cartão e não faça Pix. Na dúvida, desligue e ligue você mesmo para o número oficial.",
            "#FFC2C9"
        ))
        card.addView(action(context, "Entendi", "#FF4D5E") { hideScamAlert() })
        scamAlert = add(host, wrap(context, card), Gravity.TOP)
    }

    fun hideScamAlert() {
        scamAlert?.let { remove(it) }
        scamAlert = null
    }

    fun dismissAll() {
        hideSpeakerPrompt()
        hideScamAlert()
    }

    private fun host(context: Context): Pair<Context, Int>? {
        FraudAccessibilityService.instance?.let {
            return it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        if (Settings.canDrawOverlays(context)) {
            return context.applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        Log.w(TAG, "Sem servico de acessibilidade nem permissao de sobreposicao; aviso nao exibido")
        return null
    }

    private fun add(host: Pair<Context, Int>, view: View, gravity: Int): Shown? {
        val (context, type) = host
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Nao rouba o foco nem o toque fora do cartao: o usuario continua usando o discador.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            y = dp(context, 56)
        }
        return try {
            manager.addView(view, params)
            Shown(view, manager)
        } catch (error: Exception) {
            Log.w(TAG, "Falha ao exibir aviso sobre a ligacao", error)
            null
        }
    }

    private fun remove(shown: Shown) {
        try { shown.manager.removeView(shown.view) } catch (_: Exception) { }
    }

    private fun wrap(context: Context, card: View): View = FrameLayout(context).apply {
        val pad = dp(context, 12)
        setPadding(pad, 0, pad, 0)
        addView(card)
    }

    private fun card(context: Context, background: String, border: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, 18)
        setPadding(pad, pad, pad, dp(context, 10))
        this.background = GradientDrawable().apply {
            cornerRadius = dp(context, 20).toFloat()
            setColor(Color.parseColor(background))
            setStroke(dp(context, 2), Color.parseColor(border))
        }
        elevation = dp(context, 8).toFloat()
    }

    private fun title(context: Context, value: String, color: String) = TextView(context).apply {
        text = value
        setTextColor(Color.parseColor(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun text(context: Context, value: String, color: String) = TextView(context).apply {
        text = value
        setTextColor(Color.parseColor(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(context, 8), 0, 0)
    }

    private fun quote(context: Context, value: String) = TextView(context).apply {
        text = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        setTypeface(typeface, Typeface.ITALIC)
        val pad = dp(context, 12)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 12).toFloat()
            setColor(Color.parseColor("#5A1A24"))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 10) }
    }

    private fun action(context: Context, label: String, color: String, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            setTextColor(Color.parseColor(color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            val pad = dp(context, 12)
            setPadding(pad, pad, 0, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
