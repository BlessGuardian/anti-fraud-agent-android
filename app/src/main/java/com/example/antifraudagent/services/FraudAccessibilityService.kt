package com.example.antifraudagent.services

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.antifraudagent.data.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FraudAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FraudAccessibility"
        private const val DEBOUNCE_MS = 600L
        private const val MIN_MESSAGE_LENGTH = 3
        private const val MAX_SEEN_SIZE = 500

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, FraudAccessibilityService::class.java)
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(flat)
            while (splitter.hasNext()) {
                val cn = ComponentName.unflattenFromString(splitter.next()) ?: continue
                if (cn == expected) return true
            }
            return false
        }
    }

    private val targetApps = setOf(
        "com.whatsapp",
        "org.telegram.messenger",
        "com.instagram.android",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging"
    )

    private val seenMessages = mutableSetOf<Int>()
    private var currentPackage = ""

    private val handler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var repository: MessageRepository

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = MessageRepository(applicationContext)
        Log.d(TAG, "AccessibilityService conectado ✅")
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrompido")
        cancelDebounce()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelDebounce()
        serviceJob.cancel()
        seenMessages.clear()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in targetApps) return

        if (pkg != currentPackage) {
            currentPackage = pkg
            seenMessages.clear()
            Log.d(TAG, "App monitorado em foco: $pkg")
        }

        val relevantEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        if (!relevantEvent) return

        cancelDebounce()
        debounceRunnable = Runnable { processWindow(pkg) }.also {
            handler.postDelayed(it, DEBOUNCE_MS)
        }
    }

    private fun processWindow(packageName: String) {
        val root = rootInActiveWindow ?: return
        val messages = mutableListOf<String>()
        collectMessages(root, messages)

        for (msg in messages) {
            val fingerprint = (packageName + msg).hashCode()
            if (fingerprint !in seenMessages) {
                if (seenMessages.size >= MAX_SEEN_SIZE) seenMessages.clear()
                seenMessages.add(fingerprint)

                Log.d(TAG, "[$packageName] Nova mensagem capturada: \"$msg\"")

                // Camada 1 será implementada no Chat 5.
                // Score fixo de 0.5 para testar o fluxo de persistência.
                val layer1Score = 0.5f // TODO (Chat 5): substituir por LocalHeuristicAnalyzer.analyze(msg)

                serviceScope.launch {
                    repository.saveIfSuspicious(
                        sender      = packageName,
                        content     = msg,
                        packageName = packageName,
                        layer1Score = layer1Score
                    )
                }
            }
        }
    }

    private fun collectMessages(node: AccessibilityNodeInfo?, results: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        if (node.childCount == 0 && !text.isNullOrBlank() && text.length >= MIN_MESSAGE_LENGTH) {
            if (!isUiChrome(text)) results.add(text)
        }
        for (i in 0 until node.childCount) {
            collectMessages(node.getChild(i), results)
        }
    }

    private fun isUiChrome(text: String): Boolean {
        if (text.length == 1) return true
        val knownLabels = setOf(
            "Digitando...", "Online", "Visto por último", "Gravando áudio...",
            "Enviar", "Anexar", "Câmera", "Emoji", "Áudio", "Figurinha",
            "Pesquisar", "Voltar", "Arquivadas", "Mensagem",
            "✓", "✓✓", "Lida", "Entregue",
            "Hoje", "Ontem", "..."
        )
        return text in knownLabels
    }

    private fun cancelDebounce() {
        debounceRunnable?.let { handler.removeCallbacks(it) }
        debounceRunnable = null
    }
}
