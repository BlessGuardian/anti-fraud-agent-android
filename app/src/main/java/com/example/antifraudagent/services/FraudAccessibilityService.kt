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

/**
 * Camada 2 de captura de mensagens.
 *
 * Complementa o [MessageListenerService] cobrindo o cenário em que o usuário
 * está com o app de mensagens aberto na tela (notificações não são disparadas
 * nesse caso, então o NotificationListenerService ficaria cego).
 *
 * Fluxo:
 *  1. O Android notifica o serviço a cada mudança de conteúdo na tela.
 *  2. Filtramos apenas os apps monitorados.
 *  3. Aplicamos um debounce de 600 ms para evitar processamento excessivo.
 *  4. Percorremos a árvore de nós de acessibilidade extraindo textos de mensagem.
 *  5. Deduplicamos usando um hash (packageName + texto) para não reprocessar
 *     mensagens que já estão visíveis na tela desde o último evento.
 *  6. Logamos e (no futuro) encaminhamos para o FraudAnalyzer.
 */
class FraudAccessibilityService : AccessibilityService() {

    // -------------------------------------------------------------------------
    // Constantes
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "FraudAccessibility"

        /** Tempo de espera (ms) após o último evento antes de processar a tela. */
        private const val DEBOUNCE_MS = 600L

        /** Comprimento mínimo de texto para ser considerado uma mensagem real. */
        private const val MIN_MESSAGE_LENGTH = 3

        /** Limite de hashes guardados — evita crescimento ilimitado de memória. */
        private const val MAX_SEEN_SIZE = 500

        /** Verifica se o serviço está ativo nas configurações do sistema. */
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

    // -------------------------------------------------------------------------
    // Apps monitorados — mesma lista do MessageListenerService (exceto SMS,
    // que não tem interface própria de chat no mesmo formato)
    // -------------------------------------------------------------------------

    private val targetApps = setOf(
        "com.whatsapp",
        "org.telegram.messenger",
        "com.instagram.android",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging"
    )

    // -------------------------------------------------------------------------
    // Estado interno
    // -------------------------------------------------------------------------

    /** Hashes das mensagens já vistas nesta sessão do app ativo. */
    private val seenMessages = mutableSetOf<Int>()

    /** Package do app que estava na tela no último evento processado. */
    private var currentPackage = ""

    /** Handler + Runnable para o debounce. */
    private val handler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    // -------------------------------------------------------------------------
    // Lifecycle do AccessibilityService
    // -------------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "AccessibilityService conectado ✅")
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrompido")
        cancelDebounce()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelDebounce()
        seenMessages.clear()
    }

    // -------------------------------------------------------------------------
    // Processamento de eventos
    // -------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // Ignora qualquer app fora da lista de interesse
        if (pkg !in targetApps) return

        // Se o usuário trocou de app monitorado, reinicia a deduplicação
        if (pkg != currentPackage) {
            currentPackage = pkg
            seenMessages.clear()
            Log.d(TAG, "App monitorado em foco: $pkg")
        }

        // Só processa eventos que indicam mudança de conteúdo visível
        val relevantEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        if (!relevantEvent) return

        // Debounce: cancela processamento anterior e agenda um novo daqui a DEBOUNCE_MS
        cancelDebounce()
        debounceRunnable = Runnable { processWindow(pkg) }.also {
            handler.postDelayed(it, DEBOUNCE_MS)
        }
    }

    // -------------------------------------------------------------------------
    // Extração de mensagens da árvore de acessibilidade
    // -------------------------------------------------------------------------

    /**
     * Percorre a árvore de nós da janela ativa e extrai textos que parecem
     * ser mensagens reais (não elementos de interface como botões, rótulos, etc.).
     */
    private fun processWindow(packageName: String) {
        val root = rootInActiveWindow ?: return

        val messages = mutableListOf<String>()
        collectMessages(root, messages)

        for (msg in messages) {
            val fingerprint = (packageName + msg).hashCode()
            if (fingerprint !in seenMessages) {
                // Evita crescimento ilimitado do set
                if (seenMessages.size >= MAX_SEEN_SIZE) seenMessages.clear()
                seenMessages.add(fingerprint)

                Log.d(TAG, "[$packageName] Nova mensagem capturada: \"$msg\"")

                // TODO (Chat 5): encaminhar para FraudAnalyzer.analyze(packageName, msg)
            }
        }
    }

    /**
     * Percorre recursivamente a sub-árvore de [node] e adiciona em [results]
     * os textos de nós folha que parecem ser mensagens reais.
     */
    private fun collectMessages(node: AccessibilityNodeInfo?, results: MutableList<String>) {
        if (node == null) return

        val text = node.text?.toString()?.trim()

        // Nó folha com texto de tamanho adequado → candidato a mensagem
        if (node.childCount == 0 && !text.isNullOrBlank() && text.length >= MIN_MESSAGE_LENGTH) {
            if (!isUiChrome(text)) {
                results.add(text)
            }
        }

        // Desce nos filhos
        for (i in 0 until node.childCount) {
            collectMessages(node.getChild(i), results)
        }
    }

    /**
     * Retorna `true` se o texto é claramente um elemento de UI (botão, rótulo,
     * indicador de status), e não uma mensagem do chat.
     *
     * A lista é conservadora de propósito: falsos negativos (enviar algo de UI
     * para o analisador) são preferíveis a falsos positivos (perder mensagens).
     */
    private fun isUiChrome(text: String): Boolean {
        // Textos de 1 caractere quase nunca são mensagens reais
        if (text.length == 1) return true

        val knownLabels = setOf(
            // Indicadores de status do WhatsApp / Telegram / Instagram
            "Digitando...", "Online", "Visto por último", "Gravando áudio...",
            // Botões de ação comuns
            "Enviar", "Anexar", "Câmera", "Emoji", "Áudio", "Figurinha",
            "Pesquisar", "Voltar", "Arquivadas", "Mensagem",
            // Marcadores de leitura
            "✓", "✓✓", "Lida", "Entregue",
            // Outros rótulos genéricos
            "Hoje", "Ontem", "..."
        )

        return text in knownLabels
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun cancelDebounce() {
        debounceRunnable?.let { handler.removeCallbacks(it) }
        debounceRunnable = null
    }
}
