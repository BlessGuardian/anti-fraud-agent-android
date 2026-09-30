package com.example.antifraudagent.calls

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Unico cliente de microfone durante a ligacao.
 *
 * Por que assim: durante uma ligacao de operadora o Android silencia a captura de apps comuns.
 * A excecao e o app que tem um servico de acessibilidade ativo, e so com a fonte
 * VOICE_RECOGNITION (AudioPolicyService.updateUidStates_l; CDD 5.4.5). O reconhecedor de voz
 * do sistema grava no processo DELE (outro app), entao seria silenciado: por isso quem grava
 * somos nos, e o audio e entregue ao reconhecedor por um pipe (EXTRA_AUDIO_SOURCE).
 */
class CallAudioCapture(
    /** Chamado ~1x por segundo (thread de audio) com o volume medio e se o sistema silenciou. */
    private val onLevel: (rms: Double, silenced: Boolean) -> Unit
) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val sink = AtomicReference<OutputStream?>(null)

    /** Troca o destino do audio (pipe do reconhecedor). Fecha o anterior. */
    fun setSink(out: OutputStream?) {
        sink.getAndSet(out)?.let { closeQuietly(it) }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO e verificado antes de iniciar a sessao.
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return false
        val created = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                maxOf(minBuffer * 4, SAMPLE_RATE * 2)
            )
        } catch (error: Exception) {
            Log.w(TAG, "AudioRecord indisponivel", error)
            return false
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            return false
        }
        try {
            created.startRecording()
        } catch (error: Exception) {
            Log.w(TAG, "Falha ao iniciar gravacao", error)
            created.release()
            return false
        }
        record = created
        running = true
        thread = Thread({ loop(created) }, "bless-call-audio").apply { start() }
        return true
    }

    private fun loop(rec: AudioRecord) {
        val frame = ShortArray(FRAME_SAMPLES)
        val bytes = ByteBuffer.allocate(FRAME_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
        var sumSquares = 0.0
        var samples = 0
        var frames = 0
        while (running) {
            val read = rec.read(frame, 0, frame.size)
            if (read <= 0) {
                if (read < 0) Log.w(TAG, "AudioRecord.read retornou $read")
                continue
            }
            for (i in 0 until read) {
                val v = frame[i].toDouble()
                sumSquares += v * v
            }
            samples += read
            frames++

            sink.get()?.let { out ->
                bytes.clear()
                bytes.asShortBuffer().put(frame, 0, read)
                try {
                    out.write(bytes.array(), 0, read * 2)
                } catch (_: IOException) {
                    // O reconhecedor fechou o pipe (fim de sessao); o proximo pipe chega por setSink.
                    sink.compareAndSet(out, null)
                    closeQuietly(out)
                }
            }

            if (frames >= FRAMES_PER_REPORT) {
                val rms = if (samples > 0) sqrt(sumSquares / samples) else 0.0
                val silenced = rec.activeRecordingConfiguration?.isClientSilenced == true
                onLevel(rms, silenced)
                sumSquares = 0.0
                samples = 0
                frames = 0
            }
        }
    }

    fun stop() {
        running = false
        thread?.join(600)
        thread = null
        record?.let {
            try { it.stop() } catch (_: Exception) { }
            it.release()
        }
        record = null
        setSink(null)
    }

    private fun closeQuietly(out: OutputStream) {
        try { out.close() } catch (_: Exception) { }
    }

    companion object {
        private const val TAG = "CallAudioCapture"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SAMPLES = 320 // 20 ms
        private const val FRAMES_PER_REPORT = 50 // ~1 s
    }
}

/**
 * Reconhecimento continuo em pt-BR alimentado pelo [CallAudioCapture]. Usa a sessao segmentada
 * (API 33+): o reconhecedor devolve trechos enquanto houver audio, sem os buracos de reiniciar
 * o SpeechRecognizer a cada frase. Todos os metodos rodam na thread principal.
 */
