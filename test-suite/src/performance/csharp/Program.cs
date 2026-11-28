// Worker of the C# lane of VMF's performance gate, see test-suite/PERFORMANCE.md. The coordinator
// (perf.PerformanceGate) calls "describe" once and "measure" in a fresh process for every fork, and passes the
// protocol, so that every language measures the same way. "check" runs and verifies every case once.
using System.Diagnostics;
using System.Globalization;
using System.Runtime;
using System.Runtime.InteropServices;
using System.Text.Json;
using Vmf.Performance;

// Bump this when a case of this lane changes what it measures.
const int LaneProtocol = 1;

var cases = ModelScenarios.Cases();
switch (args.Length == 0 ? "" : args[0])
{
    case "check" when args.Length == 1:
        foreach (var scenario in cases)
        {
            Qualify(scenario);
            Console.WriteLine($"passed {scenario.Name}");
        }
        break;
    case "describe" when args.Length == 2:
        Write(args[1], json =>
        {
            json.WriteNumber("lane_protocol", LaneProtocol);
            json.WriteString("runtime", RuntimeInformation.FrameworkDescription);
            json.WriteString("runtime_identifier", RuntimeInformation.RuntimeIdentifier);
            json.WriteBoolean("server_gc", GCSettings.IsServerGC);
            json.WriteNumber("processors", Environment.ProcessorCount);
            json.WriteStartObject("workloads");
            foreach (var scenario in cases) json.WriteNumber(scenario.Name, scenario.Operations);
            json.WriteEndObject();
        });
        break;
    case "measure":
        Measure(cases, Protocol.Parse(args.Skip(1)));
        break;
    default:
        throw new ArgumentException("Expected check, describe <file> or measure fork=<n> forks=<n> warmup_ns=<n> "
            + "measure_ns=<n> min_samples=<n> max_samples=<n> output=<file>.");
}

static void Measure(IReadOnlyList<Scenario> cases, Protocol protocol)
{
    int offset = protocol.Fork * cases.Count / protocol.Forks;
    var results = new List<(string Name, double[] Sorted, List<double> Samples)>();
    Console.WriteLine($"Performance fork {protocol.Fork + 1}/{protocol.Forks}, PID {Environment.ProcessId}");
    foreach (var scenario in cases.Skip(offset).Concat(cases.Take(offset)))
    {
        Qualify(scenario);

        double warmup = 0;
        do { warmup += Sample(scenario); } while (warmup < protocol.WarmupNanos);

        var samples = new List<double>(protocol.MinSamples);
        double measured = 0;
        do
        {
            double nanos = Sample(scenario);
            samples.Add(nanos / scenario.Operations);
            measured += nanos;
        } while (samples.Count < protocol.MaxSamples
                 && (samples.Count < protocol.MinSamples || measured < protocol.MeasureNanos));
        results.Add((scenario.Name, samples.Order().ToArray(), samples));
    }

    Write(protocol.Output, json =>
    {
        foreach (var (name, sorted, samples) in results)
        {
            json.WriteStartObject(name);
            json.WriteNumber("median", Median(sorted));
            json.WriteNumber("p95", Percentile(sorted, .95));
            json.WriteNumber("p99", Percentile(sorted, .99));
            json.WriteStartArray("samples_ns_per_op");
            foreach (double sample in samples) json.WriteNumberValue(sample);
            json.WriteEndArray();
            json.WriteEndObject();
        }
    });
}

static void Qualify(Scenario scenario)
{
    var batch = scenario.Setup();
    batch.Run(scenario.Operations);
    batch.Verify(scenario.Operations);
}

// Setup and verification stay outside the timer.
static double Sample(Scenario scenario)
{
    var batch = scenario.Setup();
    long start = Stopwatch.GetTimestamp();
    batch.Run(scenario.Operations);
    long elapsed = Stopwatch.GetTimestamp() - start;
    batch.Verify(scenario.Operations);
    return elapsed * (1_000_000_000.0 / Stopwatch.Frequency);
}

static double Median(double[] sorted)
{
    double upper = sorted[sorted.Length / 2];
    return sorted.Length % 2 == 0 ? (upper + sorted[sorted.Length / 2 - 1]) / 2 : upper;
}

static double Percentile(double[] sorted, double proportion) =>
    sorted[(int)Math.Ceiling(sorted.Length * proportion) - 1];

static void Write(string file, Action<Utf8JsonWriter> content)
{
    string path = Path.GetFullPath(file);
    Directory.CreateDirectory(Path.GetDirectoryName(path)!);
    using var stream = File.Create(path);
    using var json = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented = true });
    json.WriteStartObject();
    content(json);
    json.WriteEndObject();
}

namespace Vmf.Performance
{
    /// <summary>One case: <c>Setup</c> builds a fresh model graph for a batch of <c>Operations</c> operations.</summary>
    sealed record Scenario(string Name, int Operations, Func<Batch> Setup);

    /// <summary><c>Run</c> is timed; <c>Verify</c> checks the final state and the delivered events afterwards.</summary>
    sealed record Batch(Action<int> Run, Action<int> Verify);

    sealed record Protocol(int Fork, int Forks, double WarmupNanos, double MeasureNanos, int MinSamples,
        int MaxSamples, string Output)
    {
        public static Protocol Parse(IEnumerable<string> arguments)
        {
            var values = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (string argument in arguments)
            {
                int separator = argument.IndexOf('=');
                if (separator <= 0) throw new ArgumentException($"Expected name=value, got '{argument}'.");
                values[argument[..separator]] = argument[(separator + 1)..];
            }

            string Value(string name) => values.TryGetValue(name, out var value)
                ? value
                : throw new ArgumentException($"Missing {name}=<value>.");
            int Count(string name) => int.Parse(Value(name), CultureInfo.InvariantCulture);
            double Nanos(string name) => long.Parse(Value(name), CultureInfo.InvariantCulture);

            var protocol = new Protocol(Count("fork"), Count("forks"), Nanos("warmup_ns"), Nanos("measure_ns"),
                Count("min_samples"), Count("max_samples"), Value("output"));
            if (protocol.Forks <= 0 || protocol.Fork < 0 || protocol.Fork >= protocol.Forks
                || protocol.MinSamples <= 0 || protocol.MaxSamples < protocol.MinSamples)
            {
                throw new ArgumentException("Inconsistent measurement protocol.");
            }
            return protocol;
        }
    }
}
