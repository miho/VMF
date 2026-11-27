using System.Text.Json;
using System.Text.Json.Nodes;
using System.Xml;
using Eu.Mihosoft.Vmftest.Complex.Devcom;
using Eu.Mihosoft.Vmftest.Complex.Devcom.Host;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockDevComTests
{
    private static readonly JournalBinding Binding = new("stock-devcom", 1, value => value switch
    {
        DevComCheckpoint => "checkpoint",
        PortConfig => "config",
        PortInfo => "port",
        StateChangedEvent => "event",
        _ => throw new ArgumentException("Unknown checkpoint type.", nameof(value)),
    }, null, JournalValueCodecs.Standard
        .WithImmutable<ParityBits>("devcom.parity/v1", value => value.ToString(), Enum.Parse<ParityBits>)
        .WithImmutable<StopBits>("devcom.stop/v1", value => value.ToString(), Enum.Parse<StopBits>)
        .WithImmutable<State>("devcom.state/v1", value => value.ToString(), Enum.Parse<State>));

    [Fact]
    public void OriginalPortDefaultsAndEqualitySurviveBothGetterTraits()
    {
        var value = PortConfig.NewBuilder().Build();
        Assert.Equal("COM0", ((IReadOnlyWithName)value).Name);
        Assert.Equal(115200, value.BaudRate);
        Assert.Equal(8, value.NumberOfDataBits);
        Assert.Equal(ParityBits.NO_PARITY, value.ParityBits);
        Assert.Equal(StopBits.ONE_STOP_BIT, value.StopBits);
        Assert.False(value.RS485ModeEnabled);
        Assert.Equal(200, value.SafetyTimeout);
        Assert.Equal(0, value.WriteTimeout);
        Assert.Same(value, value.AsReadOnly());

        var reflected = ModelSnapshot.Reflection.Reflect(value);
        Assert.Equal(8, reflected.Properties.Count);
        Assert.Equal(StopBits.ONE_STOP_BIT, reflected.FindProperty("stopBits")?.DefaultValue);
        Assert.Equal(ParityBits.NO_PARITY, reflected.FindProperty("parityBits")?.DefaultValue);
        Assert.Equal(StopBits.ONE_STOP_BIT, ModelSnapshot.ReadPlain<PortConfig>("{}").StopBits);

        var info = PortInfo.NewBuilder().Build();
        Assert.Equal("COM0", ((IReadOnlyWithName)info).Name);
        Assert.Empty(((IReadOnlyWithExtendedName)info).ExtendedName!);
        Assert.Empty(info.Location!);
        Assert.Empty(info.Description!);
        Assert.Equal(info, PortInfo.NewBuilder().WithDescription("other").WithExtendedName("USB").WithLocation("left").Build());
        Assert.NotEqual(info, PortInfo.NewBuilder().WithName("COM7").Build());
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void OriginalDocumentsAndEnumBearingV1JournalsExchange(string format)
    {
        var config = PortConfig.NewBuilder().WithName("COM7").WithParityBits(ParityBits.ODD_PARITY)
            .WithStopBits(StopBits.TWO_STOP_BITS).WithRS485ModeEnabled(true).Build();
        var port = PortInfo.NewBuilder().WithName("COM7").WithExtendedName("Port COM7").WithDescription("USB").WithLocation("left").Build();
        var state = Event(null);
        var loaded = Exchange("config", config, format);
        Assert.Equal("COM7", loaded.Name);
        Assert.Equal(ParityBits.ODD_PARITY, loaded.ParityBits);
        Assert.Equal(StopBits.TWO_STOP_BITS, loaded.StopBits);
        Assert.True(loaded.RS485ModeEnabled);
        var location = Exchange("port", port, format);
        Assert.Equal("Port COM7", location.ExtendedName);
        Assert.Equal("USB", location.Description);
        Assert.Equal("left", location.Location);
        var stateFromWire = Exchange("event", state, format);
        Assert.Null(stateFromWire.Exception);
        Assert.Equal(State.CONNECTING, stateFromWire.OldState);
        Assert.Equal(State.ERROR, stateFromWire.NewState);
        Assert.Equal(42, stateFromWire.Timestamp);

        var baseline = Exchange("checkpoint", new DevComCheckpoint(), format);
        var journal = JournalRecorder<DevComCheckpoint>.RecordFrame(new DevComCheckpoint(), ModelSnapshot.Registry,
            Binding, "initial", 0, source =>
            {
                source.Config = config;
                source.Port = port;
                source.Event = state;
            });
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) serializer = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            _ => (JournalXml.Write, JournalXml.Read),
        };
        string wire = ExchangeDocument("journal", serializer.Write(journal), format);
        var received = serializer.Read(wire);
        Assert.Equal(1, received.WireVersion);
        Assert.Equal(JournalJson.Write(journal), JournalJson.Write(received));
        var replayed = JournalReplayer.Replay(baseline, Binding, "initial", received, ModelSnapshot.Registry);
        Assert.Equal(ParityBits.ODD_PARITY, replayed.Config?.ParityBits);
        Assert.Equal(StopBits.TWO_STOP_BITS, replayed.Config?.StopBits);
        Assert.Equal("COM7", replayed.Port?.Name);
        Assert.Equal(State.ERROR, replayed.Event?.NewState);
        Assert.Null(baseline.Config);
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void ExceptionBindingKeepsTheStockReadableJacksonShapes(string format)
    {
        foreach (var (name, expected) in Faults())
        {
            if (format == "xml" && (name is "stack" or "suppressed"))
            {
                Assert.Throws<XmlException>(() => Write(Event(expected), format));
                continue;
            }

            var restored = Exchange(name, Event(expected), format);
            var fault = Assert.IsType<JavaException>(restored.Exception);
            Assert.Equal(expected.Message ?? (format == "xml" ? "" : null), fault.Message);
            Assert.Equal(expected.StackCount, fault.StackCount);
            Assert.Equal(expected.SuppressedCount, fault.SuppressedCount);
            Assert.Equal(expected.Cause.ValueKind, fault.Cause.ValueKind);
            if (name == "cause") Assert.Equal("busy", fault.Cause.GetProperty("message").GetString());
            if (name == "stack") Assert.True(fault.Data.GetProperty("stackTrace")[1].GetProperty("nativeMethod").GetBoolean());
            if (name == "suppressed")
                Assert.Equal("cleanup failed", fault.Data.GetProperty("suppressed")[0].GetProperty("message").GetString());
        }
    }

    [Fact]
    public void ExceptionSnapshotSupportDoesNotMakeThrowablesJournalValues()
    {
        Assert.Throws<InvalidOperationException>(() =>
            JournalRecorder<DevComCheckpoint>.RecordFrame(new DevComCheckpoint(), ModelSnapshot.Registry, Binding, "initial", 0,
                source => source.Event = Event(Fault("offline"))));
    }

    private static StateChangedEvent Event(JavaException? value) =>
        new(exception: value, newState: State.ERROR, oldState: State.CONNECTING, timestamp: 42);

    private static Dictionary<string, JavaException> Faults() => new()
    {
        ["simple"] = Fault("offline"),
        ["stack"] = Fault("serial timeout", frames: [
            Frame("demo.Driver", "open", "Driver.java", 42),
            Frame("demo.Test", "boot", null, -2),
        ]),
        ["cause"] = Fault("open failed", cause: Fault("busy")),
        ["suppressed"] = Fault("open failed", suppressed: [Fault("cleanup failed")]),
        ["null-message"] = Fault(null),
    };

    private static JavaException Fault(string? message, JavaException? cause = null, object[]? frames = null,
        JavaException[]? suppressed = null) => new(JsonSerializer.SerializeToElement(new
    {
        cause = cause?.Data,
        stackTrace = frames ?? [],
        message,
        suppressed = (suppressed ?? []).Select(value => value.Data),
        localizedMessage = message,
    }));

    private static object Frame(string type, string method, string? file, int line) => new
    {
        classLoaderName = (string?)null, moduleName = (string?)null, moduleVersion = (string?)null,
        methodName = method, fileName = file, lineNumber = line, className = type, nativeMethod = line == -2,
    };

    private static T Exchange<T>(string name, T value, string format) where T : class
    {
        string wire = Write(value, format);
        string incoming = ExchangeDocument(name, wire, format);
        if (format == "json") Assert.True(JsonNode.DeepEquals(JsonNode.Parse(wire), JsonNode.Parse(incoming)));
        return format switch
        {
            "json" => ModelSnapshot.ReadPlain<T>(incoming),
            "yaml" => ModelSnapshot.ReadPlainYaml<T>(incoming),
            _ => ModelSnapshot.ReadPlainXml<T>(incoming),
        };
    }

    private static string ExchangeDocument(string name, string wire, string format)
    {
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        if (directory is null) return wire;
        File.WriteAllText(Path.Combine(directory, $"csharp-stock-devcom-{name}.{format}"), wire);
        return File.ReadAllText(Path.Combine(directory, $"java-stock-devcom-{name}.{format}"));
    }

    private static string Write<T>(T value, string format) where T : class => format switch
    {
        "json" => ModelSnapshot.WritePlain(value),
        "yaml" => ModelSnapshot.WritePlainYaml(value),
        _ => ModelSnapshot.WritePlainXml(value),
    };
}
