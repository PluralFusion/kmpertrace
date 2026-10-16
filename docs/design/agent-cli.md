# Design: kmpertrace-cli for agents that read traces

Status: proposal only. Nothing in this document is implemented. Checked against KmperTrace 0.3.3 (2026-10-16). Claims about existing code name the file they come from. Example outputs are mock-ups written by hand, not output of the CLI.

Terms: a **span** is one timed step with its own ID. A **trace** is the set of spans that belong to one flow. An **agent** is an AI coding assistant that reads logs by running commands.

## Is there a need?

### Developers

What the public record shows on 2026-10-16:

- The repository has 41 stars and one fork. The fork has no commits ahead of `main`.
- One external issue exists. On 2026-03-04 a user asked for support for desktop Kotlin/Native targets, in particular `mingwX64`. It shipped in 0.3.3 on 2026-03-10.
- GitHub's traffic page, for the last 14 days, lists 4 views from 4 visitors and 88 clones from 22 unique cloners. Clones can include automation, so this is a rough signal.
- I found no issue that asks for Wasm support. The runtime already has a `wasmJs` target (`kmpertrace-runtime/build.gradle.kts`). Requests for Wasm or Windows may have reached the maintainers through channels I cannot see.

So developer interest is real, small and measured by one acted-on request. More requests would make it clearer.

### Agents

