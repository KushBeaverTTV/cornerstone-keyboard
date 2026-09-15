package org.futo.voiceinput.shared.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface Recorder {
    suspend fun start()
    suspend fun read(target: ShortArray): Int
    fun stop()
    fun release()
}

fun interface RecorderFactory {
    fun create(session: SessionId): Recorder
}

interface SessionBuffer {
    val size: Int
    fun append(samples: ShortArray, count: Int)
    fun snapshot(from: Int = 0, until: Int = size): FloatArray
    fun release()
}

fun interface SessionBufferFactory {
    fun create(session: SessionId): SessionBuffer
}

sealed interface PartialOutcome {
    data object SkippedOrCancelled : PartialOutcome
    data class Completed(val text: String) : PartialOutcome
    data class Failed(val error: Throwable) : PartialOutcome
}

interface RecognizerEngine {
    suspend fun inferPartial(samples: FloatArray): PartialOutcome
    suspend fun inferFinal(samples: FloatArray): String
    fun invalidate(): Long
}

data class RecognizerDispatchers(
    val control: CoroutineDispatcher,
    val recorder: CoroutineDispatcher,
    val inference: CoroutineDispatcher,
    val listener: CoroutineDispatcher,
)

interface RecognizerSessionController {
    suspend fun start(): SessionId
    suspend fun requestPartial(session: SessionId): Boolean
    suspend fun finish(session: SessionId)
    suspend fun reset(session: SessionId)
    suspend fun close()
}

internal enum class SessionPhase { RECORDING, FINISHING, CLOSED }

internal class RecordingSession(
    val id: SessionId,
    val rootJob: CompletableJob,
    val scope: CoroutineScope,
    val recorder: Recorder,
    val buffer: SessionBuffer,
) {
    var phase = SessionPhase.RECORDING
    var recorderJob: Job? = null
    var partialJob: Job? = null
    var finalizationJob: Job? = null
    var coverage = 0
    var recorderStopped = false
    var resourcesReleased = false
}

/**
 * Pure-Kotlin lifecycle owner. Public operations are serialized, execute their
 * non-negotiable portion in NonCancellable, and never launch listener delivery.
 */
