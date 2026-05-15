package com.example.antifraudagent.services

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.antifraudagent.data.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MessageListenerService : NotificationListenerService() {

    private val targetApps = setOf(
        "com.whatsapp",
        "org.telegram.messenger",
        "com.instagram.android",
        "com.google.android.apps.messaging",
        "com.android.mms"
    )

    // Escopo de coroutine vinculado ao ciclo de vida do serviço.
    // SupervisorJob garante que uma falha em um filho não cancela os demais.
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var repository: MessageRepository

    override fun onCreate() {
        super.onCreate()
        repository = MessageRepository(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName !in targetApps) return

        val extras: Bundle = sbn.notification.extras
        val sender  = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (message.isBlank()) return

        Log.d("MessageListener", "[$packageName] De: $sender | Msg: $message")

        // Higiene/normalizacao/dedup rodam dentro de MessageRepository.saveIfSuspicious()
        // via LocalMessagePreprocessor. O layer1Score continua fixo apenas como valor
        // tecnico para o Room; a pontuacao real de fraude vem do backend Python.
        val layer1Score = 0.5f

        serviceScope.launch {
            repository.saveIfSuspicious(
                sender      = sender,
                content     = message,
                packageName = packageName,
                layer1Score = layer1Score
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Nenhuma ação necessária
    }
}
