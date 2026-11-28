// Worker of the TypeScript lane of VMF's performance gate, see test-suite/PERFORMANCE.md. The coordinator
// (perf.PerformanceGate) calls "describe" once and "measure" in a fresh process for every fork, and passes the
// protocol, so that every language measures the same way. "check" runs and verifies every case once.
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { cases } from "./build/js/model-scenarios.js";

// Bump this when a case of this lane changes what it measures.
const LANE_PROTOCOL = 1;

const [command, ...rest] = process.argv.slice(2);
if (command === "check" && rest.length === 0) {
    for (const scenario of cases) {
        qualify(scenario);
        console.log(`passed ${scenario.name}`);
    }
} else if (command === "describe" && rest.length === 1) {
    write(rest[0], {
        lane_protocol: LANE_PROTOCOL,
        runtime: `Node ${process.version}`,
        v8: process.versions.v8,
        workloads: Object.fromEntries(cases.map(scenario => [scenario.name, scenario.operations])),
    });
} else if (command === "measure") {
    measure(protocol(rest));
} else {
    throw new Error("Expected check, describe <file> or measure fork=<n> forks=<n> warmup_ns=<n> "
        + "measure_ns=<n> min_samples=<n> max_samples=<n> output=<file>.");
}

function measure({ fork, forks, warmupNanos, measureNanos, minSamples, maxSamples, output }) {
    const offset = Math.floor(fork * cases.length / forks);
    const results = {};
    console.log(`Performance fork ${fork + 1}/${forks}, PID ${process.pid}`);
    for (const scenario of [...cases.slice(offset), ...cases.slice(0, offset)]) {
        qualify(scenario);

        let warmup = 0;
        do {
            warmup += sample(scenario);
        } while (warmup < warmupNanos);

        const samples = [];
        let measured = 0;
        do {
            const nanos = sample(scenario);
            samples.push(nanos / scenario.operations);
            measured += nanos;
        } while (samples.length < maxSamples && (samples.length < minSamples || measured < measureNanos));

        const sorted = [...samples].sort((a, b) => a - b);
        results[scenario.name] = {
            median: median(sorted),
            p95: percentile(sorted, .95),
            p99: percentile(sorted, .99),
            samples_ns_per_op: samples,
        };
    }
    write(output, results);
}

function qualify(scenario) {
    const batch = scenario.setup();
    batch.run(scenario.operations);
    batch.verify(scenario.operations);
}

// Setup and verification stay outside the timer.
function sample(scenario) {
    const batch = scenario.setup();
    const start = process.hrtime.bigint();
    batch.run(scenario.operations);
    const elapsed = Number(process.hrtime.bigint() - start);
    batch.verify(scenario.operations);
    return elapsed;
}

function median(sorted) {
    const upper = sorted[Math.floor(sorted.length / 2)];
    return sorted.length % 2 === 0 ? (upper + sorted[sorted.length / 2 - 1]) / 2 : upper;
}

function percentile(sorted, proportion) {
    return sorted[Math.ceil(sorted.length * proportion) - 1];
}

function protocol(parameters) {
    const values = new Map();
    for (const parameter of parameters) {
        const separator = parameter.indexOf("=");
        if (separator <= 0) throw new Error(`Expected name=value, got '${parameter}'.`);
        values.set(parameter.slice(0, separator), parameter.slice(separator + 1));
    }
    const value = name => {
        if (!values.has(name)) throw new Error(`Missing ${name}=<value>.`);
        return values.get(name);
    };
    const count = name => {
        const number = Number(value(name));
        if (!Number.isSafeInteger(number) || number < 0) throw new Error(`${name} must be a whole number.`);
        return number;
    };
    const parsed = {
        fork: count("fork"), forks: count("forks"), warmupNanos: count("warmup_ns"),
        measureNanos: count("measure_ns"), minSamples: count("min_samples"), maxSamples: count("max_samples"),
        output: value("output"),
    };
    if (parsed.forks === 0 || parsed.fork >= parsed.forks || parsed.minSamples === 0
            || parsed.maxSamples < parsed.minSamples) {
        throw new Error("Inconsistent measurement protocol.");
    }
    return parsed;
}

function write(file, content) {
    const path = resolve(file);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, JSON.stringify(content, null, 2));
}