The benchmark in `docs/ai/log-format-benchmark.md` (PR #11) and its larger-log extension (PR #12) give the first numbers. They are small tests: one model, three runs per cell, scenarios and rubrics written by the AI assistant that ran the test.

- Raw KmperTrace lines cost 2.4 to 4.6 times more tokens than a plain log. Reading them as text is the expensive path.
- On short logs, the `kmpertrace-cli print` tree cost 10% to 53% less than a plain log. On a 791-line log it cost 23% more, even though the file is smaller.
- A filtered view (failed traces, then one trace) cost 51% fewer tokens than the plain log and 61% fewer than the full tree. The filtered view in that test was a prototype script around `print`, since the CLI has no filter flags.
- Accuracy was at the ceiling on the larger log (12 of 12), so the benefit measured there is tokens, not correctness.

The need on the agent side is plausible and unproven. It rests on one scenario and a prototype. The way to find out is to build the smallest real feature, rerun the benchmark with it, and extend the benchmark: a vague symptom, logs of thousands of lines, a second model and more runs.

## What exists today

- `kmpertrace-cli` has two commands, `print` and `tui`. Both produce text for people (`kmpertrace-cli/src/main/kotlin/dev/goquick/kmpertrace/cli/Main.kt`, `Renderer.kt`). There is no JSON or other machine-readable output. The CLI has no JSON library dependency (`kmpertrace-cli/build.gradle.kts` lists clikt, mordant and the two internal modules).
- `parseLine` and `parseLines` turn structured log lines into `ParsedLogRecord` values (`kmpertrace-parse/.../ParseApi.kt`). A record carries trace and span IDs, kind, duration, source fields and all raw fields in `rawFields`, including `status`, `err_type`, `err_msg` and `stack_trace` when present (`ParsedLogRecord.kt`, `Tracer.kt` for the field names).
- `buildTraces` groups records by trace ID into `TraceTree` values, each holding a tree of `SpanNode` (`TraceBuilder.kt`, `SpanModels.kt`). `SpanNode` has span ID, parent ID, name, kind, `durationMs`, start timestamp, source fields, `attributes`, `records` and `children`. Records with trace ID `0` are left out of trees.
- `SpanNode.attributes` keeps the wire prefixes: `a:` for normal and `d:` for debug-only attributes (`TraceBuilder.kt`, `extractSpanAttributes`).
- `SpanNode` has no error field. The renderer decides that a span failed by looking for a span-end record with `status=ERROR` or a `throwable` field (`Renderer.kt`, `renderSpan`).
- `print` already builds its view through `ingestLines(lines, FilterState(), rawLevel, maxRecords = 10_000)` with an empty `FilterState`, so the hook for filters exists but no flag sets it. It also keeps at most 10,000 records (`Main.kt`, `processLines`).
- `AnalysisEngine` ingests lines and returns an `AnalysisSnapshot` of traces, untraced records and a dropped count (`kmpertrace-analysis/.../AnalysisEngine.kt`). `FilterState` filters records by minimum level, trace ID, component, operation, text and untraced records (`FilterState.kt`). Two properties matter here. It filters single records, not whole traces, so filtering by component would drop the other records of a trace and could break the tree. Its `when` chain returns at the `minLevel` branch, so a `text` filter is ignored when `minLevel` is also set. I read this in the code and did not run it.
- A span-end record omits `dur` when the duration is 0 ms (`LogFormatting.kt`, the `durationMs != 0L` check). So `SpanNode.durationMs` can be null for a span that took under 1 ms. A machine-readable output has to decide how to report that.

## Idea 1: a filtered print

This is the idea the benchmark supports most. In the larger-log test the model spent most of its tokens reading traces it did not need. A filtered view returned the one failing trace in about 4 KB, against 49 KB for the full tree.

Command shape (flags that `tui` mostly has already, plus two new ones):

```
kmpertrace-cli print --file app.log --failed
kmpertrace-cli print --file app.log --trace-id dcaeb784
kmpertrace-cli print --file app.log --component PaymentService --min-level warn
kmpertrace-cli print --file app.log --filter "idempotencyKey" --max-traces 5
```

`--failed` keeps only traces that contain a failed span. `--trace-id` accepts a prefix. `--max-traces N` keeps the first N traces and prints how many were left out.

Mock output for the first command (the failing trace only, trimmed):

```
1 of 24 traces shown (--failed)
trace dcaeb7846f2429b6
└─ ❌ Checkout.run (184 ms) {items=7 orderId=o17}
   ├─ CartService.validate (36 ms) {orderId=o17}
   ├─ Inventory.reserve (40 ms) {orderId=o17}
   ├─ ❌ PaymentService.charge (91 ms) {amountCents=6329 orderId=o17}
   │  ├─ 🔍 PaymentService: order=o17 charge attempt 1 idempotencyKey=k17-1
   │  ├─ ⚠️ PaymentService: order=o17 gateway timeout (504) on attempt 1
   │  ├─ 🔍 PaymentService: order=o17 charge attempt 2 idempotencyKey=k17-2
   │  └─ ❌ PaymentService: order=o17 charge rejected: duplicate charge for card, key=k17-2
```

What it needs:

- CLI: new options on `PrintCommand` that build a `FilterState` and pass it to `ingestLines`. `processLines` passes an empty one today (`Main.kt`).
- Analysis: filtering by whole trace. `FilterState.predicate` works on single records (`FilterState.kt`), so a component filter would remove other records of the same trace and could break the tree. The filter should select traces first, then render each selected trace in full.
- Analysis: a failed-span predicate for `--failed`. The renderer has the rule (`Renderer.kt`, `renderSpan`). It should move into the parse or analysis module so every command uses one definition.
- Analysis: fix the `when` chain in `FilterState.predicate`, which ignores `text` when `minLevel` is set.
- The record cap in `print` is 10,000 (`Main.kt`). Large logs would need a higher limit or a message saying records were dropped. `AnalysisSnapshot` already carries a dropped count (`AnalysisEngine.kt`).
- Tests: `print` has golden tests (`RenderGoldenTest.kt`) that new flags can follow.

How to test the benefit: rerun scenario S5 of the benchmark with the real flags in place of the prototype scripts in `docs/ai/benchmark/tools/`.

Effort: small to medium. The option wiring is small. Trace-level filtering and the shared failed-span rule are the larger part.

## Idea 2: JSON output

Command shape:

```
kmpertrace-cli print --file app.log --format json
```

`--format {text|json}` with `text` as the default. `json` writes one trace per line (JSON Lines), so an agent can stream it and filter with common tools. Add `"schema": 1` to every object so the shape can change later.

Mock output (one trace, line-wrapped here for reading):

```json
{"schema":1,"trace":"b9975244f1584b81","spans":[
  {"id":"bfa0ccb22c4d2f1b","parent":null,"name":"AuthRepository.login",
   "durationMs":null,"status":"ERROR",
   "error":{"type":"IllegalStateException","message":"401 unauthorized"},
   "attributes":{"userId":"42"},"debugAttributes":{},
   "logs":[{"ts":"2026-10-16T04:13:01.175Z","level":"info","message":"login start"}],
   "children":[]}]}
```

What it needs:

- Parser: nothing new for the core fields. `SpanNode` has them (`SpanModels.kt`). The writer strips the `a:` and `d:` prefixes (`TraceBuilder.kt`) and reads `status`, `err_type`, `err_msg` from the span-end record in `SpanNode.records`.
- Parser, optional: add `status` and `error` to `SpanNode` so the renderer and the writer share one definition of a failed span. Today the check lives in `Renderer.kt`.
- Debug-only attributes: emit them only with a flag such as `--debug-attrs`, matching how `--span-attrs on` is opt-in today (`Main.kt`).
- Null durations: write `null` and document that it can mean under 1 ms, or write `0` when a span-end record exists without `dur`.
- CLI: a format option on `PrintCommand` and a JSON writer next to `Renderer.kt`. The `PrintProcessor` type alias in `Main.kt` would gain a parameter, and tests that construct `PrintCommand` would change. The writer can be hand-written for this flat shape. That avoids a new dependency.

Effort: small.

## Idea 3: a summarize command

Command shape:

```
kmpertrace-cli summarize --file app.log --top 5
```

Mock output:

```
traces: 12   spans: 87   untraced records: 3   dropped: 0
errors: 2 spans in 2 traces
  IllegalStateException  AuthRepository.login  (401 unauthorized)
  TimeoutException       Api.fetch             (timeout after 10 s)
slowest spans:
  4210 ms  Api.fetch                 trace 3de985d1856c8f8a
  1730 ms  ProfileViewModel.refreshAll  trace 7aa01f33c0b9d412
   620 ms  Database.loadProfile      trace 3de985d1856c8f8a
```

What it needs:

- Analysis: a function over `List<TraceTree>` that flattens spans, counts errors and sorts by `durationMs`. Today `AnalysisEngine.snapshot()` returns the trees and the untraced records but no aggregates (`AnalysisEngine.kt`). New code belongs in `kmpertrace-analysis` so the TUI can reuse it.
- The same failed-span definition as ideas 1 and 2.
- Treatment of null durations, as in idea 2.
- Filters: reuse `FilterState` flags that `tui` already exposes (`--component`, `--operation`, `--min-level`). Today those are `tui` only (`Main.kt`).
- Add `--format json` to `summarize` as well, so agents get the same data as an object.

Effort: small to medium. The aggregation is small. Sharing filter flags between `print`, `tui` and `summarize` is the larger part.

## Idea 4: an MCP server that wraps the CLI

An MCP server (Model Context Protocol, a standard way for an agent to call tools) would expose read-only tools such as `list_traces`, `get_trace` and `summarize`. Each tool would run the CLI with `--format json` and return the result.

Mock tool list:

```
list_traces(file, limit)        -> trace IDs, root span names, durations, error flag
get_trace(file, trace_id)       -> the JSON object from idea 2
summarize(file, top)            -> the object from idea 3
```

What it needs:

- Ideas 1 to 3 first. The server has nothing to wrap without them.
- A Java 17 or newer runtime, which the CLI already requires (README Quick Start).
- A new module or repository, a tool schema per tool, and a packaging and install story. These details depend on the MCP SDK that is chosen. I did not check which SDKs exist for the JVM.
- Live sources (`adb`, iOS) are streams. A server would need to buffer them. The `tui` already does this with `AnalysisEngine` (`Main.kt`), but nothing exposes it over a protocol.

Whether it is worth building: agents that can run shell commands can call the CLI with `--format json` directly, so the server adds little for them. It helps agents that cannot run commands and teams that want a single configured tool. That value is not yet shown. I would wait for evidence that people use ideas 1 to 3.

Effort: medium to large.

## Recommendation

Build in this order:

1. A filtered `print` (idea 1): `--failed`, `--trace-id`, `--component`, `--operation`, `--min-level`, `--filter`. It is the smallest change, it reuses code that exists, and it is the idea with measured token savings (51% against plain logs in a prototype). Then rerun the benchmark with the real flags.
2. JSON output (idea 2), which keeps the output small for agents and avoids the box-drawing and emoji characters. I did not measure whether those cost more tokens.
3. `summarize` (idea 3), using the failed-span rule from step 1 so the renderer, the filter, the JSON writer and `summarize` agree.
4. Revisit the MCP server (idea 4) once ideas 1 to 3 have users and a maintainer has asked for it.

Do not promote the full tree as the token-saving way to read a large log. In the benchmark it was no cheaper than grepping a plain log.

Decide these details before step 1: whether `--failed` keeps the whole trace, which this document suggests, and how to report a null duration in the later steps. This document suggests null with a documented meaning, and debug-only attributes off by default in JSON.

## How to learn whether the need is real

- **Developers:** keep the adoption signals that GitHub gives for free (stars, forks, issues, traffic referrers). A pinned "who uses KmperTrace" issue would give people a place to say which platform they need.
- **Agents:** extend the benchmark as listed in `docs/ai/log-format-benchmark.md` under next tests, and rerun it after each CLI change. A feature that does not move the numbers is not worth shipping for agents, even if it is nice for people.
- **Both:** ask an early user to run the filtered `print` on a real log and report what they would change.
