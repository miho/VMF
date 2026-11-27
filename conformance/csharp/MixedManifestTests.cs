using Eu.Mihosoft.Vmf.Conformance.Hierarchy;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class MixedManifestTests
{
    private static readonly JournalBinding Binding = new("mixed-manifest", 1,
        model => model is InstrumentedShipment shipment ? shipment.Id! : ((Parcel)model).Id!);
    private const long UpdatedRevision = 9_007_199_254_740_993;

    [Fact]
    public void MixedGenericRolesPreserveTypesAndAliasesThroughV1Replay()
    {
        var source = Manifest();
        var view = source.AsReadOnly();
        TelemetryManifest<TrackedParcel, string?, long> combined = source;
        ManifestStorage<TrackedParcel, string?, long> storage = combined;
        IManifestRevision<long> revisions = storage;
        IParcelSelection<TrackedParcel> selection = combined;
        revisions.Revision = UpdatedRevision;
        storage.Labels["lane"] = "east";
        Assert.Same(view, revisions.AsReadOnly());
        Assert.Same(view, selection.AsReadOnly());
        Assert.Equal(UpdatedRevision, view.Revision);
        Assert.Equal("east", view.Labels["lane"]);
        Assert.Same(view.Parcels[0], view.Selected);
        Assert.Same(view.Selected, view.History[0]);

        string saved = ModelSnapshot.Write(Manifest());
        var journal = JournalRecorder<InstrumentedShipment>.RecordFrame(Manifest(), ModelSnapshot.Registry, Binding,
            "initial", 12, model =>
            {
                ((IManifestRevision<long>)model).Revision = UpdatedRevision;
                model.Labels["lane"] = "east";
                ((IParcelSelection<TrackedParcel>)model).History.Add(model.Parcels[0]);
            });
        string edits = JournalJson.Write(journal);
        Verify(saved, edits);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_HIERARCHY") is { } exchange)
        {
            Verify(File.ReadAllText(Path.Combine(exchange, "java-mixed-manifest.json")),
                File.ReadAllText(Path.Combine(exchange, "java-mixed-manifest-journal.json")));
            File.WriteAllText(Path.Combine(exchange, "csharp-mixed-manifest.json"), saved);
            File.WriteAllText(Path.Combine(exchange, "csharp-mixed-manifest-journal.json"), edits);
        }
    }

    private static void Verify(string checkpoint, string edits)
    {
        var original = ModelSnapshot.Read<InstrumentedShipment>(checkpoint);
        Assert.Equal(12L, original.Revision);
        Assert.Equal("west", original.Labels["lane"]);
        Assert.Same(original.Parcels[0], original.Selected);
        Assert.Same(original.Selected, Assert.Single(original.History));
        var loaded = JournalJson.Read(edits);
        Assert.Equal(1, loaded.WireVersion);
        var restored = JournalReplayer.Replay(original, Binding, "initial", loaded, ModelSnapshot.Registry);
        Assert.Equal(UpdatedRevision, restored.Revision);
        Assert.Equal("east", restored.Labels["lane"]);
        Assert.NotSame(original.Parcels[0], restored.Parcels[0]);
        Assert.Same(restored.Parcels[0], restored.Selected);
        Assert.Equal(2, restored.History.Count);
        Assert.All(restored.History, item => Assert.Same(restored.Selected, item));
        Assert.Single(original.History);
    }

    private static InstrumentedShipment Manifest()
    {
        var parcel = new TrackedParcel { Id = "parcel", TrackingCode = "T-1" };
        var model = InstrumentedShipment.NewBuilder().WithId("manifest").WithParcels([parcel])
            .WithSelected(parcel).WithHistory([parcel]).WithRevision(12L).Build();
        model.Labels["lane"] = "west";
        return model;
    }
}
