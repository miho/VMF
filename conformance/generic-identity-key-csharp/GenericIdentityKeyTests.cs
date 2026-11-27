using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Genericidentitykey;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class GenericIdentityKeyTests
{
    private const string Checkpoint = "generic-identity-key-8";
    private static readonly JournalBinding Binding = new("conformance.generic-identity-key", 1, model => model switch
    {
        Dispatch => "dispatch",
        RouteManifest => "manifest",
        Location { Code: { } code } => code,
        Shipment { Code: { } code } => code,
        _ => throw new ArgumentException("Unknown identity-key model object")
    });

    [Fact]
    public void ClosedInheritedReferenceKeyUsesObjectIdentityAndGuardsRekeys()
    {
        var model = Specimen();
        Routes<Shipment> routes = model.Manifest!;
        IReadOnlyRoutes<IReadOnlyShipment> view = model.Manifest!.AsReadOnly();
        IReadOnlyRoutes<IReadOnlyRouteEntry> widerView = routes.AsReadOnly();
        var first = model.Targets[0];
        var second = model.Targets[1];
        var spare = model.Targets[2];
        Shipment shipment = routes.FindEntriesByLocation(first)!;
        Assert.NotSame(first, second);
        Assert.Equal(first, second);
        Assert.Same(routes.Entries[0], shipment);
        Assert.Same(shipment.AsReadOnly(), view.FindEntriesByLocation(first.AsReadOnly()));
        Assert.Same(shipment.AsReadOnly(), widerView.FindEntriesByLocation(first.AsReadOnly()));
        Assert.Null(routes.FindEntriesByLocation(spare));
        Assert.Equal("location", ModelSnapshot.Reflection.Reflect(routes).FindProperty("entries")!.KeyPropertyName);

        Assert.Throws<ArgumentException>(() => shipment.Location = second);
        Assert.Throws<ArgumentException>(() => shipment.Location = null);
        Assert.Throws<ArgumentException>(() => RouteManifest.NewBuilder()
            .WithEntries([Entry("duplicate-a", second), Entry("duplicate-b", second)]).Build());
        Assert.Same(first, shipment.Location);
        Assert.Same(shipment, routes.FindEntriesByLocation(first));
        shipment.Location = spare;
        Assert.Null(routes.FindEntriesByLocation(first));
        Assert.Same(shipment, routes.FindEntriesByLocation(spare));
    }

    [Fact]
    public void ImmutableModelKeysAreTheirOwnReadOnlyProjection()
    {
        var first = PermitCode.NewBuilder().WithLabel("same label").Build();
        var second = PermitCode.NewBuilder().WithLabel("same label").Build();
        var gate = Gate.NewBuilder().WithPermits([new Permit { Proof = first }, new Permit { Proof = second }]).Build();
        Assert.Equal(first, second);
        Assert.NotSame(first, second);
        Assert.Same(first, first.AsReadOnly());
        Assert.Same(gate.Permits[0].AsReadOnly(), gate.AsReadOnly().FindPermitsByProof(first));
        var restored = ModelSnapshot.Read<Gate>(ModelSnapshot.Write(gate));
        Assert.Same(restored.Permits[0].AsReadOnly(), restored.AsReadOnly().FindPermitsByProof(restored.Permits[0].Proof!));
    }

    [Fact]
    public void AForwardResolvedIdentityKeyRoundtripsAndReplaysUnderASeparateStableId()
    {
        var model = Specimen();
        var manifest = model.Manifest!;
        var entry = manifest.Entries[0];
        string checkpoint = ModelSnapshot.Write(model);
        using var recorder = JournalRecorder<Dispatch>.Start(model, ModelSnapshot.Registry, Binding, Checkpoint, 8);
        entry.Location = model.Targets[2];
        manifest.Entries.Move(1, 0);
        var journal = recorder.Finish();
        var formats = new (string Extension, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_IDENTITY_KEY");
        if (exchange is not null) File.WriteAllText(Path.Combine(exchange, "csharp-checkpoint.json"), checkpoint);
        foreach (var format in formats)
        {
            string output = format.Write(journal);
            Verify(checkpoint, format.Read(output));
            if (exchange is null) continue;
            Verify(File.ReadAllText(Path.Combine(exchange, "java-checkpoint.json")),
                format.Read(File.ReadAllText(Path.Combine(exchange, $"java-journal.{format.Extension}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-journal.{format.Extension}"), output);
        }
    }

    private static Dispatch Specimen()
    {
        var first = Target("first");
        var second = Target("second");
        var spare = Target("spare");
        var manifest = RouteManifest.NewBuilder().WithEntries([Entry("alpha", first), Entry("beta", second)]).Build();
        return Dispatch.NewBuilder().WithManifest(manifest).WithTargets([first, second, spare]).Build();
    }

    private static Location Target(string code) => new() { Code = code, Name = "Same appearance" };

    private static Shipment Entry(string code, Location target) => new() { Code = code, Location = target };

    private static void Verify(string json, ChangeJournal journal)
    {
        using var document = JsonDocument.Parse(json);
        var manifest = document.RootElement.GetProperty("manifest");
        Assert.True(manifest.GetProperty("shipments")[0].TryGetProperty("destination", out _));
        Assert.False(manifest.TryGetProperty("entries", out _));
        Assert.True(json.IndexOf("\"shipments\"", StringComparison.Ordinal) < json.IndexOf("\"targets\"", StringComparison.Ordinal));
        var initial = ModelSnapshot.Read<Dispatch>(json);
        var original = initial.Targets[0];
        Assert.Equal("alpha", initial.Manifest!.FindEntriesByLocation(original)!.Code);
        Assert.Equal(1, journal.WireVersion);
        var firstEdit = Assert.Single(journal.Transactions).Operations[0];
        Assert.Equal("alpha", firstEdit.Target);
        Assert.Equal("location", firstEdit.Property);

        var replayed = JournalReplayer.Replay(initial, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        var routes = replayed.Manifest!;
        Assert.Null(routes.FindEntriesByLocation(replayed.Targets[0]));
        Assert.Same(routes.Entries[1], routes.FindEntriesByLocation(replayed.Targets[2]));
        Assert.Equal("alpha", routes.Entries[1].Code);
        Assert.NotNull(initial.Manifest.FindEntriesByLocation(original));
    }
}
