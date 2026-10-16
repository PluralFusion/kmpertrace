package dev.goquick.kmpertrace.sampleapp

import dev.goquick.kmpertrace.core.Level
import dev.goquick.kmpertrace.log.KmperTrace

/**
 * Configure KmperTrace. Each platform entry point calls this once at process start,
 * before any UI is created, so no log record is dropped.
 *
 * Calling this more than once is safe. [KmperTrace.configure] only overwrites its settings with the
 * given values, and it does not add sinks or keep other state. It takes no lock, so call it from one
 * thread at startup.
 */
fun initKmperTrace() {
    KmperTrace.configure(
        minLevel = Level.DEBUG,
        serviceName = "sample-app",
        emitDebugAttributes = true,
    )
}
