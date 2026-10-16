# Design: kmpertrace-cli for agents that read traces

Status: proposal only. Nothing in this document is implemented. Checked against KmperTrace 0.3.3 (2026-10-16). Claims about existing code name the file they come from. Example outputs are mock-ups written by hand, not output of the CLI.

Terms: a **span** is one timed step with its own ID. A **trace** is the set of spans that belong to one flow. An **agent** is an AI coding assistant that reads logs by running commands.

## What exists today

- `kmpertrace-cli` has two commands, `print` and `tui`. Both produce text for people (`kmpertrace-cli/src/main/kotlin/dev/goquick/kmpertrace/cli/Main.kt`, `Renderer.kt`). There is no JSON or other machine-readable output. The CLI has no JSON library dependency (`kmpertrace-cli/build.gradle.kts` lists clikt, mordant and the two internal modules).
- `parseLine` and `parseLines` turn structured log lines into `ParsedLogRecord` values (`kmpertrace-parse/.../ParseApi.kt`). A record carries trace and span IDs, kind, duration, source fields and all raw fields in `rawFields`, including `status`, `err_type`, `err_msg` and `stack_trace` when present (`ParsedLogRecord.kt`, `Tracer.kt` for the field names).
- `buildTraces` groups records by trace ID into `TraceTree` values, each holding a tree of `SpanNode` (`TraceBuilder.kt`, `SpanModels.kt`). `SpanNode` has span ID, parent ID, name, kind, `durationMs`, start timestamp, source fields, `attributes`, `records` and `children`. Records with trace ID `0` are left out of trees.
- `SpanNode.attributes` keeps the wire prefixes: `a:` for normal and `d:` for debug-only attributes (`TraceBuilder.kt`, `extractSpanAttributes`).
- `SpanNode` has no error field. The renderer decides that a span failed by looking for a span-end record with `status=ERROR` or a `throwable` field (`Renderer.kt`, `renderSpan`).
- `AnalysisEngine` ingests lines and returns an `AnalysisSnapshot` of traces, untraced records and a dropped count (`kmpertrace-analysis/.../AnalysisEngine.kt`). `FilterState` filters by minimum level, trace ID, component, operation, text and untraced records (`FilterState.kt`).
- A span-end record omits `dur` when the duration is 0 ms (`LogFormatting.kt`, the `durationMs != 0L` check). So `SpanNode.durationMs` can be null for a span that took under 1 ms. A machine-readable output has to decide how to report that.

## Idea 1: JSON output

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

## Idea 2: a summarize command

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
- The same failed-span definition as idea 1.
- Treatment of null durations, as in idea 1.
- Filters: reuse `FilterState` flags that `tui` already exposes (`--component`, `--operation`, `--min-level`). Today those are `tui` only (`Main.kt`).
- Add `--format json` to `summarize` as well, so agents get the same data as an object.

Effort: small to medium. The aggregation is small. Sharing filter flags between `print`, `tui` and `summarize` is the larger part.

## Idea 3: an MCP server that wraps the CLI

An MCP server (Model Context Protocol, a standard way for an agent to call tools) would expose read-only tools such as `list_traces`, `get_trace` and `summarize`. Each tool would run the CLI with `--format json` and return the result.

Mock tool list:

```
list_traces(file, limit)        -> trace IDs, root span names, durations, error flag
get_trace(file, trace_id)       -> the JSON object from idea 1
summarize(file, top)            -> the object from idea 2
```

What it needs:

- Ideas 1 and 2 first. The server has nothing to wrap without them.
- A Java 17 or newer runtime, which the CLI already requires (README Quick Start).
- A new module or repository, a tool schema per tool, and a packaging and install story. These details depend on the MCP SDK that is chosen. I did not check which SDKs exist for the JVM.
- Live sources (`adb`, iOS) are streams. A server would need to buffer them. The `tui` already does this with `AnalysisEngine` (`Main.kt`), but nothing exposes it over a protocol.

Whether it is worth building: agents that can run shell commands can call the CLI with `--format json` directly, so the server adds little for them. It helps agents that cannot run commands and teams that want a single configured tool. That value is not yet shown. I would wait for evidence that people use ideas 1 and 2.

Effort: medium to large.

## Recommendation

Build in this order:

1. JSON output (idea 1). It is small, has no new dependency, and every later idea builds on it.
2. `summarize` (idea 2), with the failed-span definition moved into the parser so the renderer, the JSON writer and `summarize` agree.
3. Revisit the MCP server (idea 3) after ideas 1 and 2 have users and a maintainer has asked for it.

Decide two details before step 1: how to report a null duration, and whether debug-only attributes appear in JSON by default. This document suggests null with a documented meaning, and debug-only attributes off by default.
