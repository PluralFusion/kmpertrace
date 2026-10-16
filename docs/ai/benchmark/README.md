# Benchmark files

Data and scripts for [../log-format-benchmark.md](../log-format-benchmark.md).

- `BenchSim.kt`: simulator that writes each scenario's log in three formats. Copy it into `kmpertrace-runtime/src/jvmTest/kotlin/` to run it. It is not part of the build.
- `harness.py`: runs each scenario and condition through headless Claude Code and records tokens, cost and the answer. Needs `BENCH_DIR` and `KMPERTRACE_CLI_BIN`.
- `logs/`: the logs that were used (`plain.log`, `plainplus.log`, `kmpertrace.log` for each scenario).
- `results.jsonl`: one record per run, including the full answer.
- `grades.json`: blind grades for S1 and S4.
