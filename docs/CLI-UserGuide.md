# KmperTrace CLI — User Guide

Practical how-to for both non-interactive (`print`) and interactive (`tui`) modes. Keep this current
when flags or UX change.

## Quick Start (copy/paste)

```bash
# Install once
./gradlew :kmpertrace-cli:installDist

# Non-interactive: render a file
kmpertrace-cli print --file app.log --color=on

# Non-interactive: pipe and follow
adb logcat -v epoch | kmpertrace-cli print --follow --color=on

# Interactive: Android live (auto PID reattach)
kmpertrace-cli tui --source adb --adb-pkg dev.goquick.kmpertrace.sampleapp

# Interactive: iOS sim
kmpertrace-cli tui --source ios --ios-proc SampleApp

# Interactive: iOS device (requires idevicesyslog/libimobiledevice)
# (on Mac you can install it with: brew install libimobiledevice)
kmpertrace-cli tui --source ios --ios-target device --ios-proc SampleApp
```

## When to Use Which Mode

- `print`: one-shot or piping (CI logs, saved files, scripts). `--follow` tails stdin/file.
- `tui`: live viewing with incremental redraw, search prompt, status bar, auto width.

## Key Behaviors

- Wrapping: default `tui` uses `--max-line-width=auto`; `print` is unlimited unless set. Soft-wrap
  for user messages; stacks/metadata stay hard-wrapped.
- Follow (`print --follow`): live-refreshes the full view in-place (clears the screen, not scrollback)
  when stdout is a TTY. If you redirect stdout to a file, it will append refreshed snapshots.
- Sources:
    - `adb`: builds a command that waits for the package PID and reattaches if the app restarts.
    - `ios`: in `--ios-target=sim` uses `xcrun simctl ... log stream`; in `--ios-target=device` uses `idevicesyslog -p <proc> --no-colors`. `--ios-proc` is required and is used to keep only app-emitted lines (matches `<proc>(...)[pid]` style).
    - `file`/`stdin`: read a file/stream. `--follow` (print-only) tails the file/stdin stream.
- Buffer: ring buffer (default 5000 records). Dropped count shown in the status bar.
- Raw logs (opt-in): `--raw-logs` or `[r]` in `tui` includes non-KmperTrace lines from the input
  stream in the untraced timeline. With `--source adb/ios` the stream is already process-filtered.
  `[r]` cycles `off → all → debug → info → warn → error → off`.
- Parsing in both `print` and `tui`:
    - Structured KmperTrace lines end with `|{ … }|`; they are always parsed first and shown in
      traces/untraced sections.
    - When raw is enabled, non-KmperTrace lines are also shown. We understand common Android inputs
      (`adb logcat -v epoch`, threadtime, Android Studio console copy/paste) and Apple unified logs
      (`log stream/show`).

## TUI Status Bar

The last line is a compact status/action bar, e.g.:

```
errors=0 | [s] struct-logs=all | [r] raw-logs=off | [a] attrs=off | [/] filter=off | [?] help | [c] clear | [q] quit
```

- `struct-logs` controls the structured min level filter (`s` cycles all → debug → info → error).
- `raw-logs` controls inclusion of non-KmperTrace lines (`r` cycles off → all → debug → info → warn → error → off).
- `attrs` toggles span attribute rendering (`a` toggles off/on; debug attrs render as `?key=...` when present).
- `filter` is a substring search across message/stack; set it with `/` (empty clears).

## Flags (essentials)

- Inputs (`print` accepts only `-f/--file` and `-F/--follow`; it reads stdin when `--file` is not set.
  The other input flags are tui only):
    - `-s/--source {stdin|file|adb|ios}` (tui only, case-insensitive). If omitted, it is inferred: `file` when
      `--file` is set, `adb` when `--adb-pkg` or `--adb-cmd` is set, `ios` when any `--ios-*` flag is set,
      otherwise `stdin`. `--source file` requires `--file`.
    - `-f/--file PATH` use a file (both commands).
    - `-F/--follow` (print only) tail stdin or the file and live-refresh.
    - `--adb-pkg PKG` (tui only) build the adb command (waits for PID, reattaches on restart); `--adb-cmd CMD` (tui only)
      override entirely.
    - `--ios-target {auto|sim|device}` (tui only) chooses simulator vs real device (default `auto`; requires explicit choice when both are available).
    - `--ios-proc NAME` (tui only) iOS process name (required). Used for simulator predicate and for device-side filtering.
    - `--ios-udid UDID` (tui only) selects a specific simulator/device when multiple exist (use `idevice_id -l` for device UDIDs).
    - `--ios-cmd CMD` (tui only) override entirely (advanced).
- Formatting (defaults: `color=auto`, `tui` width=auto, `print` width=unlimited,
  `time-format=time-only`, sources shown):
    - `-C/--color {auto|on|off}` force/disable color.
    - `-w/--max-line-width {N|auto|unlimited|0}` wrap width. `auto` tracks terminal width (tui
      default). `0`/`unlimited` disables wrap.
    - `-T/--time-format {full|time-only}` `full` = `2025-01-01T12:34:56.789Z`; `time-only` =
      `12:34:56.789` (default).
    - `-H/--hide-source` hide component/operation metadata.
    - `--span-attrs {off|on}` show span attributes next to span names (default `off`); debug attributes render as `?key=...`.
- Filters (tui only; `print` has no filter flags. Stack/message search is case-insensitive):
    - `-m/--min-level {verbose|debug|info|warn|error|assert}`
    - `--trace-id ID`, `--component NAME`, `--operation NAME`
    - `-F/--filter SUBSTRING` match message/stack.
    - `--exclude-untraced` drop records with `trace=0`.
- Buffer (tui only):
    - `-M/--max-records N` ring buffer size before dropping oldest (default 5000).
- Raw logs:
    - `--raw-logs {off|all|verbose|debug|info|warn|error|assert}` (default `off`). Applies to both
      `print` and `tui`; merges matching raw lines into the output. In `tui`, `[r]` cycles the level
      even if the flag was not set.

## Interactive Keys (tui)

- `?` - help (dismissed by any other key)
- `c` - clear
- `r` - cycle raw logs (off → all → debug → info → warn → error → off)
- `a` - toggle span attributes (off → on)
- `s` - cycle structured min level (all → debug → info → error)
- `/` - search term (empty clears)
- `q` - quit

_Raw single-key on Unix. If raw unavailable (e.g., Windows), type the letter then Enter._

## Troubleshooting

- Nothing from `adb`: ensure `--adb-pkg` is correct and the app is installed; CLI waits for PID and
  reattaches on restart.
- Nothing from iOS device: `idevicesyslog` often behaves like a single-consumer stream. If you have
  another `idevicesyslog` already running (even in another terminal), stop it (`Ctrl+C`) or kill it
  (`pkill idevicesyslog`), then restart `kmpertrace-cli`.
- Wide/ugly stacks: set `-w auto` (tui default) or a number; use `unlimited/0` to disable wrap.
- Missing color: force `--color=on`; disable with `--color=off` for logs/CI.
- Too many drops: raise `-M`; status bar shows dropped count.
