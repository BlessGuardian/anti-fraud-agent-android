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

            // Higiene/normalizacao/dedup rodam dentro de MessageRepository.saveIfSuspicious()
            // via LocalMessagePreprocessor. O layer1Score continua fixo apenas como valor
            // tecnico para o Room; a pontuacao real de fraude vem do backend Python.
            val layer1Score = 0.5f

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
