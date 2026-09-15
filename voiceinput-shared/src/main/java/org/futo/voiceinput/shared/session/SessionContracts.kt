package org.futo.voiceinput.shared.session

@JvmInline
value class SessionId(val value: Long)

sealed interface RecognizerEvent {
    data class Partial(val text: String) : RecognizerEvent
    data class Finished(val text: String) : RecognizerEvent
    data object Processing : RecognizerEvent
    data object Cancelled : RecognizerEvent
    data object ModelLoadingFailed : RecognizerEvent
}

fun interface RecognizerEventSink {
    fun emit(event: RecognizerEvent)
}

/**
 * A short, reentrant publication boundary. Identity validation and listener
 * entry are one monitor operation; no coroutine is detached for delivery.
 */
class SessionPublisher(private val sink: RecognizerEventSink) {
    private val lock = Any()
    private var active: SessionId? = null

    fun install(session: SessionId) = synchronized(lock) {
        check(active == null) { "session $active is still active" }
        active = session
    }

    fun invalidate(session: SessionId): Boolean = synchronized(lock) {
        if (active != session) return@synchronized false
        active = null
        true
    }

    fun publish(session: SessionId, event: RecognizerEvent): Boolean =
        publishIfCurrent(session, allowed = true, event = event) { }

    internal fun publishIfCurrent(
        session: SessionId,
        allowed: Boolean,
        event: RecognizerEvent?,
        mutate: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (active != session || !allowed) return@synchronized false
        mutate()
        if (event != null) sink.emit(event)
        true
    }

    fun publishTerminal(session: SessionId, event: RecognizerEvent): Boolean = synchronized(lock) {
        if (active != session) return@synchronized false
        active = null
        sink.emit(event)
        true
    }

    fun activeSession(): SessionId? = synchronized(lock) { active }
}
