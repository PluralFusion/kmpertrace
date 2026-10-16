# Prompts for AI coding assistants

Ten prompts you can copy into an AI coding assistant. Each names the files or logs it expects, so replace the paths with your own. They assume the assistant can read [AI-assistants.md](AI-assistants.md), or that you pasted its AGENTS.md block into your project. Verified against KmperTrace 0.3.3 (2026-10-16).

## 1. Add KmperTrace and wrap the login flow

```
Add KmperTrace 0.3.3 to this app and wrap the login flow in a span.
Files: the shared module's build.gradle.kts, the app entry point (Application class or main),
and LoginRepository.kt.
Follow docs/ai/AI-assistants.md: add dev.goquick.kmpertrace:kmpertrace-runtime, call
KmperTrace.configure once at process start, and wrap login in traceSpan with the userId as an
attribute. Put user data in "?" debug-only attributes.
```

## 2. Keep an SDK callback in one trace

```
Instrument the SDK callback in ScaleClient.kt so its logs stay in the same trace as the span
that registers it.
Capture a snapshot with captureTraceSnapshot() inside the span and use snap.withTraceSnapshot { }
in the callback. If the callback is posted through an Android Handler, use
Handler.postWithTrace(snapshot) { }.
```

## 3. Find the slowest span in a log

```
Run kmpertrace-cli print --file app.log --span-attrs on --color off and tell me which span has
the longest duration. The duration is printed in parentheses after the span name. Report the
span name, its duration, its attributes and its parent span.
```

## 4. Explain a failed trace

```
In app.log, find the traces that contain a span marked with the error symbol. Use
kmpertrace-cli print --file app.log --span-attrs on --color off. For each one, give the failing
span, the exception message and the first stack frame in our own code.
```

## 5. Replace println calls

```
Replace the println calls in CheckoutService.kt with KmperTrace, following docs/migration.md.
Use one traceSpan for the function, put values in attributes, and use Log.i or Log.e with a
message lambda. Do not log an exception that is rethrown, because the span records it.
```

## 6. Migrate from Timber or Kermit

```
Migrate the Timber (or Kermit) calls in the :data module to KmperTrace, following docs/migration.md.
List the files you changed. Keep one name per concept in attribute keys, for example always
userId. Tell me which calls had no direct KmperTrace equivalent.
```

## 7. Review attributes for sensitive data

```
Review every traceSpan and journey call in this repository. List any attribute or log message
that could contain a password, token, API key, Authorization header, user data or device
identifier. Move user data and device identifiers to "?" debug-only attributes and remove
secrets entirely.
```

## 8. Start a trace from a button tap

```
In ProfileScreen.kt, start a trace when the user taps Refresh. Use
Log.forComponent("ProfileScreen").journey(operation = "refresh", trigger = TraceTrigger.tap("RefreshButton")) { }
around the existing refresh call. journey is an extension on LogContext. Do not call it deep
inside another span.
```

## 9. Fix logs that appear outside any trace

```
These log lines in app.log have no trace ID: <paste the lines>. Find the code that emits them.
If they run in a callback, executor or Handler, capture a snapshot in the originating span and
re-install it with withTraceSnapshot. If they run after a dispatcher change you control, use
withTraceContext(dispatcher) { }. Read the "Why trace=0 happens" section of docs/Tracing.md first.
```

## 10. Draft a KmperTrace issue

```
KmperTrace 0.3.3 does not do what I need on <platform or case>. Draft a GitHub issue for
https://github.com/pluralfusion/kmpertrace/issues. Include the KmperTrace version, the
platform and Kotlin version, a minimal reproduction, the expected result and the actual
result. Do not include secrets or personal data.
```
