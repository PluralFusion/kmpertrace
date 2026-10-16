# Using KmperTrace with AI coding assistants

Verified against KmperTrace 0.3.3 (2026-10-15 23:37 EDT). If you use a newer version, check the signatures in the source.

This guide is for developers who use AI coding assistants (Claude Code, Codex, Copilot and similar) in projects that use KmperTrace. It lists the calls that exist, the conventions that keep logs readable, and the mistakes assistants make most often with this library.

Terms used here:

- **Trace**: one flow of work, identified by a trace ID.
- **Span**: one step inside a trace, with a span ID, a start, an end and a duration.
- **Attribute**: a key/value string attached to a span.
- **Trace snapshot**: a saved copy of the current trace and span, used to attach logs to that span from code that runs later on another thread.

## 1. What KmperTrace gives an assistant

KmperTrace writes a trace ID and a span ID into every log line emitted inside a span. It also writes span start and end markers with the duration, any error, and the span's attributes. The CLI rebuilds the tree of spans from those lines. An assistant can read which step failed, how long it took and which attribute values it ran with, without parsing free text.

## 2. Paste-ready block for AGENTS.md or CLAUDE.md

````markdown
## KmperTrace (verified against 0.3.3)
- Dependency (commonMain): implementation("dev.goquick.kmpertrace:kmpertrace-runtime:0.3.3")
- Call KmperTrace.configure(minLevel = Level.DEBUG, serviceName = "my-app", emitDebugAttributes = isDebugBuild)
  once at process start, before the first Log or traceSpan call. Not inside the first composition.
  Records logged before configure are dropped.
- Wrap an operation: traceSpan(component = "X", operation = "y", attributes = mapOf("id" to id)) { ... }
  (dev.goquick.kmpertrace.trace.traceSpan). Inside it, call Log.d/i/w/e { "message" } directly.
- Attribute values are Strings. Keys match [A-Za-z0-9_.-]. A leading "?" marks a debug-only attribute.
  Log calls take no attributes, so put values on the span.
- Never log passwords, tokens, API keys or Authorization headers. User data and device IDs go in "?" attributes.
- A span records an exception that you rethrow, so do not also log it there. Call
  Log.e(throwable = t) { "what failed" } where you catch and handle it.
- SDK callback on another thread: val snap = captureTraceSnapshot() inside the span, then
  snap.withTraceSnapshot { ... } in the callback. Android Handler: handler.postWithTrace(snap) { ... }.
- journey() is an extension on LogContext: Log.forComponent("X").journey(operation, TraceTrigger.tap("Y")) { ... }
- View logs: kmpertrace-cli print --file app.log --span-attrs on
  or: kmpertrace-cli tui --source adb --adb-pkg <package>
- Details: docs/ai/AI-assistants.md
````

Imports used by the examples in this guide:

```kotlin fragment
import dev.goquick.kmpertrace.core.Level
import dev.goquick.kmpertrace.log.KmperTrace
import dev.goquick.kmpertrace.log.Log
import dev.goquick.kmpertrace.log.journey
import dev.goquick.kmpertrace.trace.TraceTrigger
import dev.goquick.kmpertrace.trace.captureTraceSnapshot
import dev.goquick.kmpertrace.trace.traceSpan
import dev.goquick.kmpertrace.trace.android.postDelayedWithTrace // Android only
import dev.goquick.kmpertrace.trace.android.postWithTrace // Android only
```

## 3. Conventions

**Call configure early.** No log sink is installed until `KmperTrace.configure` runs, so records logged before it are dropped. Call it at process start (for example in `Application.onCreate` on Android or `main` on desktop).

```kotlin compile
fun setUpKmperTrace(isDebugBuild: Boolean) {
    KmperTrace.configure(
        minLevel = if (isDebugBuild) Level.DEBUG else Level.INFO,
        serviceName = "my-app",
        emitDebugAttributes = isDebugBuild,
    )
}
```

**Put values in attributes.** Do not build them into the message text. A log call has no attributes parameter, and `SpanScope` has no function to add attributes later. Pass them in the `attributes` map when the span opens. Log lines inside the span carry the same trace and span IDs. Attribute values are `String`, so convert numbers with `toString()`.

**Use one name per concept.** If one file uses `mac`, every file uses `mac`. Do not use `address` in another file for the same value.

**Debug-only attributes.** A key that starts with `?` is a debug-only attribute. It is emitted only when `emitDebugAttributes = true` is passed to `configure`. When the flag is false, the attribute is dropped. With the flag on, the CLI shows the attribute with a `?` prefix, for example `?mac=...`.

**Key characters.** After the optional leading `?`, a key may contain only `A-Z`, `a-z`, `0-9`, `_`, `-` and `.`. Any other character is replaced with `_` and the key gets an `invalid_` prefix, so `user id` becomes `invalid_user_id`. An empty key becomes `invalid_key`.

**Secrets and personal data.** Never log passwords, tokens, API keys or Authorization headers, in a message or in an attribute. Treat user data and device identifiers as debug-only (`?` prefix).

**Log an exception once.** When an exception leaves a span block, the span writes its end record at ERROR level with the error type, message and stack trace. Each enclosing span that the exception leaves writes the same stack trace again. A `Log.e` call before the rethrow adds one more copy. So do not log an exception you are about to rethrow. Call `Log.e(throwable = t)` where you catch and handle the exception, for example when you show an error state or fall back to a default.

**Do not repeat the class name in the message.** The logger tag and the span already carry the component name. Write `"fetch failed"`. Avoid `"ProfileRepository: fetch failed"`.

**Log directly inside a span.** Inside a function that already runs in a span named `X`, call `Log.d { ... }` or `Log.i { ... }`. Log lines take their component and operation from the active span. Do not open a second span or call `withOperation` with the same name.

