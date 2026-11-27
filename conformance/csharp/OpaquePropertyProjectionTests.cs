using System.Text;
using Eu.Mihosoft.Vmf.Conformance.Hierarchy;
using Eu.Mihosoft.Vmf.Conformance.Redeclarations;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class OpaquePropertyProjectionTests
{
    [Fact]
    public void ReadOnlyPropertyEventsProjectOpaqueModelValuesFromOtherRegistries()
    {
        var first = new ArchiveNode { Name = "foreign" };
        var derived = new CatalogItem { Code = "derived" };
        var alreadyProjected = new ArchiveNode().AsReadOnly();
        var immutable = ValueLabel.NewBuilder().WithText("immutable").Build();
        var host = new StringBuilder("host");
        object?[] values = [first, derived, alreadyProjected, immutable, host, null];
        object?[] projections = [first.AsReadOnly(), derived.AsReadOnly(), alreadyProjected, immutable, host, null];
        var owner = new PayloadRecord { Payload = first };
        var view = owner.AsReadOnly();
        var mutableEvents = new List<ModelPropertyNotification>();
        var projectedEvents = new List<ModelPropertyNotification>();
        var modeledEvents = new List<ReadOnlyModelChange>();
        using var direct = owner.VmfChanges.AddDirectPropertyListener(mutableEvents.Add);
        using var projected = view.VmfChanges.AddDirectPropertyListener(projectedEvents.Add);
        using var modeled = view.VmfChanges.AddPropertyListener("payload", modeledEvents.Add);

        for (int index = 1; index < values.Length; ++index)
        {
            owner.Payload = values[index];
            Assert.Same(values[index], owner.Payload);
            Assert.Same(values[index], view.Payload);
        }
        Assert.Equal(values.Length - 1, mutableEvents.Count);
        Assert.Equal(mutableEvents.Count, projectedEvents.Count);
        Assert.Equal(mutableEvents.Count, modeledEvents.Count);
        for (int edit = 0; edit < mutableEvents.Count; ++edit)
        {
            var live = mutableEvents[edit];
            Assert.Same(owner, live.Source);
            Assert.False(live.IsModelReference);
            Assert.Same(values[edit], live.OldValue);
            Assert.Same(values[edit + 1], live.NewValue);
            var readOnly = projectedEvents[edit];
            Assert.Same(view, readOnly.Source);
            Assert.False(readOnly.IsModelReference);
            Assert.Same(projections[edit], readOnly.OldValue);
            Assert.Same(projections[edit + 1], readOnly.NewValue);
            var modelChange = modeledEvents[edit];
            Assert.Same(view, modelChange.Source);
            Assert.False(modelChange.IsModelReference);
            Assert.Same(projections[edit], modelChange.OldValue);
            Assert.Same(projections[edit + 1], modelChange.NewValue);
        }
    }

    [Fact]
    public void OpaqueListEventsKeepTheExactStoredModelAndView()
    {
        var owner = new PayloadList();
        var view = owner.AsReadOnly();
        var items = owner.Items;
        var viewItems = view.Items;
        var foreign = new ArchiveNode();
        var foreignView = foreign.AsReadOnly();
        int[] counts = new int[4];
        using var direct = items.AddListener(edit =>
            Verify(items, edit.Source, counts[0]++, edit.Removed.Select(entry => entry.Value),
                edit.Added.Select(entry => entry.Value)));
        using var viewDirect = viewItems.AddListener(edit =>
            Verify(viewItems, edit.Source, counts[1]++, edit.Removed.Select(entry => entry.Value),
                edit.Added.Select(entry => entry.Value)));
        using var modeled = owner.VmfChanges.AddPropertyListener("items", change =>
        {
            var edit = Assert.IsType<ModelCollectionChange>(change);
            Assert.False(edit.IsModelReference);
            Verify(owner, edit.Source, counts[2]++, edit.Removed.Select(entry => entry.Value),
                edit.Added.Select(entry => entry.Value));
        });
        using var viewModeled = view.VmfChanges.AddPropertyListener("items", edit =>
        {
            Assert.False(edit.IsModelReference);
            Verify(view, edit.Source, counts[3]++, edit.Removed.Select(entry => entry.Value),
                edit.Added.Select(entry => entry.Value));
        });

        items.Add(foreign);
        Assert.Same(foreign, items[0]);
        Assert.Same(foreign, viewItems[0]);
        items[0] = foreignView;
        Assert.Same(foreignView, items[0]);
        Assert.Same(foreignView, viewItems[0]);
        items.RemoveAt(0);
        Assert.Equal([3, 3, 3, 3], counts);

        void Verify(object source, object actualSource, int step,
            IEnumerable<object?> removed, IEnumerable<object?> added)
        {
            Assert.Same(source, actualSource);
            if (step == 0) Assert.Empty(removed);
            else Assert.Same(step == 1 ? foreign : foreignView, Assert.Single(removed));
            if (step == 2) Assert.Empty(added);
            else Assert.Same(step == 0 ? foreign : foreignView, Assert.Single(added));
        }
    }
}
