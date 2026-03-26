package com.example.antifraudagent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        for (sms in messages) {
            val sender  = sms.displayOriginatingAddress ?: "Desconhecido"
            val message = sms.messageBody ?: ""

            if (message.isBlank()) return

            Log.d("SmsReceiver", "De: $sender | Msg: $message")

            // TODO: encaminhar para FraudAnalyzer na próxima etapa
        }
    }
}