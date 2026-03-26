package com.example.antifraudagent.services

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MessageListenerService : NotificationListenerService() {

    // Apps que queremos monitorar
    private val targetApps = setOf(
        "com.whatsapp",                // WhatsApp
        "org.telegram.messenger",      // Telegram
        "com.instagram.android",       // Instagram
        "com.google.android.apps.messaging", // SMS Google Messages
        "com.android.mms"              // SMS padrão
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // Ignora apps que não são do nosso interesse
        if (packageName !in targetApps) return

        val extras: Bundle = sbn.notification.extras

        val title   = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Ignora notificações sem conteúdo útil
        if (message.isBlank()) return

        Log.d("MessageListener", "[$packageName] De: $title | Msg: $message")

        // TODO: encaminhar para FraudAnalyzer na próxima etapa
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Por enquanto não precisamos fazer nada aqui
    }
}