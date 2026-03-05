# What Is KmperTrace? And Why Does It Matter?

KmperTrace is a tracing and structured logging toolkit for Android, iOS/Swift, Desktop, and Wasm.
It helps you reconstruct end-to-end execution flows from plain logs.

In real apps, logs exist, but the story does not.
You see isolated lines and it is hard to answer simple questions:

- What user action started this flow?
- Which async step failed?
- How did execution hop across coroutines and callbacks?

KmperTrace is designed to solve that gap.

## What KmperTrace is

KmperTrace has two main parts:

- `kmpertrace-runtime`: a runtime library that lets you wrap work in spans (`traceSpan { ... }`) and emit structured
  logs with trace and span IDs.
- `kmpertrace-cli`: a CLI that reads those logs and reconstructs readable trace trees with nesting, durations, errors,
  and context.

It works across Android, iOS/Swift, Desktop (JVM), and Wasm, and keeps trace context attached across coroutine and
thread hops.
For non-coroutine async boundaries (SDK callbacks, handlers, executors), it provides `TraceSnapshot` helpers to
re-attach context.

Under the hood, KmperTrace uses a Kotlin Multiplatform runtime, but you do not need to position it as "for KMP only" to
get value.

![KmperTrace CLI output example](cli_scr1.png)

## Why it is valuable

### 1) You get traceability from plain logs

KmperTrace does not require a collector, agent, or observability backend to be useful.
It encodes span lifecycle and IDs directly into structured log lines, so you can use your existing log pipeline.

That means you can start small: add spans, collect logs, and still reconstruct full flow trees later.

### 2) One debugging language across platforms

Android and iOS usually drift into different logging styles over time.
KmperTrace gives shared conventions for span names, component/operation metadata, and trace IDs, so debugging
discussions are less ambiguous.

### 3) Faster root-cause analysis

Instead of scanning raw logs line by line, the CLI groups records by trace and rebuilds call trees.
You see parent/child relationships, timing, and error stacks in one place.
This shortens the path from "something failed" to "this span failed for this reason after this sequence."

### 4) Better "user journey" visibility

With `LogContext.journey(...)`, traces can start from explicit triggers like taps or system events.
This makes traces read like real product flows, not only method-level traces.

### 5) Control over signal vs noise

You can tune logging levels and optionally gate debug attributes with configuration (`emitDebugAttributes`).
That helps teams keep useful production logs while limiting noisy or sensitive details.

## A small example

```kotlin
suspend fun refreshAll() = traceSpan(component = "ProfileViewModel", operation = "refreshAll") {
    Log.i { "Refreshing profile..." }
    repository.loadProfile()
    repository.loadContacts()
    repository.loadActivity()
    Log.i { "Refresh complete" }
}
```

Then render a collected log file:

```bash
kmpertrace-cli print --file /path/to/app.log --color=on
```

The CLI reconstructs span trees from those structured lines, including nested operations and failures.

## When KmperTrace is a good fit

- You are building Android, iOS/Swift, Desktop, or Wasm apps and debugging async flows is expensive.
- You need better production debugging from logs you already collect.
- You want trace context that survives coroutine and callback boundaries.

## Current maturity

KmperTrace is actively evolving and APIs may still change before `1.0`.
For teams that value early observability and fast debugging loops, it already provides strong practical value today.

## Repository

Take a look at our repository to find out more (code, libraries, installer for CLI, etc.):
https://github.com/pluralfusion/kmpertrace