class CallSpeechTranscriber(
    private val context: Context,
    private val capture: CallAudioCapture,
    private val listener: Listener
) {
    interface Listener {
        fun onPartial(text: String)
        fun onSegment(text: String)
        fun onTranscriberIssue(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var useOnDevice = false
    private var active = false
    private var errorStreak = 0
    private var readSide: ParcelFileDescriptor? = null
    private val restartRunnable = Runnable { listen() }

    /** Reconhecedor usa o nosso audio (pipe). Abaixo da API 33 ele abriria o proprio microfone. */
    val usesOwnAudio: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context) &&
            !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
        ) {
            listener.onTranscriberIssue("Reconhecimento de voz indisponível neste aparelho.")
            return false
        }
        active = true
        useOnDevice = usesOwnAudio && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        createRecognizer()
        listen()
        return true
    }

    /** Alterna entre o reconhecedor do aparelho e o padrao (usado quando um deles nao transcreve). */
    fun switchEngine() {
        if (!active || !usesOwnAudio) return
        val onDeviceAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        useOnDevice = !useOnDevice && onDeviceAvailable
        Log.i(TAG, "Trocando reconhecedor; onDevice=$useOnDevice")
        createRecognizer()
        scheduleRestart(RESTART_FAST_MS)
    }

    /** Para de ouvir e entrega o ultimo trecho; [onDone] roda depois do periodo de tolerancia. */
    fun stop(onDone: () -> Unit) {
        active = false
        handler.removeCallbacks(restartRunnable)
        try { recognizer?.stopListening() } catch (_: Exception) { }
        capture.setSink(null) // fim do audio: o reconhecedor fecha o ultimo trecho
        handler.postDelayed({
            destroyRecognizer()
            onDone()
        }, STOP_GRACE_MS)
    }

    private fun createRecognizer() {
        destroyRecognizer()
        recognizer = try {
            if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (error: Exception) {
            Log.w(TAG, "Falha ao criar SpeechRecognizer (onDevice=$useOnDevice)", error)
            if (useOnDevice) {
                useOnDevice = false
                try { SpeechRecognizer.createSpeechRecognizer(context) } catch (_: Exception) { null }
            } else null
        }
        recognizer?.setRecognitionListener(recognitionListener)
    }

    private fun destroyRecognizer() {
        try { recognizer?.cancel() } catch (_: Exception) { }
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
        closeRead()
    }

    private fun listen() {
        if (!active) return
        val engine = recognizer ?: run {
            createRecognizer()
            recognizer ?: return scheduleRestart(RESTART_SLOW_MS)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (!useOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        if (usesOwnAudio) {
            val pipe = try {
                ParcelFileDescriptor.createPipe()
            } catch (error: IOException) {
                Log.w(TAG, "Falha ao criar pipe de audio", error)
                return scheduleRestart(RESTART_SLOW_MS)
            }
            closeRead()
            readSide = pipe[0]
            capture.setSink(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CallAudioCapture.SAMPLE_RATE)
            // A sessao dura enquanto o audio do pipe durar (a ligacao inteira).
            intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        try {
            engine.startListening(intent)
        } catch (error: Exception) {
            Log.w(TAG, "startListening falhou", error)
            createRecognizer()
            scheduleRestart(RESTART_SLOW_MS)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!active) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private fun closeRead() {
        try { readSide?.close() } catch (_: Exception) { }
        readSide = null
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isNotBlank()) listener.onPartial(text)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            errorStreak = 0
            val text = firstResult(segmentResults)
            if (text.isNotBlank()) listener.onSegment(text)
        }

        override fun onEndOfSegmentedSession() {
            scheduleRestart(RESTART_FAST_MS)
        }

        override fun onResults(results: Bundle?) {
            errorStreak = 0
            val text = firstResult(results)
            if (text.isNotBlank()) listener.onSegment(text)
            scheduleRestart(RESTART_FAST_MS)
        }

        override fun onError(error: Int) {
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(RESTART_FAST_MS)

                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (useOnDevice) {
                        // Pacote pt-BR offline ausente: usa o reconhecedor padrao.
                        useOnDevice = false
                        createRecognizer()
                        scheduleRestart(RESTART_FAST_MS)
                    } else {
                        listener.onTranscriberIssue("O reconhecimento de voz não oferece português neste aparelho.")
                        scheduleRestart(RESTART_MAX_MS)
                    }
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    listener.onTranscriberIssue("O reconhecimento de voz não tem permissão de microfone.")

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    createRecognizer()
                    scheduleRestart(RESTART_SLOW_MS)
                }

                else -> {
                    errorStreak++
                    Log.w(TAG, "Erro do reconhecedor $error (sequencia=$errorStreak)")
                    if (errorStreak >= 3) createRecognizer()
                    if (errorStreak == 6) {
                        listener.onTranscriberIssue("A transcrição está falhando; tentando novamente.")
                    }
                    val backoff = (RESTART_SLOW_MS * (1L shl minOf(errorStreak, 4))).coerceAtMost(RESTART_MAX_MS)
                    scheduleRestart(backoff)
                }
            }
        }
    }

    companion object {
        private const val TAG = "CallSpeechTranscriber"
        private const val RESTART_FAST_MS = 150L
        private const val RESTART_SLOW_MS = 600L
        private const val RESTART_MAX_MS = 6_000L
        private const val STOP_GRACE_MS = 1_500L
    }
}
