# Do KmperTrace logs help an AI assistant debug? A small measurement

Run on 2026-10-16 against KmperTrace 0.3.3. This is a small test (42 runs). Read the limits section before you rely on any number.

## Summary

- **Accuracy: no advantage found.** On two scenarios built to favor KmperTrace, the model found the root cause in every run with plain logs, including plain logs with no correlation IDs. It was less reliable when it read the KmperTrace output: 1 of 3 correct on one scenario and 1.5 of 3 on the other, using the `kmpertrace-cli` tree. With three runs per cell, this shows no improvement and a possible weakness, not a proven one.
- **Tokens: depends on how the log is read.** The same run written as raw KmperTrace lines is about 3 times larger than the plain log, and it cost the model 2.4 to 4.6 times more tokens above baseline to read. The same log rendered by `kmpertrace-cli print` was smaller than the plain log and cost about the same or less (10% to 53% fewer tokens above baseline).
- **What this supports.** Tell assistants to read KmperTrace logs through `kmpertrace-cli print`, never as raw lines. It does not support the claim that KmperTrace improves an assistant's debugging results. That claim needs a harder test.

## Question

Does an AI coding assistant debug a problem more accurately, or with fewer tokens, when the log was written by KmperTrace than when it is a typical plain log?

## Method

- **Same run, three log formats.** A simulator ran each scenario once and wrote the log in each format at the same moment, so the content matches line for line. Plain logs have a timestamp, thread, level, component and message, plus "start" and "done in N ms" lines for each step. The simulator is `benchmark/BenchSim.kt`.
- **Conditions.**
  - `P`: plain log.
  - `PP`: plain log where the developer added `[attempt=N]` by hand to every line of the pairing flow. This is the fair baseline for what KmperTrace automates. Only scenarios S1 and S4 have it. S2 and S3 already carry the IDs in their messages.
  - `K`: KmperTrace log, read as raw lines.
  - `KC`: the same KmperTrace log, with the prompt saying that `kmpertrace-cli` is available and how to call it. I checked the tool calls of three extra runs: the model ran `kmpertrace-cli print --file app.log --span-attrs on --color off`.
- **Model and tools.** Each run is a separate headless Claude Code process (`claude -p`) using the `sonnet` alias, which resolved to `claude-sonnet-5`. Tools: Read, Bash, Grep, Glob. No source code. Project settings, MCP servers and slash commands were switched off. The model got the same prompt for each scenario: a short component description, the symptom, and a request for the root cause, the component and the evidence in at most 6 sentences. Harness: `benchmark/harness.py`.
- **Trials.** 3 per cell, 42 runs in total.
- **Grading.** S1 and S4 answers were shuffled and graded against a written rubric without condition labels, before I matched them to conditions. S2 and S3 were graded by keyword rules for a clear single answer. Grades: `benchmark/grades.json`. Raw results with all answers: `benchmark/results.jsonl`. The logs: `benchmark/logs/`.

## Scenarios

| ID | Setup | Correct answer |
|---|---|---|
| S1 | The user taps Pair twice (AA:11, then BB:22). A callback from the vendor SDK arrives on its own thread. | The late CONNECTED callback belongs to the first attempt, and the handler reads a shared "current mac" field that the second attempt overwrote, so the app reports BB:22 as connected. |
| S2 | Six file uploads in parallel, with a retry after one 503. | Upload f4 failed because the retry path skipped chunk 2 and finalize reported it missing. |
| S3 | A profile screen loads three things in parallel and takes 3.3 s. | The contacts repository loads 25 avatars one at a time (about 120 ms each). |
| S4 | The user taps Pair on three devices within a second. Two connect, one is switched off. | The first CONNECTED belongs to AA:11, not CC:33. The shared field was overwritten, so both are reported as CC:33. |

S1 and S4 are the cases KmperTrace is designed for: callbacks from another thread attached to the right trace through a trace snapshot. S2 and S3 are controls where plain logs with IDs in the messages work well.

## Results

Accuracy (sum of scores out of 3 per cell):

| Scenario | P | PP | K (raw) | KC (CLI) |
|---|---|---|---|---|
| S1 late callback | 3 | 3 | 3 | 1 |
| S2 parallel uploads | 3 | not run | 3 | 3 |
| S3 slow screen | 3 | not run | 3 | 3 |
| S4 three attempts | 3 | 3 | 2 | 1.5 |

