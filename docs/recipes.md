# KmperTrace recipes

Short recipes for common cases. Verified against KmperTrace 0.3.3 (2026-10-16). The Kotlin snippets were compiled on the JVM target, and the Android snippet against the Android target. The Swift snippets were not compiled, because they need macOS.

Terms: a **span** is one timed step with its own ID. A **trace** is the set of spans that belong to one flow. A **trace snapshot** is a saved copy of the current trace and span that you can re-install later, on another thread.

Imports that the snippets assume:

```kotlin compile
import dev.goquick.kmpertrace.log.Log
import dev.goquick.kmpertrace.log.journey
import dev.goquick.kmpertrace.trace.TraceTrigger
import dev.goquick.kmpertrace.trace.captureTraceSnapshot
import dev.goquick.kmpertrace.trace.traceSpan
```

## a. A coroutine flow

Wrap the flow in one span and each step in a child span. A `traceSpan` call inside another `traceSpan` creates a child span in the same trace. `traceSpan("FetchProfile")` takes only a name.

```kotlin compile
data class Profile(val name: String)
data class Contact(val name: String)

interface Api {
    suspend fun fetchProfile(userId: String): Profile
    suspend fun fetchContacts(userId: String): List<Contact>
}

class ProfileViewModel(private val api: Api) {
    suspend fun refreshAll(userId: String) =
        traceSpan(
            component = "ProfileViewModel",
            operation = "refreshAll",
            attributes = mapOf("userId" to userId),
        ) {
            Log.i { "refreshing" }
            val profile = traceSpan("FetchProfile") { api.fetchProfile(userId) }
            val contacts = traceSpan("FetchContacts") { api.fetchContacts(userId) }
            Log.i { "refreshed ${contacts.size} contacts for ${profile.name}" }
        }
}
```

If you switch dispatchers yourself inside a span, use `withTraceContext(dispatcher) { ... }` so the logs stay in the trace. `docs/Tracing.md` describes this and the other recipes for lost traces.

## b. An SDK callback with a trace snapshot

A callback that a third-party SDK runs on its own thread has no trace. Capture a snapshot inside the span, then re-install it in the callback.

```kotlin compile
interface Sdk {
    fun setListener(listener: (String) -> Unit)
}

suspend fun watchState(sdk: Sdk) =
    traceSpan(component = "ScaleClient", operation = "watchState") {
        val snap = captureTraceSnapshot()
        sdk.setListener { state ->
            snap.withTraceSnapshot {
                Log.d { "state=$state" }
            }
        }
    }
```

On Android, `Handler.postWithTrace` and `Handler.postDelayedWithTrace` (package `dev.goquick.kmpertrace.trace.android`) install the snapshot for you. They exist only in the Android target.

```kotlin fragment
interface ScaleSdk {
    fun connect(mac: String)
}

class ScaleClient(private val sdk: ScaleSdk) {
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun connect(mac: String) =
        traceSpan(
            component = "ScaleClient",
            operation = "connect",
            attributes = mapOf("?mac" to mac),
        ) {
            val snapshot = captureTraceSnapshot()
            mainHandler.postWithTrace(snapshot) {
                Log.d { "calling sdk.connect" }
                sdk.connect(mac)
            }
            mainHandler.postDelayedWithTrace(10_000, snapshot) {
                Log.w { "connect still pending after 10 s" }
            }
        }
}
```

This needs `import android.os.Handler`, `import android.os.Looper`, `import dev.goquick.kmpertrace.trace.android.postWithTrace` and `import dev.goquick.kmpertrace.trace.android.postDelayedWithTrace`. A snapshot does not keep the span open. The span ends when its block returns.

## c. Error handling that logs an exception once

When an exception leaves a span, the span writes its end record at ERROR level with the stack trace. Every enclosing span the exception leaves writes the same stack trace again. In a run of a two-level example, the raw output held the stack trace in the inner span's end record and in the outer span's end record. A `Log.e` call before a rethrow would add a third copy.

So a layer that only passes the exception on needs no `try` and no `Log.e`:

```kotlin compile
class Repo(private val api: Api) {
    suspend fun load(userId: String): Profile =
        traceSpan(component = "Repo", operation = "load") {
            api.fetchProfile(userId)
        }
}
```

The layer that handles the failure logs it, once:

```kotlin compile
suspend fun profileName(repo: Repo, userId: String): String =
    try {
        repo.load(userId).name
    } catch (e: Exception) {
        Log.e(throwable = e) { "could not load profile, using default" }
        "unknown"
    }
```

## d. A Compose app

Call `KmperTrace.configure` at process start, before the first composition. The README Quick Start shows where to call it on each platform.

Start a trace where the user acts. `journey` is an extension on `LogContext` and records the trigger as the `trigger` attribute:

```kotlin fragment
@Composable
fun RefreshButton(refresh: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val log = remember { Log.forComponent("ProfileScreen") }
    Button(onClick = {
        scope.launch {
            log.journey(
                operation = "refresh",
                trigger = TraceTrigger.tap("RefreshButton"),
            ) {
                refresh()
            }
        }
    }) {
        Text("Refresh")
    }
}
```

This snippet needs the Compose imports `Button`, `Text`, `Composable`, `remember` and `rememberCoroutineScope`, plus `kotlinx.coroutines.launch`.

## e. A Swift host app

Swift can log and re-install a snapshot. It cannot start a span, so create spans in Kotlin. Export `kmpertrace-runtime` from your Kotlin framework as described in [IOS-KMP-Swift.md](IOS-KMP-Swift.md). These snippets follow that guide and were not compiled.

```swift
import ComposeApp

let log = KmperTraceSwift.shared.logger(component: "IosScaleClient")
let snapshot = KmperTraceSwift.shared.captureSnapshot()

DispatchQueue.main.async {
    snapshot.with {
        log.d(operation: "connect", message: "callback fired")
    }
}
```

Capture the snapshot while a Kotlin span is active, for example in Swift code that Kotlin calls from inside a `traceSpan` block.

## Problems and requests

If a recipe does not work on your platform, or you need a platform that KmperTrace does not support yet, please open an issue at https://github.com/pluralfusion/kmpertrace/issues. Include the KmperTrace version, the platform and a minimal reproduction.
