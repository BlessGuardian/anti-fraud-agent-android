package com.example.antifraudagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.antifraudagent.calls.CallTerminator
import com.example.antifraudagent.calls.SuspiciousMessageAlert
import com.example.antifraudagent.data.remote.FraudAnalysisResult
import com.example.antifraudagent.data.remote.HybridVerdict

/**
 * SO NO BUILD DEBUG: testa a protecao reforcada sem precisar de um golpe real.
 *
 *   Encerrar a ligacao em andamento:
 *   adb shell am broadcast -n com.example.antifraudagent/.debug.DebugEndCallReceiver
 *
 *   Simular mensagem de ALTO risco no WhatsApp (pop-up e, com a protecao reforcada, fecha o app):
 *   adb shell am broadcast -n com.example.antifraudagent/.debug.DebugEndCallReceiver --es mode message
 */
class DebugEndCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("mode")) {
            "message" -> {
                SuspiciousMessageAlert.show(
                    context = context,
                    result = FraudAnalysisResult(
                        isFraud = true,
                        score = 0f,
                        category = "phishing",
                        explanation = "",
                        dbSynced = true,
                        hybrid = HybridVerdict("FRAUDE", "Fraude", "Fraude", "Teste: pedido urgente de Pix com link suspeito.")
                    ),
                    content = "[TESTE] Sua conta foi bloqueada. Clique no link e pague a taxa via Pix.",
                    sourceName = "WHATSAPP",
                    capturedAt = System.currentTimeMillis()
                )
                Log.i("DebugEndCall", "Alerta de mensagem simulado")
            }
            else -> Log.i("DebugEndCall", "Resultado: ${CallTerminator.endCall(context)}")
        }
    }
}
