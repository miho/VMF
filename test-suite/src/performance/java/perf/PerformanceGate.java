/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://dx.doi.org/10.1007/s00791-014-0230-y
 */
package perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public final class PerformanceGate {
    private static final ObjectMapper JSON = new ObjectMapper();
    // Bump this when changing workload or measurement semantics.
    private static final int PROTOCOL = 3;
    private static final int FORKS = 3;
    private static final int MIN_SAMPLES = 128;
    private static final int MAX_SAMPLES = 4096;
    private static final int ALLOCATION_SAMPLES = 32;
    private static final long WARMUP_NS = TimeUnit.MILLISECONDS.toNanos(900);
    private static final long MEASURE_NS = TimeUnit.MILLISECONDS.toNanos(275);
    private static final long FORK_TIMEOUT_MINUTES = 5;
    private static final double MIN_RELATIVE_NOISE = 0.25;
    private static final double MAX_RELATIVE_NOISE = 0.50;
    private static final double MIN_ABSOLUTE_NOISE_NS = 2.5;
    private static final String OBSERVER_REPORT = "observer-report";
    private static final String FRAME_REPORT = "frame-report";
    private static final List<String> JVM_OPTIONS = List.of("-Xms256m", "-Xmx256m", "-XX:+UseG1GC",
            "-XX:ActiveProcessorCount=4");

    private PerformanceGate() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected mode, baseline path and report path");
        String mode = args[0];
        if ("worker".equals(mode) || (mode.startsWith("worker-") && isDiagnosticReport(mode.substring(7)))) {
            String reportMode = "worker".equals(mode) ? "report" : mode.substring(7);
            worker(reportMode, Integer.parseInt(args[1]), Path.of(args[2]));
            return;
        }
        if (!List.of("baseline", "test", "report").contains(mode) && !isDiagnosticReport(mode)) {
            throw new IllegalArgumentException("Unknown performance mode: " + mode);
        }
        Path baselineFile = Path.of(args[1]).toAbsolutePath();
        Path report = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(baselineFile.getParent());
        try (var channel = FileChannel.open(baselineFile.resolveSibling("run.lock"), CREATE, WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("A local performance run is already active");
            run(mode, baselineFile, report);
        }
    }

    private static boolean isDiagnosticReport(String mode) {
        return OBSERVER_REPORT.equals(mode) || FRAME_REPORT.equals(mode);
    }

    private static List<Scenario> scenarios(String mode) {
        List<Scenario> cases = new ArrayList<>();
        switch (mode) {
            case OBSERVER_REPORT:
                cases.addAll(ModelScenarios.observerCases());
                break;
            case FRAME_REPORT:
                cases.addAll(JournalScenarios.verifiedFrameCases());
                break;
            default:
                cases.addAll(ModelScenarios.cases());
                cases.addAll(JournalScenarios.cases());
        }
        Set<String> names = new HashSet<>();
        for (Scenario scenario : cases) {
            if (!names.add(scenario.name)) {
                throw new IllegalStateException("Duplicate performance case: " + scenario.name);
            }
        }
        return cases;
    }

    private static void run(String mode, Path baselineFile, Path reportFile) throws Exception {
        var cases = scenarios(mode);
        // Compare the JSON representation used by saved baselines, including numeric node types.
        var environment = JSON.readTree(environment(cases).toString());
        JsonNode baseline = null;
        if ("test".equals(mode)) {
            if (!Files.isRegularFile(baselineFile)) {
                throw new IllegalStateException("No local performance baseline. Run performanceBaseline on a known-good checkout.");
            }
            baseline = JSON.readTree(baselineFile.toFile());
            if (!environment.equals(baseline.path("environment"))) {
                throw new IllegalStateException("Baseline uses a different runtime, machine or benchmark protocol. "
                        + "Run performanceBaseline again on a known-good checkout.");
            }
            validateBaseline(baseline, cases);
        }

        Path forkDir = reportFile.getParent().resolve(mode + "-forks");
        Files.createDirectories(forkDir);
        var forks = new ArrayList<JsonNode>();
        for (int fork = 0; fork < FORKS; fork++) {
            Path forkReport = forkDir.resolve("fork-" + (fork + 1) + ".json");
            forks.add(launchFork(mode, fork, forkReport));
        }

        ObjectNode result = JSON.createObjectNode();
        result.set("environment", environment);
        result.put("mode", mode);
        result.put("runtime_location", eu.mihosoft.vmf.runtime.core.VObject.class.getProtectionDomain()
                .getCodeSource().getLocation().toString());
        var measurements = result.putObject("measurements");
        List<String> failures = new ArrayList<>();
        System.out.printf(Locale.ROOT, "%-32s %12s %12s %12s  %s%n", "Case (ns/op)", "median", "p95", "p99", "gate");
        for (Scenario scenario : cases) {
            double[] medians = values(forks, scenario.name, "median");
            double median = median(medians);
            double high = Arrays.stream(medians).max().orElseThrow();
            double low = Arrays.stream(medians).min().orElseThrow();
            double spread = median(Arrays.stream(medians).map(value -> Math.abs(value - median)).sorted().toArray());
            double p95 = median(values(forks, scenario.name, "p95"));
            double p99 = median(values(forks, scenario.name, "p99"));
            var entry = measurements.putObject(scenario.name);
            entry.put("median", median).put("p95", p95).put("p99", p99);
            if (forks.get(0).path(scenario.name).has("tick_p99")) {
                entry.put("tick_median", median(values(forks, scenario.name, "tick_median")));
                entry.put("tick_p99", median(values(forks, scenario.name, "tick_p99")));
            }
            if (forks.get(0).path(scenario.name).has("allocated_bytes_per_op")) {
                entry.put("allocated_bytes_per_op", median(values(forks, scenario.name, "allocated_bytes_per_op")));
            }
            entry.put("fork_median_min", low).put("fork_median_max", high).put("fork_median_mad", spread);
            String gate = "-";
            if (baseline != null) {
                JsonNode previous = baseline.path("measurements").path(scenario.name);
                double old = previous.path("median").asDouble();
                double previousSpread = previous.path("fork_median_mad").asDouble();
                double calibrated = Math.min(MAX_RELATIVE_NOISE * old, 3 * previousSpread);
                double budget = Math.max(MIN_ABSOLUTE_NOISE_NS, Math.max(MIN_RELATIVE_NOISE * old, calibrated));
                double limit = old + budget;
                boolean pass = median <= limit;
                entry.put("baseline_median", old).put("noise_budget", budget).put("limit", limit).put("passed", pass);
                gate = String.format(Locale.ROOT, "%+6.1f%%, max %.1f %s",
                        100 * (median / old - 1), limit, pass ? "PASS" : "FAIL");
                if (!pass) failures.add(scenario.name);
            }
            System.out.printf(Locale.ROOT, "%-32s %12.1f %12.1f %12.1f  %s%n",
                    scenario.name, median, p95, p99, gate);
        }
        result.set("failed_cases", JSON.valueToTree(failures));
        writeJson(reportFile, result);
        System.out.println("Report: " + reportFile);
        if ("baseline".equals(mode)) {
            writeJson(baselineFile, result);
            System.out.println("Baseline saved: " + baselineFile);
        }
        if (!failures.isEmpty()) throw new IllegalStateException("Performance regressions: " + String.join(", ", failures));
    }

    private static ObjectNode environment(List<Scenario> cases) {
        ObjectNode env = JSON.createObjectNode();
        env.put("protocol", PROTOCOL).put("forks", FORKS).put("warmup_ns", WARMUP_NS)
                .put("minimum_measurement_ns", MEASURE_NS).put("minimum_samples", MIN_SAMPLES);
        for (String key : List.of("java.runtime.version", "java.vm.name", "java.vendor", "os.name", "os.arch", "os.version")) {
            env.put(key, System.getProperty(key));
        }
        env.put("local_processors", Runtime.getRuntime().availableProcessors());
        String host = System.getenv("COMPUTERNAME");
        if (host == null) host = System.getenv("HOSTNAME");
        if (host != null) env.put("host", host);
        env.set("jvm_options", JSON.valueToTree(JVM_OPTIONS));
        var workload = env.putObject("workloads");
        for (Scenario scenario : cases) workload.put(scenario.name, scenario.operations);
        return env;
    }

    private static void validateBaseline(JsonNode baseline, List<Scenario> cases) {
        for (Scenario scenario : cases) {
            var data = baseline.path("measurements").path(scenario.name);
            double center = data.path("median").asDouble(Double.NaN);
            double spread = data.path("fork_median_mad").asDouble(Double.NaN);
            if (!(center > 0) || !Double.isFinite(center) || !(spread >= 0) || !Double.isFinite(spread)) {
                throw new IllegalStateException("Invalid baseline for " + scenario.name);
            }
        }
    }

    private static JsonNode launchFork(String mode, int fork, Path output) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.addAll(JVM_OPTIONS);
        Collections.addAll(command, "-cp", System.getProperty("java.class.path"), PerformanceGate.class.getName(),
                isDiagnosticReport(mode) ? "worker-" + mode : "worker", Integer.toString(fork), output.toString());
        var worker = new ProcessBuilder(command).inheritIO().start();
        try {
            if (!worker.waitFor(FORK_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                throw new IllegalStateException("Performance JVM timed out in fork " + (fork + 1));
            }
            if (worker.exitValue() != 0) {
                throw new IllegalStateException("Performance JVM failed in fork " + (fork + 1));
            }
            return JSON.readTree(output.toFile());
        } finally {
            if (worker.isAlive()) worker.destroyForcibly();
        }
    }

    private static void worker(String mode, int fork, Path output) throws Exception {
        var cases = scenarios(mode);
        ThreadMXBean allocationBean = isDiagnosticReport(mode) ? allocationBean() : null;
        Collections.rotate(cases, -(fork * cases.size() / FORKS));
        ObjectNode result = JSON.createObjectNode();
        System.out.printf(Locale.ROOT, "Performance fork %d/%d, PID %d%n",
                fork + 1, FORKS, ProcessHandle.current().pid());
        for (Scenario scenario : cases) {
            var check = scenario.setup.get();
            check.run(scenario.operations);
            check.verify(scenario.operations);

            long warmup = 0L;
            do {
                warmup += sample(scenario, null);
            } while (warmup < WARMUP_NS);

            var raw = new ArrayList<Double>();
            var ticks = new ArrayList<Long>();
            long measured = 0L;
            do {
                long time = sample(scenario, ticks);
                raw.add((double) time / scenario.operations);
                measured += time;
            } while (raw.size() < MAX_SAMPLES && (raw.size() < MIN_SAMPLES || measured < MEASURE_NS));
            double[] samples = raw.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            var metrics = result.putObject(scenario.name);
            metrics.put("median", median(samples)).put("p95", percentile(samples, 0.95))
                    .put("p99", percentile(samples, 0.99));
            metrics.set("samples_ns_per_op", JSON.valueToTree(raw));
            if (!ticks.isEmpty()) {
                double[] sortedTicks = ticks.stream().mapToDouble(Long::doubleValue).sorted().toArray();
                metrics.put("tick_median", median(sortedTicks)).put("tick_p99", percentile(sortedTicks, 0.99));
                metrics.set("tick_samples_ns", JSON.valueToTree(ticks));
            }
            if (allocationBean != null) {
                metrics.put("allocated_bytes_per_op", allocatedBytesPerOperation(scenario, allocationBean));
            }
        }
        writeJson(output, result);
    }

    private static long sample(Scenario scenario, List<Long> ticks) {
        var batch = scenario.setup.get();
        long start = System.nanoTime();
        batch.run(scenario.operations);
        long elapsed = System.nanoTime() - start;
        batch.verify(scenario.operations);
        if (ticks != null) {
            long[] frameTimes = batch.tickNanos();
            if (frameTimes != null) {
                for (long frameTime : frameTimes) ticks.add(frameTime);
            }
        }
        return elapsed;
    }

    private static double[] values(List<JsonNode> forks, String scenario, String metric) {
        return forks.stream().mapToDouble(fork -> {
            double value = fork.path(scenario).path(metric).asDouble(Double.NaN);
            if (!Double.isFinite(value) || value < 0 || (value == 0 && !"allocated_bytes_per_op".equals(metric))) {
                throw new IllegalStateException("Invalid sample data for " + scenario);
            }
            return value;
        }).sorted().toArray();
    }

    private static ThreadMXBean allocationBean() {
        var platformBean = ManagementFactory.getThreadMXBean();
        if (!(platformBean instanceof ThreadMXBean)) return null;
        ThreadMXBean bean = (ThreadMXBean) platformBean;
        if (!bean.isThreadAllocatedMemorySupported()) return null;
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }

    private static double allocatedBytesPerOperation(Scenario scenario, ThreadMXBean bean) {
        double[] samples = new double[ALLOCATION_SAMPLES];
        long thread = Thread.currentThread().getId();
        for (int i = 0; i < samples.length; i++) {
            var batch = scenario.setup.get();
            long before = bean.getThreadAllocatedBytes(thread);
            batch.run(scenario.operations);
            long bytes = bean.getThreadAllocatedBytes(thread) - before;
            batch.verify(scenario.operations);
            samples[i] = (double) bytes / scenario.operations;
        }
        Arrays.sort(samples);
        return median(samples);
    }

    private static double median(double[] sorted) {
        double upper = sorted[sorted.length / 2];
        return sorted.length % 2 == 0 ? (upper + sorted[sorted.length / 2 - 1]) / 2 : upper;
    }

    private static double percentile(double[] sorted, double proportion) {
        return sorted[(int) Math.ceil(sorted.length * proportion) - 1];
    }

    private static void writeJson(Path file, ObjectNode content) throws Exception {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "performance-", ".json");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), content);
            try {
                Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
