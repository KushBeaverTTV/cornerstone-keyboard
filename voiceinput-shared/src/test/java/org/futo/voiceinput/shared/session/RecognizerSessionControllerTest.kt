package org.futo.voiceinput.shared.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecognizerSessionControllerTest {
    @Test
    fun invalidatedSessionCannotPublish() {
        val events = mutableListOf<RecognizerEvent>()
        val publisher = SessionPublisher(RecognizerEventSink(events::add))
        val session = SessionId(1)

        publisher.install(session)
        publisher.invalidate(session)

        assertFalse(publisher.publish(session, RecognizerEvent.Partial("stale")))
        assertTrue(events.isEmpty())
        assertNull(publisher.activeSession())
    }

    @Test
    fun partialReturningAfterResetCannotMutateOrPublish() = runTest {
        val h = Harness(testScheduler)
        val s1 = h.controller.start()
        h.feed(s1, 7, 4)
        runCurrent()
        assertTrue(h.controller.requestPartial(s1))
        runCurrent()
        h.engine.partialEntered.await()

        val reset = async { h.controller.reset(s1) }
        runCurrent()
        assertFalse(reset.isCompleted)
        h.engine.releasePartial.complete(Unit)
        advanceUntilIdle()
        reset.await()

        assertTrue(h.events.none { it is RecognizerEvent.Partial })
        assertEquals(0, h.controller.coverageFor(s1))
        assertNull(h.controller.activeSession())
        assertEquals(1, h.buffers.getValue(s1).releases)
    }

    @Test
    fun startWaitsForOldFinalizationAndFinalizerUsesOnlyS1Snapshot() = runTest {
        val h = Harness(testScheduler)
        val s1 = h.controller.start()
        h.feed(s1, 1, 4)
        runCurrent()

        val finish = async { h.controller.finish(s1) }
        runCurrent()
        h.engine.finalEntered.await()
        val startingS2 = async { h.controller.start() }
        runCurrent()

        assertFalse(startingS2.isCompleted)
        assertEquals(listOf(1f, 1f, 1f, 1f), h.engine.finalInputs.single().toList())

        h.engine.releaseFinal.complete(Unit)
        advanceUntilIdle()
        finish.await()
        val s2 = startingS2.await()
        h.feed(s2, 2, 3)
        runCurrent()

        assertEquals(1, h.buffers.getValue(s1).releases)
        assertEquals(0, h.buffers.getValue(s2).releases)
        assertEquals(listOf(1f, 1f, 1f, 1f), h.engine.finalInputs.single().toList())
        assertTrue(h.events.none { it is RecognizerEvent.Finished })
    }

    @Test
    fun finishDoesNotCompleteBeforeListenerPublication() = runTest {
        val h = Harness(testScheduler)
        val s1 = h.controller.start()
        h.feed(s1, 3, 2)
        runCurrent()
        val finish = async { h.controller.finish(s1) }
        runCurrent()
        assertFalse(finish.isCompleted)

        h.engine.releaseFinal.complete(Unit)
        advanceUntilIdle()
        finish.await()

        assertEquals(listOf(RecognizerEvent.Finished("final")), h.events)
    }

    @Test
    fun reentrantInvalidationRejectsLaterOldEvents() {
        val events = mutableListOf<RecognizerEvent>()
        lateinit var publisher: SessionPublisher
        val s1 = SessionId(1)
        publisher = SessionPublisher(RecognizerEventSink { event ->
            events += event
            if (event == RecognizerEvent.Partial("first")) publisher.invalidate(s1)
        })
        publisher.install(s1)

        assertTrue(publisher.publish(s1, RecognizerEvent.Partial("first")))
        assertFalse(publisher.publish(s1, RecognizerEvent.Finished("stale")))
        assertEquals(listOf(RecognizerEvent.Partial("first")), events)
    }

    @Test
    fun teardownCompletesDespiteCallerCancellation() = runTest {
        val h = Harness(testScheduler)
        val s1 = h.controller.start()
        runCurrent()

        val reset = async { h.controller.reset(s1) }
        runCurrent()
        reset.cancel()
        advanceUntilIdle()

        assertTrue(reset.isCompleted)
        assertEquals(1, h.recorders.getValue(s1).stops)
        assertEquals(1, h.recorders.getValue(s1).releases)
        assertEquals(1, h.buffers.getValue(s1).releases)
        assertTrue(h.controller.jobsFor(s1).all { it.isCompleted })
    }

    @Test
    fun completedBlankPartialAdvancesCoverageWithoutPublishingText() = runTest {
        val h = Harness(testScheduler)
        h.engine.partialResult = PartialOutcome.Completed("")
        h.engine.releasePartial.complete(Unit)
        val s1 = h.controller.start()
        h.feed(s1, 5, 12)
        runCurrent()

        assertTrue(h.controller.requestPartial(s1))
        advanceUntilIdle()

        assertEquals(12, h.controller.coverageFor(s1))
        assertTrue(h.events.none { it is RecognizerEvent.Partial })
        h.controller.reset(s1)
    }

    @Test
    fun concurrentFinishResetAndCloseReleaseResourcesOnceWithAtMostOneTerminal() = runTest {
        val h = Harness(testScheduler)
        val s1 = h.controller.start()
        h.feed(s1, 9, 4)
        runCurrent()

        val finish = async { h.controller.finish(s1) }
        val reset = async { h.controller.reset(s1) }
        val close = async { h.controller.close() }
        runCurrent()
        h.engine.releaseFinal.complete(Unit)
        advanceUntilIdle()
        joinAll(finish, reset, close)

        assertEquals(1, h.recorders.getValue(s1).stops)
        assertEquals(1, h.recorders.getValue(s1).releases)
        assertEquals(1, h.buffers.getValue(s1).releases)
        assertTrue(h.events.count { it.isTerminal() } <= 1)
    }

    private fun RecognizerEvent.isTerminal(): Boolean =
        this is RecognizerEvent.Finished ||
            this is RecognizerEvent.Cancelled ||
            this is RecognizerEvent.ModelLoadingFailed
}

