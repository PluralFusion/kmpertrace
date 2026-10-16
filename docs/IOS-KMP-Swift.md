# KmperTrace in KMP Apps with Swift Host Code

This guide is for Kotlin Multiplatform (KMP) apps that have a native iOS host target (Swift/ObjC) and
want to call KmperTrace APIs from Swift.

If you’re building a *pure Swift* app (no KMP), use `docs/IOS-XCFramework.md` instead.

## Goal

Make KmperTrace accessible from Swift **without app-specific Kotlin “bridges”** by re-exporting
KmperTrace from your KMP-produced iOS framework (e.g. `ComposeApp.framework`).

## What you get (in practice)

- Swift can emit **structured KmperTrace log records** (same format as Kotlin logs).
- Swift logs can be **attached to the same trace/span** as Kotlin code (ViewModels, Compose, repositories), so the CLI
  renders a single cohesive trace tree.
- Swift callbacks running on different queues/threads (BLE delegates, SDK callbacks, `DispatchQueue.async`) can keep
  trace correlation via **snapshot capture + restore**.

The key concept: KmperTrace span context is thread-local-ish. Swift async callbacks often execute on a different queue,
so you must propagate context explicitly.

## Recommended setup (single runtime)

In a KMP app you typically produce one iOS framework that Swift imports (your shared module’s
framework). The most reliable approach is:

1) Your KMP module depends on `kmpertrace-runtime`.
2) Your KMP framework **exports** `kmpertrace-runtime`, so Swift can see `KmperTraceSwift`.
3) Swift calls `dev.goquick.kmpertrace.swift.KmperTraceSwift` (string-based logging + snapshot helpers).

This avoids linking a second KmperTrace runtime via SwiftPM in the same app.

## Configure KmperTrace (Kotlin side)

Swift logging will only show up once the runtime is configured (min level, service name, etc.). Configure it early in
your KMP app startup (once per process):

```kotlin
import dev.goquick.kmpertrace.core.Level
import dev.goquick.kmpertrace.log.KmperTrace

KmperTrace.configure(
    minLevel = Level.DEBUG,
    serviceName = "hmscale", // or your app id
)
```

## Gradle: export KmperTrace from your KMP framework

In the module that produces the framework that Xcode imports:

```kotlin
kotlin {
  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "ComposeApp"
      isStatic = true

      // Re-export kmpertrace-runtime so Swift can access it through ComposeApp.framework.
      // Version catalogs:
      // export(libs.kmpertrace.runtime)
      //
      // Or coordinates:
      // export("dev.goquick.kmpertrace:kmpertrace-runtime:<version>")
    }
  }

  sourceSets {
    commonMain.dependencies {
      // Recommended for KMP frameworks consumed by Swift:
      // - api(...) exposes kmpertrace-runtime as part of the produced framework’s public surface
      // - export(...) (above) ensures the symbols are re-exported to Swift callers
      // Version catalogs:
      // api(libs.kmpertrace.runtime)
      //
      // Or coordinates:
      api("dev.goquick.kmpertrace:kmpertrace-runtime:<version>")
    }
  }
}
```

Notes:
- If you don’t `export(...)`, Swift won’t see KmperTrace types through your framework and you’ll end up
  re-creating per-app bridges.
- Keep a single source of truth: either call KmperTrace via your KMP framework *or* link the standalone
  `KmperTraceRuntime` xcframework (see below), but avoid doing both in the same app.

## Naming conventions (so traces are readable)

KmperTrace relies on consistent naming to build readable trees:

- `component`: stable “owner” name (feature/service/class). Examples: `IosScaleClient`, `BluetoothScanner`, `ProfileViewModel`.
- `operation`: action within the component. Examples: `connect`, `startScanning`, `loadProfile`.

In Kotlin spans, the full span name is typically `component.operation`. In Swift logs, use the same pair so raw logs and
structured logs align.

## Mental model: spans vs snapshots

- A **span** is what gives the CLI a tree node (`SPAN_START`/`SPAN_END`) and a duration.
- A **snapshot** (`KmperTraceSnapshot`) is *not* a span. It’s an *opaque capture of the current binding state*
  (trace/span IDs + “bind logs to span” mode).

Snapshots solve this specific problem:
- you are inside a Kotlin span,
- you cross a non-coroutine async boundary (Swift delegate, GCD callback, SDK callback),
- the callback runs later on a different queue without Kotlin trace context,
- you re-install the snapshot while logging so those Swift logs still attach to the originating span.

Important: a snapshot does **not** extend a span’s lifetime. To make a span cover an async operation, keep the span open
on the Kotlin side (typically by modeling the operation as a suspending function that awaits completion) and use snapshots
only for callback-thread log binding.

## Swift: logging essentials

In Swift, you usually only need two things:
- a component-bound logger (`KmperLogger`)
- an optional snapshot (`KmperTraceSnapshot`) for async callbacks

Current scope of the Swift API:
- Logs are string-based and bind to the current span when a snapshot is installed.
- Span creation is still Kotlin-first (create spans in Kotlin and attach Swift logs via snapshots).
- Structured attributes / throwable stack traces are primarily Kotlin APIs today; from Swift, put important values into
  the message and treat snapshot binding as the main feature.

### 1) Component-bound logging (no repeated component)

