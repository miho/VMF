using Eu.Mihosoft.Vmf.Conformance.Hierarchy;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ShipmentTests
{
    private static readonly JournalBinding Binding = new("open-shipment", 1, value => value switch
    {
        DepotShipment shipment => shipment.Id!,
        Parcel parcel => parcel.Id!,
        Inspection inspection => inspection.Id!,
        _ => throw new ArgumentException("Unexpected shipment model.", nameof(value)),
    });

    [Fact]
    public void OpenRolesShareOneProjectionAndKeepIndependentOwningSlots()
    {
        var source = NewShipment();
        Shipment<TrackedParcel, ParcelInspection> open = source;
        ParcelDeck<TrackedParcel> parcels = open;
        IParcelSelection<TrackedParcel> selection = open;
        IInspectionDeck<ParcelInspection> inspections = open;
        Assert.Same(source.Parcels, parcels.Parcels);
        Assert.Same(source.Inspections, inspections.Inspections);
        Assert.Same(source.Parcels[0], selection.Selected);
        Assert.Same(selection.Selected, selection.History[0]);
        Assert.Same(source.Inspections[0], inspections.LatestInspection);

        IReadOnlyShipment<IReadOnlyTrackedParcel, IReadOnlyParcelInspection> view = source.AsReadOnly();
        Assert.Same(view, selection.AsReadOnly());
        Assert.Same(view, inspections.AsReadOnly());
        Assert.Same(view.Parcels[0], view.Selected);
        Assert.Same(view.Selected, view.History[0]);
        Assert.Same(view.Inspections[0], view.LatestInspection);
        var copy = ModelSnapshot.Reflection.DeepCopy(source);
        Assert.NotSame(source.Parcels[0], copy.Parcels[0]);
        Assert.NotSame(source.Inspections[0], copy.Inspections[0]);
        Assert.Same(copy.Parcels[0], copy.Selected);
        Assert.Same(copy.Parcels[0], copy.History[0]);
        Assert.Same(copy.Inspections[0], copy.LatestInspection);
        Assert.Equal(typeof(TrackedParcel), ModelSnapshot.Reflection.Reflect(copy).FindProperty("parcels")!.ValueType);
        Assert.Equal(typeof(ParcelInspection), ModelSnapshot.Reflection.Reflect(copy).FindProperty("inspections")!.ValueType);

        IInspectionDeck<ParcelInspection> destination = new DepotShipment();
        var moved = source.Inspections[1];
        destination.Inspections.Add(moved);
        Assert.Same(moved, Assert.Single(destination.Inspections));
        Assert.Single(inspections.Inspections);
        Assert.Single(view.Inspections);
        Assert.Single(parcels.Parcels);
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void BothOwnershipSlotsAndTheirAliasesSurviveJournalExchange(string format)
    {
        var initial = NewShipment();
        var journal = JournalRecorder<DepotShipment>.RecordFrame(NewShipment(), ModelSnapshot.Registry, Binding,
            "initial", 30, model =>
            {
                Shipment<TrackedParcel, ParcelInspection> open = model;
                open.Inspections.Move(1, 0);
                ((IInspectionDeck<ParcelInspection>)open).LatestInspection = open.Inspections[0];
                ((IParcelSelection<TrackedParcel>)open).History[1] = open.Parcels[0];
            });
        (Func<DepotShipment, string> WriteSnapshot, Func<string, DepotShipment> ReadSnapshot,
            Func<ChangeJournal, string> WriteJournal, Func<string, ChangeJournal> ReadJournal) codecs = format switch
        {
            "json" => (ModelSnapshot.Write, ModelSnapshot.Read<DepotShipment>, JournalJson.Write, JournalJson.Read),
            "yaml" => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<DepotShipment>, JournalYaml.Write, JournalYaml.Read),
            "xml" => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<DepotShipment>, JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        string checkpoint = codecs.WriteSnapshot(initial);
        string edits = codecs.WriteJournal(journal);
        Verify(checkpoint, edits);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_HIERARCHY") is { } directory)
        {
            string PathFor(string author, string kind) => Path.Combine(directory, $"{author}-shipment-{kind}.{format}");
            Verify(File.ReadAllText(PathFor("java", "checkpoint")), File.ReadAllText(PathFor("java", "journal")));
            File.WriteAllText(PathFor("csharp", "checkpoint"), checkpoint);
            File.WriteAllText(PathFor("csharp", "journal"), edits);
        }

        void Verify(string saved, string changes)
        {
            var original = codecs.ReadSnapshot(saved);
            Assert.Same(original.Parcels[0], original.Selected);
            Assert.Same(original.Selected, original.History[0]);
            Assert.Same(original.Selected, original.History[2]);
            var loaded = codecs.ReadJournal(changes);
            Assert.Equal(1, loaded.WireVersion);
            var restored = JournalReplayer.Replay(original, Binding, "initial", loaded, ModelSnapshot.Registry);
            Assert.Equal(new[] { "follow-up", "report" }, restored.Inspections.Select(report => report.Id));
            Assert.Same(restored.Inspections[0], restored.LatestInspection);
            Assert.Same(restored.Parcels[0], restored.History[1]);
            Assert.Equal("report", original.Inspections[0].Id);
            Assert.Same(original.Inspections[0], original.LatestInspection);
            Assert.Null(original.History[1]);
        }
    }

    private static DepotShipment NewShipment()
    {
        var parcel = new TrackedParcel { Id = "parcel", TrackingCode = "T-1" };
        var first = new ParcelInspection { Id = "report", Cleared = true };
        var next = new ParcelInspection { Id = "follow-up" };
        return DepotShipment.NewBuilder().WithId("depot").WithParcels([parcel]).WithSelected(parcel)
            .WithHistory([parcel, null, parcel]).WithInspections([first, next]).WithLatestInspection(first).Build();
    }
}