class RecognizerSessionControllerImpl(
    private val dispatchers: RecognizerDispatchers,
    private val recorderFactory: RecorderFactory,
    private val engine: RecognizerEngine,
    private val bufferFactory: SessionBufferFactory,
    private val publisher: SessionPublisher,
) : RecognizerSessionController {
    private val lifecycle = Mutex()
    private var nextId = 0L
    private var active: RecordingSession? = null
    private var controllerClosed = false
    private val sessions = linkedMapOf<SessionId, RecordingSession>()

    override suspend fun start(): SessionId = nonCancellableControl {
        lifecycle.withLock {
            check(!controllerClosed) { "controller is closed" }
            active?.let { old ->
                publisher.invalidate(old.id)
                old.phase = SessionPhase.CLOSED
                teardown(old)
            }

            val id = SessionId(++nextId)
            val recorder = recorderFactory.create(id)
            val buffer = bufferFactory.create(id)
            val root = SupervisorJob()
            val session = RecordingSession(
                id = id,
                rootJob = root,
                scope = CoroutineScope(root + dispatchers.control),
                recorder = recorder,
                buffer = buffer,
            )
            sessions[id] = session
            active = session
            publisher.install(id)

            try {
                withContext(dispatchers.recorder) { recorder.start() }
                val job = session.scope.launch(dispatchers.recorder, start = CoroutineStart.LAZY) {
                    recorderLoop(session)
                }
                session.recorderJob = job
                job.start()
            } catch (failure: Throwable) {
                publisher.invalidate(id)
                session.phase = SessionPhase.CLOSED
                teardown(session)
                active = null
                throw failure
            }
            id
        }
    }

    override suspend fun requestPartial(session: SessionId): Boolean = nonCancellableControl {
        lifecycle.withLock {
            val current = active
            if (current?.id != session || current.phase != SessionPhase.RECORDING) {
                return@withLock false
            }
            if (current.partialJob?.isActive == true) return@withLock false

            val sampleCount = current.buffer.size
            val samples = current.buffer.snapshot(0, sampleCount)
            val job = current.scope.launch(dispatchers.inference, start = CoroutineStart.LAZY) {
                val outcome = try {
                    engine.inferPartial(samples)
                } catch (_: CancellationException) {
                    PartialOutcome.SkippedOrCancelled
                } catch (failure: Throwable) {
                    PartialOutcome.Failed(failure)
                }
                when (outcome) {
                    is PartialOutcome.Completed -> {
                        publisher.publishIfCurrent(current.id, current.phase == SessionPhase.RECORDING,
                            outcome.text.takeIf(String::isNotBlank)?.let(RecognizerEvent::Partial)
                        ) {
                            current.coverage = maxOf(current.coverage, sampleCount)
                        }
                    }
                    is PartialOutcome.Failed,
                    PartialOutcome.SkippedOrCancelled -> Unit
                }
            }
            current.partialJob = job
            job.start()
            true
        }
    }

    override suspend fun finish(session: SessionId): Unit = nonCancellableControl {
        val finalizer = lifecycle.withLock {
            val current = active
            if (current?.id != session || current.phase != SessionPhase.RECORDING) return@withLock null
            current.phase = SessionPhase.FINISHING
            stopRecorder(current)
            engine.invalidate()
            current.rootJob.cancelAndJoin()
            val finalSamples = current.buffer.snapshot()

            val job = CoroutineScope(SupervisorJob() + dispatchers.control).launch(
                start = CoroutineStart.LAZY,
            ) {
                val terminal = try {
                    val result = withContext(dispatchers.inference) { engine.inferFinal(finalSamples) }
                    RecognizerEvent.Finished(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    RecognizerEvent.ModelLoadingFailed
                }
                withContext(dispatchers.listener) {
                    publisher.publishTerminal(current.id, terminal)
                }
            }
            current.finalizationJob = job
            job.start()
            job
        }

        finalizer?.join()
        lifecycle.withLock {
            val current = active
            if (current?.id == session && current.phase == SessionPhase.FINISHING) {
                current.phase = SessionPhase.CLOSED
                teardown(current)
                if (active === current) active = null
            }
        }
    }

    override suspend fun reset(session: SessionId): Unit = nonCancellableControl {
        lifecycle.withLock {
            val current = active
            if (current?.id != session) return@withLock
            publisher.invalidate(current.id)
            current.phase = SessionPhase.CLOSED
            teardown(current)
            if (active === current) active = null
        }
    }

    override suspend fun close(): Unit = nonCancellableControl {
        lifecycle.withLock {
            if (controllerClosed) return@withLock
            controllerClosed = true
            active?.let { current ->
                publisher.invalidate(current.id)
                current.phase = SessionPhase.CLOSED
                teardown(current)
                if (active === current) active = null
            }
        }
    }

    fun coverageFor(session: SessionId): Int = sessions[session]?.coverage ?: 0
    fun activeSession(): SessionId? = active?.id
    fun jobsFor(session: SessionId): List<Job> = sessions[session]?.let {
        listOfNotNull(it.recorderJob, it.partialJob, it.finalizationJob)
    }.orEmpty()

    private suspend fun recorderLoop(session: RecordingSession) {
        val target = ShortArray(4096)
        while (session.phase == SessionPhase.RECORDING) {
            val count = session.recorder.read(target)
            if (count <= 0) return
            session.buffer.append(target, count)
        }
    }

    private fun stopRecorder(session: RecordingSession) {
        if (!session.recorderStopped) {
            session.recorderStopped = true
            session.recorder.stop()
        }
    }

    private suspend fun teardown(session: RecordingSession) {
        if (session.resourcesReleased) return
        session.resourcesReleased = true
        withContext(NonCancellable + dispatchers.control) {
            stopRecorder(session)
            engine.invalidate()
            session.rootJob.cancel()
            session.finalizationJob?.cancel()
            listOfNotNull(session.recorderJob, session.partialJob, session.finalizationJob).joinAll()
            session.recorder.release()
            session.buffer.release()
        }
    }

    private suspend fun <T> nonCancellableControl(block: suspend () -> T): T =
        withContext(NonCancellable + dispatchers.control) { block() }
}
