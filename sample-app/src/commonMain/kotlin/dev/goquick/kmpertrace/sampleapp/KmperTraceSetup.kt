package dev.goquick.kmpertrace.sampleapp

import dev.goquick.kmpertrace.core.Level
import dev.goquick.kmpertrace.log.KmperTrace

/**
 * Configure KmperTrace. Each platform entry point calls this once at process start,
 * before any UI is created, so no log record is dropped.
 */
fun initKmperTrace() {
    KmperTrace.configure(
        minLevel = Level.DEBUG,
        serviceName = "sample-app",
        emitDebugAttributes = true,
    )
}
