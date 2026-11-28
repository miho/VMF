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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        if (args.length < 3 || args.length == 4) {
            throw new IllegalArgumentException("Expected mode, baseline path and report path, "
                    + "and for another target language the name of its lane and its worker command");
        }
        String mode = args[0];
        boolean javaLane = args.length == 3;
        if (javaLane && ("worker".equals(mode)
                || (mode.startsWith("worker-") && isDiagnosticReport(mode.substring(7))))) {
            String reportMode = "worker".equals(mode) ? "report" : mode.substring(7);
            worker(reportMode, Integer.parseInt(args[1]), Path.of(args[2]));
            return;
        }
        if (!List.of("baseline", "test", "report").contains(mode) && !(javaLane && isDiagnosticReport(mode))) {
            throw new IllegalArgumentException("Unknown performance mode: " + mode);
        }
        Path baselineFile = Path.of(args[1]).toAbsolutePath();
        Path report = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(baselineFile.getParent());
        // One lock for all lanes: two lanes measuring at the same time would disturb each other.
        try (var channel = FileChannel.open(baselineFile.resolveSibling("run.lock"), CREATE, WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("A local performance run is already active");
            Lane lane = javaLane ? new JavaLane(mode)
                    : new WorkerLane(args[3], Arrays.asList(args).subList(4, args.length), report.getParent());
            run(mode, lane, baselineFile, report);
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

    private static void run(String mode, Lane lane, Path baselineFile, Path reportFile) throws Exception {
        var cases = lane.workloads();
        // Compare the JSON representation used by saved baselines, including numeric node types.
        var environment = JSON.readTree(environment(lane, cases).toString());
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
            validateBaseline(baseline, cases.keySet());
        }

        Path forkDir = reportFile.getParent().resolve(mode + "-forks");
        Files.createDirectories(forkDir);
        var forks = new ArrayList<JsonNode>();
        for (int fork = 0; fork < FORKS; fork++) {
            Path forkReport = forkDir.resolve("fork-" + (fork + 1) + ".json");
            forks.add(lane.measure(fork, forkReport));
        }

        ObjectNode result = JSON.createObjectNode();
        result.set("environment", environment);
        result.put("mode", mode);
        result.put("runtime_location", lane.location());
        var measurements = result.putObject("measurements");
        List<String> failures = new ArrayList<>();
        System.out.printf(Locale.ROOT, "%-32s %12s %12s %12s  %s%n", "Case (ns/op)", "median", "p95", "p99", "gate");
        for (String scenario : cases.keySet()) {
            double[] medians = values(forks, scenario, "median");
            double median = median(medians);
            double high = Arrays.stream(medians).max().orElseThrow();
            double low = Arrays.stream(medians).min().orElseThrow();
            double spread = median(Arrays.stream(medians).map(value -> Math.abs(value - median)).sorted().toArray());
            double p95 = median(values(forks, scenario, "p95"));
            double p99 = median(values(forks, scenario, "p99"));
            var entry = measurements.putObject(scenario);
            entry.put("median", median).put("p95", p95).put("p99", p99);
            if (forks.get(0).path(scenario).has("tick_p99")) {
                entry.put("tick_median", median(values(forks, scenario, "tick_median")));
                entry.put("tick_p99", median(values(forks, scenario, "tick_p99")));
            }
            if (forks.get(0).path(scenario).has("allocated_bytes_per_op")) {
                entry.put("allocated_bytes_per_op", median(values(forks, scenario, "allocated_bytes_per_op")));
            }
            entry.put("fork_median_min", low).put("fork_median_max", high).put("fork_median_mad", spread);
            String gate = "-";
            if (baseline != null) {
                JsonNode previous = baseline.path("measurements").path(scenario);
                double old = previous.path("median").asDouble();
                double previousSpread = previous.path("fork_median_mad").asDouble();
                double calibrated = Math.min(MAX_RELATIVE_NOISE * old, 3 * previousSpread);
                double budget = Math.max(MIN_ABSOLUTE_NOISE_NS, Math.max(MIN_RELATIVE_NOISE * old, calibrated));
                double limit = old + budget;
                boolean pass = median <= limit;
                entry.put("baseline_median", old).put("noise_budget", budget).put("limit", limit).put("passed", pass);
                gate = String.format(Locale.ROOT, "%+6.1f%%, max %.1f %s",
                        100 * (median / old - 1), limit, pass ? "PASS" : "FAIL");
                if (!pass) failures.add(scenario);
            }
            System.out.printf(Locale.ROOT, "%-32s %12.1f %12.1f %12.1f  %s%n",
                    scenario, median, p95, p99, gate);
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

    private static ObjectNode environment(Lane lane, Map<String, Integer> cases) {
        ObjectNode env = JSON.createObjectNode();
        env.put("protocol", PROTOCOL).put("forks", FORKS).put("warmup_ns", WARMUP_NS)
                .put("minimum_measurement_ns", MEASURE_NS).put("minimum_samples", MIN_SAMPLES);
        for (String key : List.of("os.name", "os.arch", "os.version")) env.put(key, System.getProperty(key));
        env.put("local_processors", Runtime.getRuntime().availableProcessors());
        String host = System.getenv("COMPUTERNAME");
        if (host == null) host = System.getenv("HOSTNAME");
        if (host != null) env.put("host", host);
        lane.describe(env);
        var workload = env.putObject("workloads");
        cases.forEach(workload::put);
        return env;
    }

    private static void validateBaseline(JsonNode baseline, Set<String> cases) {
        for (String scenario : cases) {
            var data = baseline.path("measurements").path(scenario);
            double center = data.path("median").asDouble(Double.NaN);
            double spread = data.path("fork_median_mad").asDouble(Double.NaN);
            if (!(center > 0) || !Double.isFinite(center) || !(spread >= 0) || !Double.isFinite(spread)) {
                throw new IllegalStateException("Invalid baseline for " + scenario);
            }
        }
    }

    /** Where the cases of one target language run. Every fork is a fresh process. */
    private interface Lane {
        /** The case names with the operations of one batch, in the order the cases are declared. */
        Map<String, Integer> workloads();

        /** Adds what a baseline and a later run must have in common besides the machine and the protocol. */
        void describe(ObjectNode environment);

        /** Measures every case in a fresh process and returns what {@link #worker} writes. */
        JsonNode measure(int fork, Path output) throws Exception;

        /** The code under test, for the report. */
        String location();
    }

    /** Java: every fork is a JVM that runs this class as a worker. */
    private static final class JavaLane implements Lane {
        private final String mode;
        private final Map<String, Integer> workloads = new LinkedHashMap<>();

        JavaLane(String mode) {
            this.mode = mode;
            for (Scenario scenario : scenarios(mode)) workloads.put(scenario.name, scenario.operations);
        }

        @Override
        public Map<String, Integer> workloads() {
            return workloads;
        }

        @Override
        public void describe(ObjectNode environment) {
            for (String key : List.of("java.runtime.version", "java.vm.name", "java.vendor")) {
                environment.put(key, System.getProperty(key));
            }
            environment.set("jvm_options", JSON.valueToTree(JVM_OPTIONS));
        }

        @Override
        public JsonNode measure(int fork, Path output) throws Exception {
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
            command.addAll(JVM_OPTIONS);
            Collections.addAll(command, "-cp", System.getProperty("java.class.path"), PerformanceGate.class.getName(),
                    isDiagnosticReport(mode) ? "worker-" + mode : "worker", Integer.toString(fork), output.toString());
            execute(command, "Performance JVM", "fork " + (fork + 1));
            return JSON.readTree(output.toFile());
        }

        @Override
        public String location() {
            return eu.mihosoft.vmf.runtime.core.VObject.class.getProtectionDomain()
                    .getCodeSource().getLocation().toString();
        }
    }

    /**
     * Another target language: a program that runs the cases against the code generated for that language and
     * follows the protocol of {@link #worker}. It is called as
     * <pre>
     * describe &lt;file&gt;
     * measure fork=&lt;n&gt; forks=&lt;n&gt; warmup_ns=&lt;n&gt; measure_ns=&lt;n&gt; min_samples=&lt;n&gt; max_samples=&lt;n&gt; output=&lt;file&gt;
     * </pre>
     * {@code describe} writes a JSON object with the member {@code workloads} (case name to operations per batch) and
     * whatever identifies the language runtime and its settings. {@code measure} writes, for every case, the
     * members {@code median}, {@code p95}, {@code p99} and {@code samples_ns_per_op}.
     */
    private static final class WorkerLane implements Lane {
        private final String name;
        private final List<String> command;
        private final ObjectNode description;
        private final Map<String, Integer> workloads = new LinkedHashMap<>();

        WorkerLane(String name, List<String> command, Path directory) throws Exception {
            this.name = name;
            this.command = List.copyOf(command);
            Files.createDirectories(directory);
            Path file = directory.resolve("lane.json");
            execute(call("describe", file.toString()), "Performance worker for " + name, "its description");
            JsonNode described = JSON.readTree(file.toFile());
            if (!described.isObject() || !described.path("workloads").isObject() || described.path("workloads").isEmpty()) {
                throw new IllegalStateException("The performance worker for " + name + " describes no workloads");
            }
            description = (ObjectNode) described;
            JsonNode declared = description.remove("workloads");
            for (var fields = declared.fields(); fields.hasNext(); ) {
                var workload = fields.next();
                if (!workload.getValue().isInt() || workload.getValue().asInt() <= 0) {
                    throw new IllegalStateException("Positive operation count required for " + workload.getKey());
                }
                workloads.put(workload.getKey(), workload.getValue().asInt());
            }
        }

        @Override
        public Map<String, Integer> workloads() {
            return workloads;
        }

        @Override
        public void describe(ObjectNode environment) {
            environment.put("lane", name);
            environment.set("worker", description);
        }

        @Override
        public JsonNode measure(int fork, Path output) throws Exception {
            execute(call("measure", "fork=" + fork, "forks=" + FORKS, "warmup_ns=" + WARMUP_NS,
                    "measure_ns=" + MEASURE_NS, "min_samples=" + MIN_SAMPLES, "max_samples=" + MAX_SAMPLES,
                    "output=" + output), "Performance worker for " + name, "fork " + (fork + 1));
            return JSON.readTree(output.toFile());
        }

        @Override
        public String location() {
            return String.join(" ", command);
        }

        private List<String> call(String... arguments) {
            var call = new ArrayList<>(command);
            Collections.addAll(call, arguments);
            return call;
        }
    }

    private static void execute(List<String> command, String process, String step) throws Exception {
        var worker = new ProcessBuilder(command).inheritIO().start();
        try {
            if (!worker.waitFor(FORK_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                throw new IllegalStateException(process + " timed out in " + step);
            }
            if (worker.exitValue() != 0) {
                throw new IllegalStateException(process + " failed in " + step);
            }
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