```swift
import ComposeApp

let log = KmperTraceSwift.shared.logger(component: "IosScaleClient")
log.i(operation: "connect", message: "starting")
log.d(operation: "connect", message: "state=\(state)")
log.e(operation: "connect", message: "failed: \(error)")
```

### 2) Snapshot binding across async boundaries

Capture a snapshot while you’re inside a Kotlin span (or right after a Kotlin call that creates/binds one), then use it
inside callbacks:

```swift
let log = KmperTraceSwift.shared.logger(component: "IosScaleClient")

let snapshot = KmperTraceSwift.shared.captureSnapshot()
DispatchQueue.main.async {
    snapshot.with {
        log.d(operation: "connect", message: "callback fired")
    }
}
```

### 3) Snapshot-aware logger (avoid forgetting to install snapshot)

If you log frequently from callbacks, bind the snapshot once:

```swift
let snapshot = KmperTraceSwift.shared.captureSnapshot()
let log = KmperTraceSwift.shared.snapshotLogger(component: "IosScaleClient", snapshot: snapshot)

log.i(operation: "connect", message: "starting")
DispatchQueue.main.async {
    log.d(operation: "connect", message: "callback fired")
}
```

You can also update the snapshot later:

```swift
let log = KmperTraceSwift.shared.snapshotLogger(component: "IosScaleClient")
log.snapshot = KmperTraceSwift.shared.captureSnapshot()
```

### 4) Capturing a value under an installed snapshot

Sometimes you want to compute something while the snapshot is installed (e.g. call code that logs internally) and return
the computed value. Use `withResult { ... }`:

```swift
let snapshot = KmperTraceSwift.shared.captureSnapshot()
let anyValue = snapshot.withResult {
    // This block runs with the snapshot installed.
    return "value"
}
```

Note: due to Kotlin/Native interop, Swift will typically see `Any?` here; cast to the expected type.

## Sharing a span between Kotlin and Swift (how to do it correctly)

### Pattern A: Kotlin creates the span, Swift logs attach via snapshot

This is the most common and recommended setup.

Kotlin (inside a span):
- call into Swift or set up Swift delegates
- capture a snapshot and pass/store it where callbacks can use it

Swift (in callbacks):
- use `snapshot.with { ... }` or a snapshot logger

Outcome:
- Swift logs show up *under the Kotlin span* in the CLI tree (same `trace`/`span` IDs).

Practical “where do I capture the snapshot?” rule:
- Capture it at a boundary where you *know* you are running inside a Kotlin span:
  - Swift code invoked by Kotlin while the span is active, or
  - Swift immediately after calling a Kotlin API that keeps the span open while work is in-flight.
- Store it in the Swift object that receives callbacks (delegate/client), and use it for every callback log
  (either via `snapshot.with { ... }` or `snapshotLogger`).

### Pattern B: Swift-only async work should still be structured

KmperTrace does not currently provide a “start span/end span handle” API from Swift. If you want host-level operations to
show as span nodes, create spans in Kotlin and keep them open while awaiting completion.

Practical approach:
- expose a Kotlin `suspend` entrypoint that wraps the async work and awaits it (using a continuation),
- inside that entrypoint, use `traceSpan { ... }` and pass a snapshot into Swift callbacks.

If you need span creation from Swift, treat it as a planned runtime feature (requires a Swift-friendly API that does not
rely on suspending lambdas).

## Swift interop gotchas (read this once)

- Kotlin/Native interop treats Kotlin function-type parameters as **escaping** closures in Swift.
  Do not try to “make them non-escaping” with `withoutActuallyEscaping(...)` — it can crash at runtime.
- Prefer `KmperTraceSnapshot.with { ... }` and `KmperSnapshotLogger` so your call sites never have to reason about escaping.

## Troubleshooting

- Swift can’t see `KmperTraceSwift`:
  - Ensure the KMP framework you import in Xcode is the one built from your Gradle config (stale frameworks are common).
  - Ensure you have both `api(kmpertrace-runtime)` and `export(kmpertrace-runtime)` for the produced framework.
- Logs show up but are “unscoped” (outside the tree):
  - You’re logging from a callback queue without installing a snapshot; use `captureSnapshot()` + `snapshot.with { ... }`.
  - The Kotlin span may have already ended; a snapshot can’t resurrect a closed span.
  - Make sure the async operation is modeled so the Kotlin span stays open while work is in-flight.

## Verify in `kmpertrace-cli`

Once your app emits logs, validate that Swift logs are scoped under the expected Kotlin spans:

```bash
./gradlew :kmpertrace-cli:installDist
kmpertrace-cli tui --source ios --ios-target sim --ios-proc YourAppProcess
```

On a real device:

```bash
kmpertrace-cli tui --source ios --ios-target device --ios-proc YourAppProcess
```

Tips:
- If you have multiple booted simulators or multiple connected devices, pass `--ios-udid`.
- Use `[r]` in the TUI to toggle raw logs if you want to see non-KmperTrace lines too.
- If device streaming shows no logs, check `docs/CLI-UserGuide.md` troubleshooting (especially `idevicesyslog` being single-consumer).

## Alternative (not recommended for KMP apps): link the standalone XCFramework

KmperTrace also publishes a standalone `KmperTraceRuntime.xcframework` for pure Swift apps via SwiftPM:
see `docs/IOS-XCFramework.md`.

For KMP apps, prefer exporting KmperTrace from your KMP framework so the whole app uses the same
runtime instance and the same trace/log binding state.
