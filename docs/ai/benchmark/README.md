# Benchmark files

Data and scripts for [../log-format-benchmark.md](../log-format-benchmark.md).

- `BenchSim.kt` and `BenchSimLarge.kt`: simulators that write each scenario's log in three formats (S1 to S4, and S5). Copy them into `kmpertrace-runtime/src/jvmTest/kotlin/` to run them. They are not part of the build.
- `tools/`: `ktrace-errors` and `ktrace-show`, prototypes of a filtered `kmpertrace-cli print`, used in the S5 `KF` condition. They need `kmpertrace-cli` and Python 3 on PATH.
- `harness.py`: runs each scenario and condition through headless Claude Code and records tokens, cost and the answer. Needs `BENCH_DIR` and `KMPERTRACE_CLI_BIN`. For the `KF` condition also set `KTRACE_TOOLS_BIN` to the `tools/` folder. Set `RESULTS` to choose the output file.
- `logs/`: the logs that were used (`plain.log`, `plainplus.log`, `kmpertrace.log` for each scenario).
- `results.jsonl` (S1 to S4) and `results_s5.jsonl` (S5): one record per run, including the full answer.
- `grades.json`: blind grades for S1, S4 and S5.
