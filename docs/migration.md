# Moving to KmperTrace from println, Timber or Kermit

Verified against KmperTrace 0.3.3 (2026-10-16). The KmperTrace code and output on this page were compiled and run on the JVM target. The Timber and Kermit snippets come from their public READMEs and docs and were not compiled or run here, so this page does not describe their output.

Terms: a **span** is one timed step with its own ID. A **trace** is the set of spans that belong to one flow.

## The same code, before and after

The flow is a login call that either succeeds or fails.

### From println

Before:

```kotlin compile
suspend fun loginBefore(api: AuthApi, userId: String) {
    println("login start userId=$userId")
    try {
        api.login(userId)
        println("login ok")
    } catch (e: Exception) {
        println("login failed: ${e.message}")
        throw e
    }
}
```

After:

```kotlin compile
suspend fun loginAfter(api: AuthApi, userId: String) =
    traceSpan(
        component = "AuthRepository",
        operation = "login",
        attributes = mapOf("userId" to userId),
    ) {
        Log.i { "login start" }
        api.login(userId)
        Log.i { "login ok" }
    }
```

Both versions use this interface and the imports `dev.goquick.kmpertrace.log.Log` and `dev.goquick.kmpertrace.trace.traceSpan`:

```kotlin compile
interface AuthApi { suspend fun login(userId: String): String }
```

The `try` block is gone. A span records an exception that leaves it, so the failure shows up without a `println`.

### From Timber

Before (call forms taken from the Timber README):

```kotlin fragment
Timber.d("login start userId=%s", userId)
// ...
Timber.d(e, "login failed")
```

After: the same `loginAfter` function as above. Replace each `Timber.d("... %s", value)` call with a `Log` call and move `value` into a span attribute.

### From Kermit

Before (call forms taken from the Kermit docs):

```kotlin fragment
private val log = Logger.withTag("AuthRepository")

log.i { "login start userId=$userId" }
// ...
log.e(e) { "login failed" }
```

After: the same `loginAfter` function as above. Kermit's lambda form maps to `Log.i { }`. `Logger.withTag("AuthRepository")` maps to the `component` argument of `traceSpan`, or to `Log.forComponent("AuthRepository")` outside a span. A throwable goes in `Log.e(throwable = e) { ... }`.

## What changes in the output

`println` prints the text you wrote:

```
login start userId=42
login ok
login start userId=42
login failed: 401 unauthorized
```

KmperTrace keeps the readable text of each log line and adds a structured suffix of the form `|{ ... }|` with the trace ID, the span ID and other fields. It also writes a start record and an end record for each span. `kmpertrace-cli` rebuilds the tree from the suffixes. This is the real output of `kmpertrace-cli print --file demo.log --span-attrs on --color off` for one successful and one failed call (stack frames omitted):

```
trace 3de985d1856c8f8a
└─ AuthRepository.login (6 ms) {userId=42}
   ├─ ℹ️ 00:13:01.173 AuthRepository: login start
   └─ ℹ️ 00:13:01.174 AuthRepository: login ok

trace b9975244f1584b81
└─ ❌ AuthRepository.login {userId=42}
   ├─ ℹ️ 00:13:01.175 AuthRepository: login start
   └─ ❌ 00:13:01.175 AuthRepository: 401 unauthorized
          401 unauthorized
              ...
```

What this shows:

- Each call is one trace, with the two log lines under the span they ran in.
- The span has a duration (`6 ms` on the successful call) and the `userId` attribute.
- The failed span is marked and carries the exception message and stack trace without a `Log.e` call in the code.
- The message text holds no values, because the value is an attribute.

## Setup changes

KmperTrace needs one `KmperTrace.configure(...)` call at process start. The README Quick Start shows it. Until it runs, no sink is installed and records are dropped. Do not log secrets or personal data. Put user data in debug-only attributes by prefixing the key with `?`.

## Problems and requests

If a migration case does not work, or you need support for a platform or logging library that KmperTrace does not cover, please open an issue at https://github.com/pluralfusion/kmpertrace/issues. Include the KmperTrace version, the platform and a minimal reproduction.
