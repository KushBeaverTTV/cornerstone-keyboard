package org.futo.voiceinput.shared

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.konovalov.vad.Vad
import com.konovalov.vad.config.FrameSize
import com.konovalov.vad.config.Mode
import com.konovalov.vad.config.Model
import com.konovalov.vad.config.SampleRate
import com.konovalov.vad.models.VadModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import org.futo.voiceinput.shared.ggml.InvalidModelException
import org.futo.voiceinput.shared.ggml.ParakeetSherpa
import org.futo.voiceinput.shared.session.RecognizerEvent
import org.futo.voiceinput.shared.session.RecognizerEventSink
import org.futo.voiceinput.shared.session.SessionId
import org.futo.voiceinput.shared.session.SessionPublisher
import org.futo.voiceinput.shared.types.AudioRecognizerListener
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunner
import org.futo.voiceinput.shared.whisper.RecognizerHolder
import org.futo.voiceinput.shared.whisper.isBlankResult
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private fun getRecordingDeviceKind(type: Int): String {
    return when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV_TUNER"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_DOCK_ANALOG -> "DOCK_ANALOG"
        else -> "unknown@${type}"
    }
}

data class RecordingSettings(
    val preferBluetoothMic: Boolean,
    val requestAudioFocus: Boolean,
    val canExpandSpace: Boolean,
    val useVADAutoStop: Boolean
)

data class AudioRecognizerSettings(
    val modelRunConfiguration: MultiModelRunConfiguration,
    val decodingConfiguration: DecodingConfiguration,
    val recordingConfiguration: RecordingSettings
)

class ModelDoesNotExistException(val models: List<ModelLoader>) : Throwable()