private class Harness(scheduler: TestCoroutineScheduler) {
    private val control = StandardTestDispatcher(scheduler, "control")
    private val recorderDispatcher = StandardTestDispatcher(scheduler, "recorder")
    private val inference = StandardTestDispatcher(scheduler, "inference")
    private val listener = StandardTestDispatcher(scheduler, "listener")

    val events = mutableListOf<RecognizerEvent>()
    val recorders = linkedMapOf<SessionId, FakeRecorder>()
    val buffers = linkedMapOf<SessionId, FakeSessionBuffer>()
    val engine = ScriptedEngine()
    private val publisher = SessionPublisher(RecognizerEventSink(events::add))

    val controller = RecognizerSessionControllerImpl(
        dispatchers = RecognizerDispatchers(control, recorderDispatcher, inference, listener),
        recorderFactory = RecorderFactory { id -> FakeRecorder().also { recorders[id] = it } },
        engine = engine,
        bufferFactory = SessionBufferFactory { id -> FakeSessionBuffer(id).also { buffers[id] = it } },
        publisher = publisher,
    )

    suspend fun feed(session: SessionId, marker: Short, count: Int) {
        recorders.getValue(session).reads.send(ShortArray(count) { marker })
    }
}

private class FakeRecorder : Recorder {
    val reads = Channel<ShortArray>(Channel.UNLIMITED)
    var starts = 0
    var stops = 0
    var releases = 0

    override suspend fun start() { starts++ }

    override suspend fun read(target: ShortArray): Int {
        val samples = reads.receive()
        samples.copyInto(target)
        return samples.size
    }

    override fun stop() {
        stops++
        reads.trySend(ShortArray(0))
    }

    override fun release() { releases++ }
}

private class FakeSessionBuffer(val session: SessionId) : SessionBuffer {
    private val samples = mutableListOf<Float>()
    var releases = 0

    override val size: Int get() = samples.size

    override fun append(samples: ShortArray, count: Int) {
        check(releases == 0)
        repeat(count) { this.samples += samples[it].toFloat() }
    }

    override fun snapshot(from: Int, until: Int): FloatArray {
        check(releases == 0) { "read after release: $session" }
        return samples.subList(from, until).toFloatArray()
    }

    override fun release() {
        check(releases == 0) { "double release: $session" }
        releases++
    }
}

private class ScriptedEngine : RecognizerEngine {
    val partialEntered = CompletableDeferred<Unit>()
    val releasePartial = CompletableDeferred<Unit>()
    val finalEntered = CompletableDeferred<Unit>()
    val releaseFinal = CompletableDeferred<Unit>()
    var partialResult: PartialOutcome = PartialOutcome.Completed("partial")
    var finalResult = "final"
    val partialInputs = mutableListOf<FloatArray>()
    val finalInputs = mutableListOf<FloatArray>()
    var invalidations = 0

    override suspend fun inferPartial(samples: FloatArray): PartialOutcome {
        partialInputs += samples.copyOf()
        partialEntered.complete(Unit)
        withContext(NonCancellable) { releasePartial.await() }
        return partialResult
    }

    override suspend fun inferFinal(samples: FloatArray): String {
        finalInputs += samples.copyOf()
        finalEntered.complete(Unit)
        withContext(NonCancellable) { releaseFinal.await() }
        return finalResult
    }

    override fun invalidate(): Long = (++invalidations).toLong()
}
