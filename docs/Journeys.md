# Journeys (trigger-first traces)

This guide shows how to use the `journey(...)` API to make traces read like a human story:

> “Who started this, why, what happened next, and where did it fail?”

It is designed for app debugging (including HMScale): you start a journey at the *trigger* (user/system event), then do the work inside that span so the CLI shows one coherent tree.

---

## What you get

- A span intended to represent a single user/system “story”.
- A required, standardized trigger (`tap.*`, `tab.*`, `system.*`).
- A `trigger` span attribute (`a:trigger=...`), emitted on both span start and span end. This attribute is the record of the trigger. `journey(...)` does not emit a separate log line.
- Default nesting behavior:
  - If no trace is active → the journey span becomes the **root span** (new trace).
  - If a trace/span is already active → the journey span becomes a **child span** in the same trace.

---

## API quick reference

```kotlin
import dev.goquick.kmpertrace.log.Log
import dev.goquick.kmpertrace.log.journey
import dev.goquick.kmpertrace.trace.TraceTrigger

val log = Log.forComponent("PairDeviceNode")

suspend fun onPairClicked(rescan: Boolean) = log.journey(
    operation = "scanForScales",
    trigger = TraceTrigger.tap("PairButton"),
    attributes = mapOf("rescan" to rescan.toString())
) {
    // Any spans + logs here are part of the journey tree.
}
```

Trigger helpers:

- `TraceTrigger.tap("PairButton")` → `tap.PairButton`
- `TraceTrigger.tab("Onboarding")` → `tab.Onboarding`
- `TraceTrigger.system("bootstrapStateChanged")` → `system.bootstrapStateChanged`
- `TraceTrigger.custom("…")` for rare cases (still sanitized)

---

## HMScale usage patterns

### 1) Start journeys at UI entry points

Start the journey as close as possible to the trigger:

- button `onClick`
- “rescan” action
- navigation event
- screen attach (if that’s the real trigger)

Compose pattern:

```kotlin
import dev.goquick.kmpertrace.log.Log

val log = Log.forComponent("PairDeviceNode")
val scope = rememberCoroutineScope()

Button(onClick = {
    scope.launch {
        log.journey(
            operation = "scanForScales",
            trigger = TraceTrigger.tap("PairButton"),
            attributes = mapOf("rescan" to "false")
        ) {
            scanForScales()
        }
    }
}) { /* ... */ }
```

### 2) Use attributes for “why”, not for narration

Keep attributes short and stable: things you’d want to see next to a span in the CLI.

Examples:

- `rescan=true`
- `sessionId=...`
- `mode=provider`

If you need debug-only data, use the existing `?key` convention on span attributes (emitted only when enabled):

```kotlin
attributes = mapOf(
    "rescan" to "true",
    "?deviceName" to deviceName
)
```

### 3) If you still see `trace=0`, bridge the boundary with `TraceSnapshot`

`journey(...)` (and spans in general) propagate through normal coroutine suspension/resume.
Most “trace=0” lines come from:

- non-coroutine callbacks / SDK listeners
- posting to `Handler` / executors
- launching work into an unrelated scope that doesn’t inherit the current coroutine context

The supported fix is `TraceSnapshot`:

```kotlin
import dev.goquick.kmpertrace.log.Log
import dev.goquick.kmpertrace.trace.captureTraceSnapshot

log.journey("scanForScales", TraceTrigger.tap("PairButton")) {
    val snap = captureTraceSnapshot()

    bluetoothSdk.setListener { state ->
        snap.withTraceSnapshot {
            Log.d { "state=$state" }
        }
    }
}
```

If you need to launch into a separate scope, pass the snapshot coroutine context:

```kotlin
import dev.goquick.kmpertrace.log.Log

val snap = captureTraceSnapshot()
backgroundScope.launch(snap.asCoroutineContext()) {
    Log.i { "running in background but still attached to the journey span" }
}
```

---

## What you should see in the CLI

At minimum, each journey trace will have:

- a root span like `PairDeviceNode.scanForScales`
- the trigger on the span as the `trigger` attribute (`tap.PairButton`), visible with `--span-attrs on` or the `a` key in `tui`
- your nested spans + logs under it

If you see “floating” bullets outside any trace tree, treat that as a signal you crossed a boundary that needs a `TraceSnapshot` bridge.

