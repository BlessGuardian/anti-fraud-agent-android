package com.example.antifraudagent.calls

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.antifraudagent.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class CallRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var recognizer: SpeechRecognizer? = null
    private var recordingFile: File? = null
    private var sessionId: String? = null
    private var startedAt = 0L
    private var observedCall = false
    private var finalizing = false
    private var alertShown = false
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var overlay: CallRiskOverlay

    private val callStateListener = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            if (state != TelephonyManager.CALL_STATE_IDLE) observedCall = true
            if (state == TelephonyManager.CALL_STATE_IDLE && observedCall) finishSession("Chamada encerrada")
        }
    }

    override fun onCreate() {
        super.onCreate()
        CallNotifications.createChannels(this)
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        overlay = CallRiskOverlay(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finishSession("Protecao encerrada pelo usuario")
            else -> if (sessionId == null) startSession()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        if (!SettingsRepository.getInstance(this).isCallProtectionEnabled()) {
            stopSelf()
            return
        }
        sessionId = UUID.randomUUID().toString()
        startedAt = System.currentTimeMillis()
        CallProtectionState.start(sessionId!!, startedAt)
        startForeground(
            CallNotifications.PROTECTION_NOTIFICATION_ID,
            CallNotifications.protectionNotification(this, "")
        )
        try {
            telephonyManager.registerTelephonyCallback(mainExecutor, callStateListener)
        } catch (error: SecurityException) {
            CallProtectionState.status("Permissao de estado do telefone nao concedida")
        }
        startAudioCapture()
        startRecognition()
    }

    @Suppress("DEPRECATION")
    private fun startAudioCapture() {
        try {
            val directory = File(filesDir, "call_audio").apply { mkdirs() }
            recordingFile = File(directory, "${sessionId}.m4a")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(16_000)
                setOutputFile(recordingFile!!.absolutePath)
                prepare()
                start()
            }
            CallProtectionState.status("Audio temporario e transcricao ativos")
        } catch (error: Exception) {
            CallProtectionState.status("Nao foi possivel iniciar a gravacao: ${error.message}")
            recordingFile?.delete()
            recordingFile = null
        }
    }

    private fun startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            CallProtectionState.status("Reconhecimento de voz indisponivel neste aparelho")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { engine ->
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) {
                    if (!finalizing) mainHandler.postDelayed({ beginListening() }, RESTART_RECOGNITION_MS)
                }
                override fun onResults(results: android.os.Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    onTranscript(text)
                    if (!finalizing) mainHandler.postDelayed({ beginListening() }, RESTART_RECOGNITION_MS)
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) CallProtectionState.status("Ouvindo: ${text.take(90)}")
                }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            })
        }
        beginListening()
    }

    private fun beginListening() {
        if (finalizing || recognizer == null) return
        try {
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (_: Exception) {
            CallProtectionState.status("Reconhecimento de voz aguardando o microfone")
        }
    }

    private fun onTranscript(text: String) {
        if (text.isBlank() || finalizing) return
        val risk = CallRiskRules.analyze(text)
        val showAlert = risk.isHighRisk && !alertShown
        if (showAlert) {
            alertShown = true
            CallNotifications.riskNotification(this, risk.indicators)
            overlay.show(risk.indicators)
        }
        CallProtectionState.appendTranscript(text, risk.score, showAlert)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(
            CallNotifications.PROTECTION_NOTIFICATION_ID,
            CallNotifications.protectionNotification(this, CallProtectionState.state.value.transcript)
        )
    }

    private fun finishSession(reason: String) {
        if (finalizing) return
        finalizing = true
        mainHandler.removeCallbacksAndMessages(null)
        try { telephonyManager.unregisterTelephonyCallback(callStateListener) } catch (_: Exception) { }
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        recordingFile?.delete()
        recordingFile = null
        overlay.dismiss()

        val id = sessionId
        val transcript = CallProtectionState.state.value.transcript.trim()
        if (id == null || transcript.isBlank()) {
            CallProtectionState.finish("$reason. Nenhuma fala foi transcrita.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        CallProtectionState.finish("$reason. Enviando transcricao para analise.")
        scope.launch {
            val result = CallTranscriptRepository(applicationContext).finalize(
                sessionId = id,
                transcript = transcript,
                startedAt = startedAt,
                endedAt = System.currentTimeMillis()
            )
            CallProtectionState.status(
                if (result.syncStatus.name == "ANALYZED") "Transcricao analisada e salva no historico"
                else "Transcricao salva localmente; envio pendente"
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (!finalizing && sessionId != null) finishSession("Servico interrompido")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.antifraudagent.action.START_CALL_PROTECTION"
        const val ACTION_STOP = "com.example.antifraudagent.action.STOP_CALL_PROTECTION"
        private const val RESTART_RECOGNITION_MS = 450L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallRecordingService::class.java).setAction(ACTION_START)
            )
        }
    }
}
