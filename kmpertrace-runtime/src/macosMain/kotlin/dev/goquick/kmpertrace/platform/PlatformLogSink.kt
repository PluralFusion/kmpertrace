package dev.goquick.kmpertrace.platform

import dev.goquick.kmpertrace.log.LogRecord
import dev.goquick.kmpertrace.log.LogSink

actual object PlatformLogSink : LogSink {
    actual override fun emit(record: LogRecord) {
        println(record.line)
    }
}