Tokens per run above baseline (mean). The baseline is 33,554 tokens, from a run with a trivial task. It covers the system prompt and tool definitions that every run pays for. Total tokens = input + cache + output.

| Scenario | P | PP | K (raw) | KC (CLI) |
|---|---|---|---|---|
| S1 | 6,086 | 5,858 | 14,383 | 4,087 |
| S2 | 12,744 | not run | 58,438 | 10,808 |
| S3 | 6,380 | not run | 18,586 | 2,985 |
| S4 | 6,642 | 6,887 | 16,497 | 5,988 |

Mean cost per run (USD, list price): P 0.065, PP 0.067, K 0.111, KC 0.059. The 42 runs cost about 3.23 USD in total. Turns were 2 for almost every run. The exceptions were S2 with K (4.3, the model read the large raw file in several pieces) and S2 with P and KC (2.3).

File sizes, to explain the token gap:

| Scenario | Plain log | KmperTrace log | `kmpertrace-cli print` output |
|---|---|---|---|
| S1 | 6.7 KB | 21.4 KB | 3.4 KB |
| S2 | 10.0 KB | 32.7 KB | 5.6 KB |
| S3 | 10.1 KB | 32.3 KB | 4.0 KB |
| S4 | 7.7 KB | 24.7 KB | 3.9 KB |

## What went wrong in the KmperTrace answers

- Four of the six `KC` answers on S1 and S4 were wrong or partly wrong. Three of them reversed the attribution: they said the callback that belongs to BB:22 (S1) or CC:33 (S4) had been placed under the AA:11 span, which is the opposite of what happened. A fourth blamed an overwritten pointer inside the SDK, which the log does not show. One raw `K` answer on S4 said the SDK connected only CC:33, when it connected AA:11 and CC:33.
- In the tree, the late callback's lines sit inside the first attempt's span, next to a message that names the other device. That layout may be easy to misread. I did not test why the answers went wrong, so this is a guess.
- The plain-log answers used timing and the symptom text to reach the right answer. The symptom text in the prompt names the devices and the order the user tapped them, which gave the model a strong hint in every condition.

## Limits

- Three runs per cell, one model, one date. A difference of one run in three is not evidence of anything.
- The prompts contain a symptom with names and order. A real bug report is vaguer, and that could favor structured logs. I did not test it.
- The logs are short (67 to 103 lines). Plain logs may stop working at thousands of lines from many flows. Not tested.
- The simulator is mine. I picked the scenarios, the plain-log style and the rubric. The plain logs are better than many real ones: they have component names, thread names and start and end lines.
- No source code was given. A model with the source could find some of these bugs by reading it.
- The grader (me) knew the correct answers. The shuffle removed the condition labels but not my knowledge of the rubric.
- Token numbers include a fixed 33.5k baseline and some cache effects, so the "above baseline" figures are approximate.

## What this means for the docs

- **Supported:** read KmperTrace logs with `kmpertrace-cli print`, not as raw text. The raw format cost 2.4 to 4.6 times more tokens above baseline than a plain log in this test. The assistant guide should say so in its paste block.
- **Not supported yet:** that KmperTrace improves an assistant's accuracy. Do not claim it in the README, the guide or `llms.txt` until a harder test shows it.
- **Worth building:** the machine-readable output and `summarize` command in `docs/design/agent-cli.md`. The trees are the compact form, and a JSON form could avoid the misreadings seen here.

## Next tests

1. Remove the device names and order from the symptom, and use a vague report.
2. Use logs of 1,000 to 5,000 lines from many concurrent flows.
3. Add the source code to the prompt.
4. Run 10 trials per cell and a second model.
5. Add the JSON output from the design, if it gets built, as a fifth condition.

## Reproduce

Copy `benchmark/BenchSim.kt` into the runtime's `jvmTest` source folder, run it with `BENCH_DIR` set to an empty directory, then run `BENCH_DIR=... KMPERTRACE_CLI_BIN=... python3 benchmark/harness.py 3 3`. The arguments are the number of trials and the number of parallel workers. The harness spends model tokens on your account.
