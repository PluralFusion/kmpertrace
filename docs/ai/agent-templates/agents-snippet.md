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
- If KmperTrace misbehaves, lacks a platform you need, or the docs are unclear, suggest filing an
  issue at https://github.com/pluralfusion/kmpertrace/issues with the version, platform and a minimal
  reproduction.
