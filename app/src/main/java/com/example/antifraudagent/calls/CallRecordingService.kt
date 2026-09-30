package com.example.antifraudagent.calls

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.antifraudagent.data.local.call.CallTranscript
import com.example.antifraudagent.data.local.call.CallTranscriptSyncStatus
import com.example.antifraudagent.data.remote.FraudAnalysisResult
import com.example.antifraudagent.data.remote.HybridVerdict
import com.example.antifraudagent.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Protege UMA ligacao recebida e atendida, do atendimento ate o fim. Iniciado pelo
 * [CallStateMonitor]; nunca por botao.
 *
 * Fluxo: servico de microfone em primeiro plano -> pede o viva-voz (aviso some quando o
 * alto-falante liga) -> captura + transcricao continua -> regras locais em cada trecho (alerta
 * imediato) -> checkpoints no servidor (LLM) com limite por ligacao -> fim da ligacao: para tudo,
 * analise final e registro no historico.
 */
class CallRecordingService : Service(), CallSpeechTranscriber.Listener {

    private class Session(
        val id: String,
        val startedAt: Long,
        val callerNumber: String?
    ) {
        val segments = mutableListOf<String>()
        var partial = ""
        var localRisk = CallRiskResult.NONE
        var reachedMedium = false
        var escalationPending = false
        var checkpointsSent = 0
        var checkpointInFlight = false
        var checkpointDirty = false
        var lowPeriodicSent = false
        var lastCheckpointAt = 0L
        var segmentsAtLastCheckpoint = 0
        var wordsAtLastServer = 0
        var lastServer: FraudAnalysisResult? = null
        var serverConfirmedFraud = false
        var alertAt: Long? = null
        var alertSource: String? = null
        var alertReason: String? = null
        var alertExcerpt: String? = null
        var route = CallAudioRoute.UNKNOWN
        var speakerUsed = false
        var hearingSeconds = 0
        var silencedSeconds = 0
        var engineSwitched = false
        var issue: String? = null
        var notInCallChecks = 0

        val fullText: String get() = segments.joinToString(" ").trim()
        val words: Int get() = wordCount(fullText)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var audioManager: AudioManager
    private lateinit var repository: CallTranscriptRepository
    private var capture: CallAudioCapture? = null
    private var transcriber: CallSpeechTranscriber? = null
    private var session: Session? = null
    private var finalizing = false
    private var lastNotificationAt = 0L

    private val routeListener = AudioManager.OnCommunicationDeviceChangedListener { refreshRoute() }
    private val routePoll = object : Runnable {
        override fun run() {
            refreshRoute()
            handler.postDelayed(this, ROUTE_POLL_MS)
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            checkSession()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CallNotifications.createChannels(this)
        audioManager = getSystemService(AudioManager::class.java)
        repository = CallTranscriptRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CALL_ANSWERED -> {
                if (session == null && !finalizing) begin(intent.getStringExtra(EXTRA_CALLER))
            }
            else -> if (session == null && !finalizing) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(callerNumber: String?) {
        val startedAt = System.currentTimeMillis()
        val newSession = Session(UUID.randomUUID().toString(), startedAt, callerNumber)
        CallProtectionState.start(newSession.id, startedAt, callerNumber)
        try {
            ServiceCompat.startForeground(
                this,
                CallNotifications.PROTECTION_NOTIFICATION_ID,
                CallNotifications.protectionNotification(this, CallProtectionState.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (error: Exception) {
            // Android 14+: sem acessibilidade ativa/visivel o sistema recusa microfone em segundo plano.
            Log.e(TAG, "Sistema recusou o servico de microfone", error)
            CallOverlays.hideSpeakerPrompt()
            CallNotifications.issueNotification(
                this,
                "O Android não liberou o microfone. Verifique se a acessibilidade do BlessGuardian está ativa."
            )
            CallProtectionState.finish("O Android não liberou o microfone para esta ligação.")
            isRunning = false
            stopSelf()
            return
        }
        session = newSession
        scope.launch {
            repository.save(
                CallTranscript(
                    sessionId = newSession.id,
                    content = "",
                    startedAt = startedAt,
                    endedAt = startedAt,
                    callerNumber = callerNumber,
                    syncStatus = CallTranscriptSyncStatus.FAILED,
                    syncError = "Ligação em andamento"
                )
            )
        }

        audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, routeListener)
        handler.post(routePoll)
        handler.postDelayed(watchdog, WATCHDOG_MS)

        val audio = CallAudioCapture { rms, silenced -> handler.post { onAudioLevel(rms, silenced) } }
        val speech = CallSpeechTranscriber(this, audio, this)
        capture = audio
        transcriber = speech
        if (speech.usesOwnAudio && !audio.start()) {
            reportIssue("Não foi possível acessar o microfone durante a ligação.")
        }
        speech.start()
    }

    // --- Rota de audio / viva-voz ---------------------------------------------------------------

    private fun refreshRoute() {
        val current = session ?: return
        val device = try { audioManager.communicationDevice } catch (_: Exception) { null }
        @Suppress("DEPRECATION")
        val speakerFlag = audioManager.isSpeakerphoneOn
        val route = when {
            device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || speakerFlag -> CallAudioRoute.SPEAKER
            device != null && device.type in HEADSET_TYPES -> CallAudioRoute.HEADSET
            device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallAudioRoute.EARPIECE
            else -> CallAudioRoute.UNKNOWN
        }
        if (route == current.route) return
        val previous = current.route
        current.route = route
        if (route == CallAudioRoute.SPEAKER) {
            current.speakerUsed = true
            CallOverlays.hideSpeakerPrompt()
            CallProtectionState.update { it.copy(route = route, status = "Viva-voz ativo. Ligação protegida.") }
        } else {
            val shouldPrompt = !speakerPromptDismissed && current.alertAt == null &&
                (previous == CallAudioRoute.SPEAKER || route == CallAudioRoute.HEADSET || !CallOverlays.isSpeakerPromptVisible)
            if (shouldPrompt) {
                CallOverlays.showSpeakerPrompt(this, route) { speakerPromptDismissed = true }
            }
            CallProtectionState.update {
                it.copy(
                    route = route,
                    status = if (route == CallAudioRoute.HEADSET) "Com fone, a proteção só ouve você."
                    else "Ative o viva-voz para a proteção ouvir quem ligou."
                )
            }
        }
        refreshNotification(force = true)
    }

    // --- Audio / transcricao --------------------------------------------------------------------

    private fun onAudioLevel(rms: Double, silenced: Boolean) {
        val current = session ?: return
        if (silenced) {
            current.silencedSeconds++
            if (current.silencedSeconds == SILENCED_ALERT_SECONDS) {
                reportIssue("O Android bloqueou o microfone nesta ligação. Confira se a acessibilidade do BlessGuardian está ativa.")
            }
        } else if (rms >= SPEECH_RMS) {
            current.hearingSeconds++
        }
        val hearing = !silenced && rms >= SPEECH_RMS
        if (CallProtectionState.state.value.hearingAudio != hearing) {
            CallProtectionState.update { it.copy(hearingAudio = hearing) }
        }
    }

    override fun onPartial(text: String) {
        val current = session ?: return
        current.partial = text
        CallProtectionState.update { it.copy(partial = text) }
        evaluateLocal(current, "${current.fullText} $text", excerpt = text)
        refreshNotification()
    }

    override fun onSegment(text: String) {
        val current = session ?: return
        current.segments += text
        current.partial = ""
        val full = current.fullText.take(MAX_TRANSCRIPT_CHARS)
        CallProtectionState.update { it.copy(transcript = full, partial = "", status = statusLine(current)) }
        evaluateLocal(current, full, excerpt = text)
        maybeCheckpoint(current)
        refreshNotification()
    }

    override fun onTranscriberIssue(message: String) = reportIssue(message)

    private fun reportIssue(message: String) {
        val current = session
        if (current != null && current.issue == null) current.issue = message
        CallProtectionState.status(message)
        Log.w(TAG, message)
    }

    // --- Analise --------------------------------------------------------------------------------

    private fun evaluateLocal(current: Session, text: String, excerpt: String) {
        if (finalizing) return
        val risk = CallRiskRules.analyze(text)
        if (risk.score > current.localRisk.score) {
            current.localRisk = risk
            CallProtectionState.update { it.copy(riskLevel = risk.level, indicators = risk.indicators) }
        }
        if (risk.isHighRisk && current.alertAt == null) {
            raiseAlert(
                current,
                source = "local",
                reason = "A conversa tem sinais de golpe: ${risk.indicators.take(3).joinToString()}.",
                excerpt = excerpt
            )
        }
        if (risk.level != CallRiskLevel.LOW && !current.reachedMedium) {
            current.reachedMedium = true
            current.escalationPending = true
            maybeCheckpoint(current)
        }
    }

    /**
     * Cada POST /detect vira um registro no historico, entao os checkpoints sao limitados:
     * no maximo [MAX_CHECKPOINTS] por ligacao (+1 analise final), um por vez.
     */
    private fun maybeCheckpoint(current: Session) {
        if (finalizing) return
        if (!SettingsRepository.getInstance(this).isCaptureEnabled()) return
        if (current.checkpointInFlight) {
            current.checkpointDirty = true
            return
        }
        if (current.checkpointsSent >= MAX_CHECKPOINTS || current.serverConfirmedFraud) return
        val words = current.words
        val now = SystemClock.elapsedRealtime()
        val escalation = current.escalationPending && words >= MIN_WORDS_ESCALATION
        val periodic = now - current.lastCheckpointAt >= PERIODIC_MS &&
            words - current.wordsAtLastServer >= MIN_NEW_WORDS_PERIODIC &&
            (current.localRisk.level != CallRiskLevel.LOW || !current.lowPeriodicSent)
        if (!escalation && !periodic) return

        val startIndex = current.segmentsAtLastCheckpoint
        val endIndex = current.segments.size
        current.checkpointInFlight = true
        current.escalationPending = false
        CallProtectionState.update { it.copy(analyzing = true) }
        scope.launch {
            val result = repository.analyzeCheckpoint(CallTranscriptRepository.windowForCheckpoint(current.fullText))
            current.checkpointInFlight = false
            CallProtectionState.update { it.copy(analyzing = false) }
            if (session !== current || finalizing) return@launch
            if (result != null) {
                current.checkpointsSent++
                current.lastCheckpointAt = SystemClock.elapsedRealtime()
                current.segmentsAtLastCheckpoint = endIndex
                current.wordsAtLastServer = words
                current.lastServer = result
                if (current.localRisk.level == CallRiskLevel.LOW) current.lowPeriodicSent = true
                if (serverSaysFraud(result)) {
                    current.serverConfirmedFraud = true
                    val reason = serverReason(result)
                    val excerpt = current.segments.subList(startIndex, endIndex).joinToString(" ").takeLast(300)
                    if (current.alertAt == null) {
                        raiseAlert(current, source = "servidor", reason = reason, excerpt = excerpt)
                    } else {
                        current.alertReason = reason
                        CallProtectionState.update { it.copy(alertReason = reason) }
                    }
                }
            }
            if (current.checkpointDirty) {
                current.checkpointDirty = false
                maybeCheckpoint(current)
            }
        }
    }

    private fun raiseAlert(current: Session, source: String, reason: String, excerpt: String?) {
        current.alertAt = System.currentTimeMillis()
        current.alertSource = source
        current.alertReason = reason
        current.alertExcerpt = excerpt?.trim()?.takeIf { it.isNotBlank() }?.take(400)
        CallOverlays.hideSpeakerPrompt()
        CallOverlays.showScamAlert(this, reason, current.alertExcerpt)
        CallNotifications.riskNotification(this, reason, current.alertExcerpt)
        vibrate()
        CallProtectionState.update { it.copy(alertShown = true, alertReason = reason) }
        refreshNotification(force = true)
    }

    private fun vibrate() {
        try {
            getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 700), -1)
            )
        } catch (_: Exception) { }
    }

    // --- Vigia e encerramento -------------------------------------------------------------------

    private fun checkSession() {
        val current = session ?: return
        val inCall = try {
            getSystemService(TelecomManager::class.java)?.isInCall ?: true
        } catch (_: SecurityException) {
            true
        }
        current.notInCallChecks = if (inCall) 0 else current.notInCallChecks + 1
        val tooLong = System.currentTimeMillis() - current.startedAt > MAX_SESSION_MS
        if (current.notInCallChecks >= 2 || tooLong) {
            finish(if (tooLong) "Tempo máximo de proteção atingido" else "Ligação encerrada")
            return
        }
        // Ha som chegando mas nenhum trecho transcrito: o reconhecedor atual nao usa nosso audio.
        if (current.segments.isEmpty() && current.hearingSeconds >= NO_TRANSCRIPT_SWITCH_SECONDS) {
            if (!current.engineSwitched) {
                current.engineSwitched = true
                transcriber?.switchEngine()
            } else if (current.hearingSeconds >= NO_TRANSCRIPT_SWITCH_SECONDS * 2) {
                reportIssue("O áudio está chegando, mas a transcrição não funcionou neste aparelho.")
            }
        }
    }

    fun finish(reason: String) {
        val current = session
        if (current == null) {
            if (!finalizing) stopSelf()
            return
        }
        if (finalizing) return
        finalizing = true
        handler.removeCallbacks(routePoll)
        handler.removeCallbacks(watchdog)
        try { audioManager.removeOnCommunicationDeviceChangedListener(routeListener) } catch (_: Exception) { }
        CallOverlays.hideSpeakerPrompt()
        CallProtectionState.update { it.copy(status = "$reason. Analisando a conversa...", analyzing = true) }

        val speech = transcriber
        val audio = capture
        transcriber = null
        capture = null
        if (speech != null) {
            speech.stop {
                audio?.stop()
                complete(current, reason)
            }
        } else {
            audio?.stop()
            complete(current, reason)
        }
    }

    private fun complete(current: Session, reason: String) {
        val full = current.fullText.take(MAX_TRANSCRIPT_CHARS)
        val words = wordCount(full)
        val sendingAllowed = SettingsRepository.getInstance(this).isCaptureEnabled()
        val sendText = when {
            !sendingAllowed || words < MIN_WORDS_FINAL -> null
            current.lastServer == null || words - current.wordsAtLastServer >= MIN_NEW_WORDS_FINAL ->
                CallTranscriptRepository.windowForFinal(full)
            else -> null
        }
        val issue = current.issue ?: when {
            !current.speakerUsed && current.route != CallAudioRoute.SPEAKER -> "O viva-voz não foi ativado; a proteção pode não ter ouvido quem ligou."
            words == 0 -> "Nenhuma fala foi transcrita."
            else -> null
        }
        var entry = CallTranscript(
            sessionId = current.id,
            content = full,
            startedAt = current.startedAt,
            endedAt = System.currentTimeMillis(),
            riskScore = current.localRisk.score,
            isFraud = current.alertAt != null,
            syncStatus = if (sendText != null) CallTranscriptSyncStatus.PENDING else CallTranscriptSyncStatus.ANALYZED,
            syncError = when {
                sendText != null -> null
                !sendingAllowed -> "Envio pausado no Perfil; analisada só no aparelho"
                words < MIN_WORDS_FINAL -> "Conversa curta; analisada só no aparelho"
                else -> null
            },
            callerNumber = current.callerNumber,
            speakerUsed = current.speakerUsed,
            localIndicators = current.localRisk.indicators.joinToString("\n").ifBlank { null },
            alertExcerpt = current.alertExcerpt,
            alertAtMillis = current.alertAt,
            alertSource = current.alertSource,
            captureIssue = issue
        )
        current.lastServer?.let { entry = entry.withServerResult(it, keepAlert = true) }
        if (current.alertReason != null && (entry.verdict.isNullOrBlank() || !current.serverConfirmedFraud)) {
            // Alerta local com servidor dizendo "seguro": o motivo do alerta continua sendo o veredito.
            entry = entry.copy(verdict = current.alertReason)
        }

        CallScope.launch {
            val saved = repository.finalize(entry, sendText)
            if (saved.isFraud == true && current.alertAt == null) {
                // O golpe so ficou claro na analise final: avisa mesmo com a ligacao encerrada.
                CallNotifications.riskNotification(
                    applicationContext,
                    "A ligação que você acabou de atender tinha sinais de golpe. ${saved.verdict.orEmpty()}".trim(),
                    saved.content.takeLast(200)
                )
            }
            handler.post {
                CallProtectionState.finish(
                    when {
                        saved.isFraud == true -> "Ligação encerrada. Golpe detectado e registrado."
                        words == 0 -> "Ligação encerrada. Nenhuma fala foi transcrita."
                        else -> "Ligação encerrada. Nenhum sinal de golpe."
                    }
                )
                session = null
                isRunning = false
                ServiceCompat.stopForeground(this@CallRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun refreshNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) return
        lastNotificationAt = now
        getSystemService(android.app.NotificationManager::class.java).notify(
            CallNotifications.PROTECTION_NOTIFICATION_ID,
            CallNotifications.protectionNotification(this, CallProtectionState.state.value)
        )
    }

    private fun statusLine(current: Session): String = when {
        current.alertAt != null -> "Alerta de golpe emitido. Continue atento."
        current.route == CallAudioRoute.SPEAKER -> "Viva-voz ativo. Ligação protegida."
        else -> "Transcrevendo. Ative o viva-voz para ouvir quem ligou."
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val current = session
        if (current != null && !finalizing) {
            // Encerrado pelo sistema: salva o que houver, sem esperar o reconhecedor.
            transcriber = null
            capture?.stop()
            capture = null
            finalizing = true
            complete(current, "Proteção interrompida")
        }
        CallOverlays.hideSpeakerPrompt()
        if (instance === this) instance = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallRecordingService"
        const val ACTION_CALL_ANSWERED = "com.example.antifraudagent.action.CALL_ANSWERED"
        private const val EXTRA_CALLER = "caller"

        private const val MAX_TRANSCRIPT_CHARS = 20_000
        private const val MAX_CHECKPOINTS = 2
        private const val MIN_WORDS_ESCALATION = 12
        private const val MIN_NEW_WORDS_PERIODIC = 40
        private const val PERIODIC_MS = 60_000L
        private const val MIN_WORDS_FINAL = 8
        private const val MIN_NEW_WORDS_FINAL = 5
        private const val ROUTE_POLL_MS = 1_500L
        private const val WATCHDOG_MS = 10_000L
        private const val MAX_SESSION_MS = 2 * 60 * 60 * 1000L
        private const val NOTIFICATION_THROTTLE_MS = 2_000L
        private const val SPEECH_RMS = 150.0
        private const val SILENCED_ALERT_SECONDS = 5
        private const val NO_TRANSCRIPT_SWITCH_SECONDS = 25

        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID
        )

        /** Escopo do processo para o registro final sobreviver ao fim do servico. */
        private val CallScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile var isRunning = false
            private set

        /** O usuario tocou "Entendi" no aviso do viva-voz nesta ligacao. */
        @Volatile var speakerPromptDismissed = false

        @Volatile private var instance: CallRecordingService? = null

        fun startForAnsweredCall(context: Context, callerNumber: String?) {
            if (isRunning) return
            isRunning = true
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CallRecordingService::class.java)
                        .setAction(ACTION_CALL_ANSWERED)
                        .putExtra(EXTRA_CALLER, callerNumber)
                )
            } catch (error: Exception) {
                isRunning = false
                CallOverlays.hideSpeakerPrompt()
                Log.e(TAG, "Nao foi possivel iniciar a protecao da ligacao", error)
                CallNotifications.createChannels(context)
                CallNotifications.issueNotification(
                    context,
                    "O Android não permitiu iniciar a proteção. Verifique se a acessibilidade do BlessGuardian está ativa."
                )
            }
        }

        /** Fim da ligacao (IDLE). */
        fun stop(context: Context) {
            Handler(Looper.getMainLooper()).post {
                instance?.finish("Ligação encerrada") ?: run { isRunning = false }
            }
        }

        private fun serverSaysFraud(result: FraudAnalysisResult): Boolean =
            result.isFraud || result.hybrid?.status?.let { !it.equals("SEGURO", ignoreCase = true) } == true

        private fun serverReason(result: FraudAnalysisResult): String =
            result.hybrid?.justification?.takeIf { it.isNotBlank() }
                ?: result.verdict.takeIf { it.isNotBlank() && HybridVerdict.parse(it) == null }
                ?: result.indicators.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(prefix = "Sinais: ")
                ?: "A análise da conversa indicou tentativa de golpe."

        fun wordCount(text: String): Int =
            text.split(Regex("\\s+")).count { it.isNotBlank() }
    }
}