class AudioRecognizer(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val modelManager: ModelManager,
    private val listener: AudioRecognizerListener,
    private val settings: AudioRecognizerSettings
) {
    private var isRecording = false
    private var recorder: AudioRecord? = null

    private val modelRunner = MultiModelRunner(modelManager)

    private val canExpandSpace = settings.recordingConfiguration.canExpandSpace
    private val useVAD = settings.recordingConfiguration.useVADAutoStop

    private var floatSamples: FloatBuffer = FloatBuffer.allocate(16000 * 30)
    private var recorderJob: Job? = null
    private var modelJob: Job? = null
    private var loadModelJob: Job? = null
    private var partialDecodeJob: Job? = null
    private var cleanupJob: Job? = null
    private var startAfterCleanupJob: Job? = null
    private val partialDecodeGate = PartialDecodeGate()
    private var partialDecodeSession = 0L
    private var publicationSession: SessionId? = null
    private val sessionPublisher = SessionPublisher(RecognizerEventSink { event ->
        when (event) {
            is RecognizerEvent.Partial -> listener.partialResult(event.text)
            is RecognizerEvent.Finished -> listener.finished(event.text)
            RecognizerEvent.Processing -> listener.processing()
            RecognizerEvent.Cancelled -> listener.cancelled()
            RecognizerEvent.ModelLoadingFailed -> listener.modelLoadingFailed()
        }
    })

    private fun beginPublicationSession(generation: Long): SessionId {
        publicationSession?.let(sessionPublisher::invalidate)
        return SessionId(generation).also { session ->
            sessionPublisher.install(session)
            publicationSession = session
        }
    }

    private fun invalidatePublicationSession() {
        publicationSession?.let(sessionPublisher::invalidate)
        publicationSession = null
    }

    private fun publishTerminal(session: SessionId, event: RecognizerEvent): Boolean {
        val published = sessionPublisher.publishTerminal(session, event)
        if (published && publicationSession == session) publicationSession = null
        return published
    }

    // Streaming partial transcripts (Parakeet path only): while recording, only
    // the audio since the last checkpoint is re-decoded on the inference thread
    // (bounded cost — IncrementalDecodePolicy) and joined onto the checkpoint
    // text, so the live transcript is always complete.
    private var lastPartialDecodeAtMs = 0L
    @Volatile private var lastHeardPartial: String = ""

    // Checkpoint: baseTranscript is the joined transcript of [0, baseSamples).
    @Volatile private var baseTranscript: String = ""
    @Volatile private var baseSamples: Int = 0

    // Buffer position covered by the most recent partial decode that actually
    // COMPLETED. Set only inside the decode callback — never at launch: a
    // decode skipped by the engine's busy-gate or cancelled in flight covers
    // nothing, and the stop path measures undecoded audio from this position.
    @Volatile private var lastDecodeDeliveredAtSamples: Int = 0

    // True when independently decoded checkpoint/tail text contains an ambiguous
    // sentence seam. Stop must then perform one full-context final decode.
    @Volatile private var needsFullReconciliation: Boolean = false

    // Buffer position of the last VAD speech frame (0 = none tracked); trailing
    // non-speech past it is trimmed from the final decode (TrailingTrimPolicy).
    @Volatile private var lastSpeechAtSamples: Int = 0

    // Trimmed end of the decode buffer, computed at finish time.
    private var finalTrimSamples: Int = Int.MAX_VALUE

    // A failing partial decode must never crash or degrade the final decode —
    // one failure disables partials for the rest of this recording session.
    private var partialDecodesDisabled = false

    // Set while a session (record..reset) is live so RecognizerHolder.releaseAll()
    // under memory pressure won't close the engine mid-session.
    private var sessionMarked = false

    private var focusRequest: AudioFocusRequest? = null

    private var communicationDevice = "unknown"

    private fun focusAudio() {
        unfocusAudio()

        if(!settings.recordingConfiguration.requestAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                focusRequest =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .build()
                focusRequest?.let { audioManager.requestAudioFocus(it) }
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unfocusAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                focusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
                focusRequest = null
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isBluetoothAvailable(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.availableCommunicationDevices

                return devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } != null
            }
        } catch(e: Exception) {
            // SecurityException = BLUETOOTH_CONNECT not granted (API 31+)
            Log.w("AudioRecognizer", "Bluetooth availability check failed (BLUETOOTH_CONNECT granted?)", e)
        }

        return false
    }

    private fun setCommunicationDevice(preferBluetoothMic: Boolean): Pair<Boolean, String> {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.availableCommunicationDevices
                val tgtDevice =
                    devices.firstOrNull { preferBluetoothMic && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?:
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?:
                    devices.firstOrNull { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO  } ?:
                    devices.first()

                if (!audioManager.setCommunicationDevice(tgtDevice)) {
                    Log.w("AudioRecognizer", "setCommunicationDevice rejected for ${getRecordingDeviceKind(tgtDevice.type)}")
                    audioManager.clearCommunicationDevice()
                    return Pair(false, "")
                } else {
                    return Pair(tgtDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO, tgtDevice.productName.toString())
                }
            }
        } catch(e: Exception) {
            // SecurityException = BLUETOOTH_CONNECT not granted (API 31+)
            Log.w("AudioRecognizer", "setCommunicationDevice failed (BLUETOOTH_CONNECT granted?)", e)
        }
        return Pair(false, "")
    }

    private fun clearCommunicationDevice() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    @Throws(ModelDoesNotExistException::class)
    private fun verifyModelsExist() {
        val modelsThatDoNotExist = mutableListOf<ModelLoader>()

        if (!settings.modelRunConfiguration.primaryModel.exists(context)) {
            modelsThatDoNotExist.add(settings.modelRunConfiguration.primaryModel)
        }

        for (model in settings.modelRunConfiguration.languageSpecificModels.values) {
            if (!model.exists(context)) {
                modelsThatDoNotExist.add(model)
            }
        }

        if (modelsThatDoNotExist.isNotEmpty()) {
            throw ModelDoesNotExistException(modelsThatDoNotExist)
        }
    }

    init {
        verifyModelsExist()
    }

    fun reset() {
        invalidatePublicationSession()
        startAfterCleanupJob?.cancel()
        startAfterCleanupJob = null
        // stop() throws IllegalStateException when recording never actually
        // started (e.g. cancel before the first session) — that is not an error.
        try {
            recorder?.stop()
        } catch (e: IllegalStateException) {
            Log.w("AudioRecognizer", "recorder.stop() skipped: recorder was not recording")
        }
        recorderJob?.cancel()

        modelJob?.cancel()
        loadModelJob?.cancel()
        partialDecodeGate.stopSession(partialDecodeSession)
        partialDecodeJob?.cancel()
        isRecording = false

        modelRunner.cancelAll()

        unfocusAudio()

        // Session over: memory-pressure engine release is allowed again.
        if (sessionMarked) {
            sessionMarked = false
            RecognizerHolder.sessionEnded()
        }

        // Decode jobs are only cooperatively cancelled — a native decode can still
        // be reading floatSamples right now. Join them before any buffer swap.
        // The recorder job gets the same treatment: a blocking read() may still
        // be in flight, so release() waits for the job to actually finish.
        val pendingRecorder = recorder
        recorder = null
        val pendingRecorderJob = recorderJob
        val pendingModelJob = modelJob
        val pendingLoadModelJob = loadModelJob
        val pendingPartialJob = partialDecodeJob
        val priorCleanupJob = cleanupJob
        cleanupJob = lifecycleScope.launch(Dispatchers.Default) {
            priorCleanupJob?.join()
            pendingRecorderJob?.join()
            pendingRecorder?.release()
            pendingModelJob?.join()
            pendingLoadModelJob?.join()
            pendingPartialJob?.join()
            releaseBuffer()
        }
        clearCommunicationDevice()
    }

    fun finish() {
        if (!isRecording) return
        publicationSession?.let { sessionPublisher.publish(it, RecognizerEvent.Processing) }
        onFinishRecording()
    }

    fun cancel() {
        val session = publicationSession
        if (session == null || !publishTerminal(session, RecognizerEvent.Cancelled)) {
            listener.cancelled()
        }
        reset()
    }

    fun openPermissionSettings() {
        val packageName = context.packageName
        val micPermissionRequester = Intent()
        micPermissionRequester.setClassName(context, "org.futo.inputmethod.latin.MicPermissionActivity")
        micPermissionRequester.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        context.startActivity(micPermissionRequester)

        cancel()
    }

    fun start() {
        listener.loading()

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
        } else {
            startRecording()
        }
    }

    private fun requestPermission() {
        listener.needPermission { wasGranted ->
            if(wasGranted) {
                startRecording()
            }
        }
    }


    @Throws(SecurityException::class)
    private fun createAudioRecorder(): AudioRecord {
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            16000 * 2 * 5
        )

        this.recorder = recorder

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            recorder.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
        }

        return recorder
    }

    private suspend fun preloadModels() {
        modelRunner.preload(settings.modelRunConfiguration)
    }

    private fun expandSpaceIfAllowed(): Boolean {
        if(canExpandSpace && !BufferGrowth.isAtCap(floatSamples.capacity())) {
            // Allocate an extra 30 seconds, but never beyond the hard cap.
            val newSampleBuffer = FloatBuffer.allocate(BufferGrowth.nextCapacity(floatSamples.capacity()))
            //Log.d("AudioRecognizer", "Allocating extra space: ${floatSamples.capacity() / 16000} -> ${newSampleBuffer.capacity() / 16000}")
            newSampleBuffer.put(floatSamples.array(), 0, floatSamples.capacity() - floatSamples.remaining())
            floatSamples = newSampleBuffer
            return true
        }
        return false
    }

    /**
     * Releases any buffer expansion accumulated during a recording session.
     * The initial 30-second warm allocation is preserved so the next session
     * starts fresh without regressing startup latency.
     */
    private fun releaseBuffer() {
        if (isRecording) return
        floatSamples = AudioBufferPolicy.reset(floatSamples)
    }

    private suspend fun recordingJob(recorder: AudioRecord, vad: VadModel?) {
        var hasTalked = false
        var anyNoiseAtAll = false

        val canMicBeBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(SensorPrivacyManager::class.java) as SensorPrivacyManager).supportsSensorToggle(
                SensorPrivacyManager.Sensors.MICROPHONE
            )
        } else {
            false
        }
        var isMicBlocked = false

        val vadSampleBuffer = ShortBuffer.allocate(480)
        var numConsecutiveNonSpeech = 0
        var numConsecutiveSpeech = 0
        var totalSpeechFrames = 0

        val samples = ShortArray(1600)

        while (isRecording) {
            yield()
            val nRead = recorder.read(samples, 0, 1600, AudioRecord.READ_BLOCKING)
            if (nRead <= 0) break
            yield()

            var isRunningOutOfSpace = (floatSamples.remaining() < nRead.coerceAtLeast(1600)) && !expandSpaceIfAllowed()

            // Auto-stop uses the same VAD counters exposed to the UI countdown.
            val hasNotTalkedRecently = VadPolicy.shouldStop(hasTalked, numConsecutiveNonSpeech, totalSpeechFrames, useVAD)
            if (isRunningOutOfSpace || hasNotTalkedRecently) {
                yield()
                withContext(Dispatchers.Main) {
                    finish()
                }
                return
            }

            // Run VAD
            var speechSeenInRead = false
            if(useVAD && vad != null) {
                var remainingSamples = nRead
                var offset = 0
                while (remainingSamples > 0) {
                    if (!vadSampleBuffer.hasRemaining()) {
                        val isSpeech = vad.isSpeech(vadSampleBuffer.array())
                        vadSampleBuffer.clear()
                        vadSampleBuffer.rewind()

                        if (!isSpeech) {
                            numConsecutiveNonSpeech++
                            numConsecutiveSpeech = 0
                        } else {
                            numConsecutiveNonSpeech = 0
                            numConsecutiveSpeech++
                            totalSpeechFrames++
                            speechSeenInRead = true
                        }
                    }

                    val samplesToRead = min(min(remainingSamples, 480), vadSampleBuffer.remaining())
                    for (i in 0 until samplesToRead) {
                        vadSampleBuffer.put(
                            samples[offset]
                        )
                        offset += 1
                        remainingSamples -= 1
                    }
                }
            }

            floatSamples.put(samples.sliceArray(0 until nRead).map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray())

            // Track the end of the last speech for trailing-noise trim at finish.
            if (speechSeenInRead) {
                lastSpeechAtSamples = floatSamples.position()
            }

            // Don't set hasTalked if the start sound may still be playing, otherwise on some
            // devices the rms just explodes and `hasTalked` is always true
            val startSoundPassed = (floatSamples.position() > 16000 * 0.6)
            if (!startSoundPassed) {
                numConsecutiveSpeech = 0
                numConsecutiveNonSpeech = 0
                totalSpeechFrames = 0
                lastSpeechAtSamples = 0
            }

            val rms = sqrt(samples.sumOf { (it.toFloat() / Short.MAX_VALUE.toFloat()).pow(2).toDouble() } / samples.size).toFloat()

            if (startSoundPassed && ((rms > 0.004) || (numConsecutiveSpeech > 8))) {
                hasTalked = true
            }

            if (rms > 0.0001) {
                anyNoiseAtAll = true
                isMicBlocked = false
            }

            // Check if mic is blocked
            val blockCheckTimePassed = (floatSamples.position() > 2 * 16000) // two seconds
            if (!anyNoiseAtAll && canMicBeBlocked && blockCheckTimePassed) {
                isMicBlocked = true
            }

            val magnitude = (1.0f - 0.1f.pow(24.0f * rms))

            val state = if (hasTalked) {
                MagnitudeState.TALKING
            } else if (isMicBlocked) {
                MagnitudeState.MIC_MAY_BE_BLOCKED
            } else {
                MagnitudeState.NOT_TALKED_YET
            }

            yield()
            withContext(Dispatchers.Main) {
                listener.updateMagnitude(magnitude, state)
                listener.autoStopCountdown(
                    VadPolicy.countdownSeconds(
                        hasTalked,
                        numConsecutiveNonSpeech,
                        totalSpeechFrames,
                        useVAD,
                    )
                )
            }

            maybeLaunchPartialDecode()

            // Skip ahead as much as possible, in case we are behind (taking more than
            // 100ms to process 100ms)
            while (true) {
                yield()
                val nRead2 = recorder.read(
                    samples, 0, 1600, AudioRecord.READ_NON_BLOCKING
                )
                if (nRead2 > 0) {
                    if (floatSamples.remaining() < nRead2 && !expandSpaceIfAllowed()) {
                        yield()
                        withContext(Dispatchers.Main) {
                            finish()
                        }
                        break
                    }
                    floatSamples.put(samples.sliceArray(0 until nRead2).map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray())
                } else {
                    break
                }
            }
        }
        println("isRecording loop exited")
    }

    private fun createVad(): VadModel {
        return Vad.builder()// KBP: VERY_AGGRESSIVE only registered near-field audio — users had to
        // practically swallow the phone. NORMAL hears speech at hand distance.
        .setModel(Model.WEB_RTC_GMM).setMode(Mode.NORMAL)
            .setFrameSize(FrameSize.FRAME_SIZE_480).setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setSpeechDurationMs(150).setSilenceDurationMs(300).build()
    }

    /**
     * Streaming partial transcripts (Parakeet path only; the whisper engine and its
     * native partials stay untouched).
     *
     * Called once per recording-loop iteration (~100ms); throttles itself to one
     * launch per PartialThrottle.intervalMs. Each partial decodes ONLY the audio
     * recorded since the last checkpoint and joins it onto the checkpoint text
     * (IncrementalDecodePolicy) — decode cost stays bounded no matter how long
     * the note runs, the live transcript is always complete, and the checkpoint
     * seam can never produce duplicated text (base and tail cover disjoint audio).
     *
     * This function runs ON THE RECORDER THREAD and must stay cheap and
     * non-blocking: it only snapshots the sample buffer (an array copy) and
     * launches a coroutine. The decode itself runs on the engine's single
     * inference thread, which self-gates (skips when busy), so a slow decode can
     * never queue up work here.
     *
     * Cancel-safety: reset()/cancel() cancel [partialDecodeJob] and bump the
     * engine's cancel generation, so an in-flight partial can never deliver text
     * after cancellation. Leak-safety: each partial decode releases its sherpa
     * stream internally (see ParakeetSherpa.inferPartial).
     */
    private fun maybeLaunchPartialDecode() {
        if (partialDecodesDisabled) return

        val now = System.currentTimeMillis()
        val bufferedSeconds = floatSamples.position() / 16000f
        val minIntervalMs = PartialThrottle.intervalMs(bufferedSeconds)
        if (now - lastPartialDecodeAtMs < minIntervalMs) return

        // Only decode once model preloading has settled; peek without loading so a
        // slow model load never blocks the recorder.
        if (loadModelJob?.isCompleted != true) return
        val engine = modelManager.peekLoadedEngine(settings.modelRunConfiguration.primaryModel)
                as? ParakeetSherpa ?: return

        val sampleCount = floatSamples.position()
        val start = IncrementalDecodePolicy.tailStart(sampleCount, baseSamples) ?: return
        val session = partialDecodeSession
        if (!partialDecodeGate.tryStart(session)) return

        lastPartialDecodeAtMs = now

        val baseAtLaunch = baseTranscript
        val promote = IncrementalDecodePolicy.shouldPromote(sampleCount, start)
        val snapshot = floatSamples.array().sliceArray(start until sampleCount)

        val job = try {
            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    val tailText = engine.inferPartial(snapshot) ?: return@launch
                    partialDecodeGate.runIfCurrent(session) {
                        if (IncrementalDecodePolicy.requiresFullReconciliation(baseAtLaunch, tailText)) {
                            needsFullReconciliation = true
                        }
                        val completion = IncrementalDecodePolicy.completedPartial(
                            base = baseAtLaunch,
                            tail = tailText,
                            sampleCount = sampleCount,
                            promote = promote
                        )
                        if (completion.joinedTranscript.isNotBlank()) {
                            runnerCallback(SessionId(session)).partialResult(completion.joinedTranscript)
                        }
                        lastDecodeDeliveredAtSamples = completion.deliveredAtSamples
                        completion.promotedBaseTranscript?.let { baseTranscript = it }
                        completion.promotedBaseSamples?.let { baseSamples = it }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    partialDecodeGate.runIfCurrent(session) {
                        Log.w("AudioRecognizer", "Partial decode failed, disabling partials for this session", e)
                        partialDecodesDisabled = true
                    }
                }
            }
        } catch (e: Throwable) {
            partialDecodeGate.finished(session)
            throw e
        }

        // Completion handlers run even when a cancelled lifecycle prevents the
        // coroutine body from starting, so the single-flight slot cannot leak.
        job.invokeOnCompletion { partialDecodeGate.finished(session) }
        partialDecodeJob = job
    }

    @Throws(SecurityException::class)
    private fun createRecorderAndJob(preferBluetoothMic: Boolean): MicrophoneDeviceState {
        isRecording = false
        recorder?.stop()

        val bluetoothInfo = setCommunicationDevice(preferBluetoothMic)

        val task = {
            recorder?.release()

            val recorder = createAudioRecorder()

            recorder.startRecording()
            this.recorder = recorder

            isRecording = true

            recorderJob = lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    if(useVAD) {
                        createVad().use { vad ->
                            recordingJob(recorder, vad)
                        }
                    } else {
                        recordingJob(recorder, null)
                    }
                }
            }
        }

        if(recorderJob != null) {
            lifecycleScope.launch {
                recorderJob?.cancelAndJoin()
                try {
                    task()
                } catch (e: SecurityException) {
                    // Mic permission revoked between sessions: this deferred path
                    // (2nd+ session) runs outside startRecording()'s try/catch,
                    // so handle the denial the same way — clear BT routing and
                    // re-ask instead of crashing the IME.
                    Log.w("AudioRecognizer", "Lost mic permission on recorder re-creation", e)
                    withContext(Dispatchers.Main) {
                        clearCommunicationDevice()
                        requestPermission()
                    }
                }
            }
        } else {
            task()
        }


        return MicrophoneDeviceState(
            bluetoothAvailable = bluetoothInfo.first || isBluetoothAvailable(),
            bluetoothActive = bluetoothInfo.first,
            deviceName = bluetoothInfo.second,
            bluetoothPreferredByUser = settings.recordingConfiguration.preferBluetoothMic,
            setBluetooth = {
                listener.recordingStarted(createRecorderAndJob(it))
            }
        )
    }

    private fun startRecording() {
        cleanupJob?.takeUnless(Job::isCompleted)?.let { pendingCleanup ->
            if (startAfterCleanupJob?.isActive != true) {
                startAfterCleanupJob = lifecycleScope.launch {
                    pendingCleanup.join()
                    withContext(Dispatchers.Main) { startRecording() }
                }
            }
            return
        }
        cleanupJob = null
        startAfterCleanupJob = null

        // Open a fresh single-flight gate for this session. If cancellation from
        // the previous session is still unwinding, its running slot stays held
        // until that coroutine's finally block releases it.
        partialDecodeSession = partialDecodeGate.startSession()
        val session = beginPublicationSession(partialDecodeSession)

        // New session: re-enable streaming partials (a previous session may have
        // disabled them after a partial-decode failure).
        partialDecodesDisabled = false
        lastPartialDecodeAtMs = 0L
        lastHeardPartial = ""
        baseTranscript = ""
        baseSamples = 0
        lastDecodeDeliveredAtSamples = 0
        needsFullReconciliation = false
        lastSpeechAtSamples = 0
        finalTrimSamples = Int.MAX_VALUE

        val device = try {
            createRecorderAndJob(settings.recordingConfiguration.preferBluetoothMic)
        } catch (e: SecurityException) {
            // It's possible we may have lost permission, so let's just ask for permission again
            invalidatePublicationSession()
            clearCommunicationDevice()
            requestPermission()
            return
        }

        focusAudio()

        // Mark the session live only after the recorder actually started —
        // from here until reset(), memory pressure must not close the engine.
        if (!sessionMarked) {
            sessionMarked = true
            RecognizerHolder.sessionStarted()
        }

        listener.recordingStarted(device)

        loadModelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    preloadModels()
                } catch(_: InvalidModelException) {
                    withContext(Dispatchers.Main) {
                        if (publishTerminal(session, RecognizerEvent.ModelLoadingFailed)) reset()
                    }
                } catch(e: Exception) {
                    // Parakeet path throws InvalidParakeetModelException/IOException —
                    // never let a model failure escape the coroutine and crash the IME
                    withContext(Dispatchers.Main) {
                        if (publishTerminal(session, RecognizerEvent.ModelLoadingFailed)) reset()
                    }
                }
            }
        }
    }

    private fun runnerCallback(session: SessionId): ModelInferenceCallback = object : ModelInferenceCallback {
        override fun updateStatus(state: InferenceState) {
            if (sessionPublisher.activeSession() == session) listener.decodingStatus(state)
        }

        override fun partialResult(string: String) {
            if(isBlankResult(string)) return
            sessionPublisher.publishIfCurrent(
                session = session,
                allowed = true,
                event = RecognizerEvent.Partial(string),
            ) {
                lastHeardPartial = string
            }
        }
    }

    private suspend fun runModel(session: SessionId) {
        loadModelJob?.let {
            if (it.isActive) {
                println("Model was not finished loading...")
                it.join()
            }
        }

        val end = minOf(floatSamples.position(), finalTrimSamples)
        // Parakeet path: decode only the tail since the checkpoint and join —
        // the final pass is O(new audio), never O(whole buffer). Whisper keeps
        // the classic full-buffer decode.
        val isParakeet = modelManager.peekLoadedEngine(settings.modelRunConfiguration.primaryModel) is ParakeetSherpa
        val start = if (isParakeet) {
            IncrementalDecodePolicy.finalStart(
                baseTranscript,
                baseSamples,
                end,
                requiresFullReconciliation = needsFullReconciliation
            )
        } else {
            0
        }
        val baseAtFinal = baseTranscript

        if (end <= start) {
            // Nothing new to decode past the checkpoint (e.g. trailing noise was
            // trimmed away) — the checkpoint transcript IS the final result.
            val checkpointText = FinishPolicy.commitText(lastHeardPartial, baseAtFinal)
            yield()
            withContext(Dispatchers.Main) {
                yield()
                publishTerminal(session, RecognizerEvent.Finished(checkpointText))
            }
            return
        }

        val floatArray = floatSamples.array().sliceArray(start until end)

        // A tail decode emits tail-only text through partialResult; join it onto
        // the checkpoint so lastHeardPartial (and the live strip) stays complete.
        val sessionCallback = runnerCallback(session)
        val callback: ModelInferenceCallback = if (start > 0) {
            object : ModelInferenceCallback {
                override fun updateStatus(state: InferenceState) = sessionCallback.updateStatus(state)
                override fun partialResult(string: String) {
                    if (isBlankResult(string)) return
                    sessionCallback.partialResult(IncrementalDecodePolicy.join(baseAtFinal, string))
                }
            }
        } else {
            sessionCallback
        }

        yield()
        val outputText = try {
             modelRunner.run(
                floatArray,
                settings.modelRunConfiguration,
                settings.decodingConfiguration,
                callback
            ).trim()
        }catch(e: InferenceCancelledException) {
            yield()
            return
        }catch(e: Exception) {
            // Engine failures (bad/partial model, native error) must not escape
            yield()
            withContext(Dispatchers.Main) {
                if (publishTerminal(session, RecognizerEvent.ModelLoadingFailed)) reset()
            }
            return
        }

        val completeText = if (start > 0) IncrementalDecodePolicy.join(baseAtFinal, outputText) else outputText
        val text = FinishPolicy.commitText(lastHeardPartial, completeText)

        yield()
        withContext(Dispatchers.Main) {
            yield()
            publishTerminal(session, RecognizerEvent.Finished(text))
        }
    }

    private fun onFinishRecording() {
        // Close the launch gate before touching recorder state. From this point
        // no recorder tick can enqueue another partial, even if cancellation has
        // not reached the recording coroutine yet.
        partialDecodeGate.stopSession(partialDecodeSession)

        val pendingRecorderJob = recorderJob
        pendingRecorderJob?.cancel()

        if (!isRecording) {
            // Races (stop button vs VAD auto-stop, or finish after a failed
            // session) reach here legitimately — a logged no-op, not a crash.
            Log.w("AudioRecognizer", "onFinishRecording while not recording — ignoring")
            return
        }

        isRecording = false
        recorder?.stop()
        val session = publicationSession ?: run {
            Log.w("AudioRecognizer", "finish has no active publication session")
            reset()
            return
        }

        modelJob = lifecycleScope.launch {
            // Finish decisions must use one stable publication point. Stop the
            // recorder first so it cannot schedule one last partial, then capture
            // and stop the final partial job before snapshotting session state.
            withContext(Dispatchers.Default) {
                pendingRecorderJob?.join()
                val finalPartialJob = partialDecodeJob
                finalPartialJob?.cancel()
                modelRunner.cancelAll()
                finalPartialJob?.join()
            }

            // The recorder and partial decoder are now quiescent, so all values
            // below describe the same completed session state.
            finalTrimSamples = TrailingTrimPolicy.keptSamples(floatSamples.position(), lastSpeechAtSamples)
            val readyPartial = lastHeardPartial
            val pendingSamples = IncrementalDecodePolicy.pendingUndecoded(
                finalTrimSamples,
                lastDecodeDeliveredAtSamples
            )

            if (FinishPolicy.shouldCommitPartialImmediately(readyPartial) &&
                    !needsFullReconciliation &&
                    !IncrementalDecodePolicy.needsFinalTail(pendingSamples)) {
                // Bubble contract: text already on screen. Do not pinwheel or
                // re-decode when the stable session snapshot is unambiguous.
                publishTerminal(session, RecognizerEvent.Finished(readyPartial))
                withContext(Dispatchers.Default) { releaseBuffer() }
                return@launch
            }

            if (FinishPolicy.shouldShowPinwheel(hasUsablePartial = readyPartial.isNotBlank())) {
                sessionPublisher.publish(session, RecognizerEvent.Processing)
            }

            withContext(Dispatchers.Default) {
                try {
                    runModel(session)
                } finally {
                    releaseBuffer()
                }
            }
        }
    }
}