## 4. Worked examples

### a. A coroutine flow

This uses the `traceSpan(component, operation, attributes, block)` overload. The span name is `ProfileRepository.loadProfile`.

```kotlin compile
data class Profile(val name: String)

interface ProfileApi {
    suspend fun fetch(userId: String): Profile
}

class ProfileRepository(private val api: ProfileApi) {
    suspend fun loadProfile(userId: String, email: String): Profile =
        traceSpan(
            component = "ProfileRepository",
            operation = "loadProfile",
            attributes = mapOf(
                "userId" to userId,
                "?email" to email,
            ),
        ) {
            Log.d { "fetching from network" }
            api.fetch(userId)
        }
}
```

If the caller handles the failure, log it there. This is the one `Log.e` call for the exception:

```kotlin compile
suspend fun profileName(repo: ProfileRepository, userId: String, email: String): String =
    try {
        repo.loadProfile(userId, email).name
    } catch (e: Exception) {
        Log.e(throwable = e) { "could not load profile, using default" }
        "unknown"
    }
```

### b. A callback from a third-party SDK on another thread

Capture the snapshot inside the span. Re-install it in the callback with `withTraceSnapshot`. The snapshot does not create a span and does not keep the span open. The span ends when its block returns.

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

For Android `Handler` callbacks, the runtime provides two extension functions in `dev.goquick.kmpertrace.trace.android`:

- `Handler.postWithTrace(snapshot: TraceSnapshot = captureTraceSnapshot(), block: () -> Unit): Boolean`
- `Handler.postDelayedWithTrace(delayMillis: Long, snapshot: TraceSnapshot = captureTraceSnapshot(), block: () -> Unit): Boolean`

They post the block and install the snapshot while it runs. Capture the snapshot in the span, then pass it in:

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

This example needs `import android.os.Handler` and `import android.os.Looper`. Both functions exist only in the Android target.

### c. Viewing logs with the CLI

The CLI needs Java 17 or newer. Install steps are in the [README](../../README.md#quick-start). It has two commands, `print` and `tui`.

`print` renders a log file or piped input once. Add `--follow` to keep reading.

```bash
# A saved log file, with span attributes shown
kmpertrace-cli print --file app.log --span-attrs on

# Live logcat piped in
adb logcat -c && adb logcat -v epoch --pid="$(adb shell pidof com.example.app)" \
  | kmpertrace-cli print --follow --span-attrs on
```

`tui` is the interactive viewer. It reads from adb or an iOS simulator or device directly.

```bash
kmpertrace-cli tui --source adb --adb-pkg com.example.app --span-attrs on
kmpertrace-cli tui --source ios --ios-proc MyApp --span-attrs on
```

Flags that belong to `tui` only: `--source`, `--adb-pkg`, `--adb-cmd`, `--ios-proc`, `--ios-target`, `--ios-udid`, `--ios-cmd`, `--min-level`, `--trace-id`, `--component`, `--operation`, `--filter`, `--exclude-untraced` and `--max-records`.

`print` accepts `--file`, `--follow`, `--hide-source`, `--max-line-width`, `--color`, `--time-format`, `--raw-logs` and `--span-attrs`. `tui` accepts the same flags except `--follow`.

In `tui`, press `a` to toggle span attributes. Debug-only attributes show with a `?` prefix.

## 5. Common mistakes

**Assuming `journey()` writes a log line.** It does not. It records a `trigger` attribute on the span.
Correct form: read the trigger with `--span-attrs on` (or the `a` key in `tui`).

**Using `TraceSnapshot.capture()` or `snap.runWithContext { }`.** Neither exists. An older docs page showed them.
Correct form: `captureTraceSnapshot()` and `snap.withTraceSnapshot { }`.

**Calling `log { }` outside a span.** `log { }` and `log(Level.DEBUG) { }` are `SpanScope.log`. They work only inside the block of a `traceSpan` call.
Correct form: `Log.i { "message" }` anywhere else.

**Expecting `print` to accept filter flags.** `--min-level`, `--trace-id`, `--component`, `--operation`, `--filter`, `--exclude-untraced` and `--max-records` belong to `tui` only.
Correct form: `kmpertrace-cli tui --file app.log --component ProfileRepository`.

**Calling `Log.journey(...)`.** `journey` is an extension on `LogContext`, and its `trigger` parameter takes a `TraceTrigger` value, so a plain string does not compile. Called inside an active trace, it creates a child span in that trace. To start a new trace, call it where no span is active.
Correct form, at the place where the user action starts:

```kotlin compile
suspend fun onRefreshClicked(refresh: suspend () -> Unit) =
    Log.forComponent("ProfileScreen").journey(
        operation = "refresh",
        trigger = TraceTrigger.tap("RefreshButton"),
    ) {
        refresh()
    }
```

**Treating `withOperation` as a span.** `log.withOperation("x")` returns a `LogContext` that tags log lines. It creates no span.
Correct form: `traceSpan(component, operation) { ... }` for a span.

**Passing a non-String attribute value.** `mapOf("count" to 3)` does not compile, because the map type is `Map<String, String>`.
Correct form: `mapOf("count" to count.toString())`.

**Mixing up argument order.** `Log.e(tag, throwable) { }` takes the tag first. A `LogContext` such as `log.e(throwable) { }` has no tag.
Correct form: `Log.e(throwable = t) { "message" }` or `log.e(throwable = t) { "message" }`.

**Creating spans from Swift.** The Swift API (`KmperTraceSwift`) can log and re-install a snapshot. It cannot start a span.
Correct form: create the span in Kotlin and use `snapshot.with { }` in Swift. See [IOS-KMP-Swift.md](../IOS-KMP-Swift.md).
