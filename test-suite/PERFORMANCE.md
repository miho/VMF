# Local performance regression gate

Run from the VMF repository root with the same JDK and on the same machine as the baseline.

```sh
./gradlew performanceBaseline
./gradlew performanceTest
```

On Windows, use `gradlew.bat` in place of `./gradlew`. Capture a baseline on a known-good checkout. The task saves it under `.gradle/vmf-performance/baseline.json`, outside the normal `clean` output. Run `performanceReport` to inspect a candidate without changing or comparing the baseline. Run `check -PwithPerformanceTests` to include the performance gate in a local verification run. An ordinary `test` or `check` skips the benchmark. `build-and-test-all`, which CI runs, compiles the benchmarks (`compilePerformanceBenchmarks`) so that the gate stays runnable; it does not measure.

The gate compiles the checkout's runtime and generates the model with the checkout's generator. Its codec cases compile the checkout's Jackson sources. It launches three sequential JVMs with fixed heap, G1, and active processor settings and rotates the case order. Setup and correctness checks run outside the timer. Close CPU-heavy applications before calibrating or checking a baseline.

Cases cover typed and reflective scalar access, containment reparenting with and without recursive observation, swaps and sorts, opposite references, scalar and list notifications, recursive listener registration, native transaction recording and undo/redo, object graph cloning, verified journal capture on different graph sizes, and replay in memory and through JSON, YAML, and XML.

Each operation is the unit in its case name. For example, a listener lifecycle operation includes registering, editing, and unsubscribing. A sealed session counts all eight frames as one operation. In-memory replay starts from a model checkpoint. The codec cases include loading the serialized checkpoint and journal into a fresh graph with bytes already in memory.

The ordered journal cases vary node count and retained tags independently and exercise one, eight, and 32 frames per sealed session on the large graph. Every frame includes a write followed by restoration of the old value. The report includes median and p99 for the sealed session and for ticks from opening a transaction through verified publication. Tick samples exclude opening and finishing the recorder.

## C# and TypeScript lanes

The tasks above measure the Java runtime. The C# and the TypeScript runtime have a lane each. A lane generates the gate's model for its language and builds a worker program that runs the model cases of the Java lane against the generated code. The coordinator of the Java lane starts the worker, so the protocol, the failure threshold, the baseline and the report are the same for every language.

| | Java | C# | TypeScript |
|---|---|---|---|
| Save a baseline | `performanceBaseline` | `performanceBaselineCSharp` | `performanceBaselineTypeScript` |
| Compare with the baseline | `performanceTest` | `performanceTestCSharp` | `performanceTestTypeScript` |
| Measure only | `performanceReport` | `performanceReportCSharp` | `performanceReportTypeScript` |
| Baseline in `.gradle/vmf-performance` | `baseline.json` | `baseline-csharp.json` | `baseline-typescript.json` |
| Reports in `build/reports/performance` | `<mode>.json` | `csharp/<mode>.json` | `typescript/<mode>.json` |
| Cases in `test-suite/src/performance` | `java/perf/ModelScenarios.java` | `csharp/ModelScenarios.cs` | `typescript/model-scenarios.ts` |

`performanceBaselineAllTargets`, `performanceTestAllTargets` and `performanceReportAllTargets` run the three lanes one after the other. The C# lane needs the .NET 8 SDK, the TypeScript lane Node.js with npm. The C# worker runs with workstation garbage collection and four processors, as the Java lane limits its JVMs; the TypeScript worker runs with Node's defaults. A baseline records the version of the language runtime and is rejected on another one.

A case has the same name, graph and operation count in every lane that has it, so the lanes can be read side by side. The lanes differ where the languages do:

- C# and TypeScript swap two children with two `Move` calls; Java uses `Collections.swap`.
- TypeScript has no sort and no transactions: the lane has no sort case, and its recording case starts a recording, makes two edits and stops it. Its reflective case reads and writes the property by its name.
- The journal cases exist only in Java.
- C# and TypeScript have two more cases. `scalar-set-listener-elsewhere` sets scalars of objects that nobody observes while a listener observes another tree; `containment-append-1024` appends to a growing list. Both runtimes had exactly these costs: a listener anywhere made every setter slower, and appending got slower with the size of the list.

`checkPerformanceTargets` builds both workers and runs every case once with its checks, without measuring. CI runs it, so that these lanes stay runnable as well.

A lane for another language is a worker with the commands `check`, `describe` and `measure` (see `WorkerLane` in `PerformanceGate`), registered with `performanceLane` in `performance.gradle`. When a case of a worker changes what it measures, bump the lane protocol in that worker.

## Observer and verified-frame diagnostics

Run `performanceObserverReport` for selective scalar and containment observation. Run
`performanceFrameReport` to compare native publication with `JournalRecorder.publishFrame()`
over 32 ticks on each of the 13-node and 101-node fixtures. The frame report measures
the edit and publication together for each tick, with the recorder open throughout the
session. The session measurements also include opening and finishing the recorder.

These tasks write separate reports next to the gate report. They measure thread-allocated
bytes per operation on supporting JVMs in a separate pass after the latency samples. The
observer report counts one edit as an operation. The frame report counts one session as
an operation. Both use the same fork settings as the gate.

## Failure threshold

For each case the gate compares the median of the three process medians in nanoseconds per operation. A case fails when the result exceeds the baseline median by more than the largest of these three values.

- 25% of the baseline median.
- Three times the median absolute deviation of the process medians, capped at 50% of the baseline median.
- 2.5 nanoseconds per operation. This floor accounts for JVM variation on operations that take only a few nanoseconds.

A regression exits with a failure and keeps the prior baseline. Missing or incompatible baselines also fail explicitly. Reports under `build/reports/performance` include the per-case limits and raw fork samples. The p95 and p99 are diagnostics for batch-normalized timings. The gate uses the median to reduce sensitivity to isolated safepoints and scheduler pauses.

When changing the meaning of an existing case or changing the runner protocol, bump `PROTOCOL` in `PerformanceGate` and establish a new baseline. Keep the baseline machine-local and recalibrate after changing JDKs or machine settings. Reports are comparable when the workload, runtime setup, and hardware match.
