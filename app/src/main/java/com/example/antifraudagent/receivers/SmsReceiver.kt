package com.example.antifraudagent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.antifraudagent.data.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    // BroadcastReceivers têm ciclo de vida muito curto — o escopo precisa de
    // SupervisorJob para não ser cancelado junto com o receiver.
    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val repository = MessageRepository(context.applicationContext)

        for (sms in messages) {
            val sender  = sms.displayOriginatingAddress ?: "Desconhecido"
            val message = sms.messageBody ?: ""

            if (message.isBlank()) continue

            Log.d("SmsReceiver", "De: $sender | Msg: $message")

            // Camada 1 será implementada no Chat 5.
            // Score fixo de 0.5 para testar o fluxo de persistência.
            val layer1Score = 0.5f // TODO (Chat 5): substituir por LocalHeuristicAnalyzer.analyze(message)

            receiverScope.launch {
                repository.saveIfSuspicious(
                    sender      = sender,
                    content     = message,
                    packageName = "sms",
                    layer1Score = layer1Score
                )
            }
        }
    }
}
