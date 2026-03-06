package dev.goquick.kmpertrace.log

@PublishedApi
internal actual fun currentThreadNameOrNull(): String? = null // Kotlin/Native does not expose a stable thread name API.
