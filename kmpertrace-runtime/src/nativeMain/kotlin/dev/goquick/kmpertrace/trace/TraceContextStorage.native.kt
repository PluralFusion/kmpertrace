package dev.goquick.kmpertrace.trace

import dev.goquick.kmpertrace.core.TraceContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.native.concurrent.ThreadLocal

private class TraceContextElement(
    private val traceValue: TraceContext?,
    private val downstream: ContinuationInterceptor?
) : CoroutineContext.Element, ContinuationInterceptor {
    override val key: CoroutineContext.Key<*> get() = ContinuationInterceptor

    // Wrap the continuation so that when it resumes on a native thread we re-install the captured trace context.
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
        downstream?.interceptContinuation(TraceContinuation(continuation, traceValue))
            ?: TraceContinuation(continuation, traceValue)

    override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
        downstream?.releaseInterceptedContinuation(continuation)
    }
}

private class TraceContinuation<T>(
    private val delegate: Continuation<T>,
    private val traceValue: TraceContext?
) : Continuation<T> {
    override val context: CoroutineContext get() = delegate.context

    override fun resumeWith(result: Result<T>) {
        val previousTrace = currentTraceContext
        val previousBinding = LoggingBindingStorage.get()

        // Install the captured trace context for the duration of this continuation resume.
        currentTraceContext = traceValue
        // Always bind logs to the current span while this continuation runs.
        LoggingBindingStorage.set(LoggingBinding.BindToSpan)
        try {
            delegate.resumeWith(result)
        } finally {
            // Restore whatever was active before we hopped back to this thread.
            currentTraceContext = previousTrace
            LoggingBindingStorage.set(previousBinding)
        }
    }
}

@ThreadLocal
private var currentTraceContext: TraceContext? = null // Keep one TraceContext per native thread.

actual object TraceContextStorage {
    actual fun get(): TraceContext? = currentTraceContext

    actual fun set(value: TraceContext?) {
        currentTraceContext = value
    }

    actual fun element(
        value: TraceContext?,
        downstream: ContinuationInterceptor?
    ): CoroutineContext = TraceContextElement(value, downstream)
}
