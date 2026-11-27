using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class OrdinaryOwningBuilderTests
{
    [Fact]
    public void UnorderedBuilderReplacementPublishesMembershipAndKeepsBoundsForValidation()
    {
        var source = new Shelf { Name = "source" };
        var a = new Volume { Name = "a" };
        var b = new Volume { Name = "b" };
        source.Volumes.AddRange([a, b]);
        var staged = Shelf.NewBuilder().ApplyFrom(source);
        Assert.Equal(2, source.Volumes.Count);
        Assert.Same(source, a.Shelf);

        var imported = staged.Build();
        Assert.Same(imported, a.Shelf);
        Assert.Same(imported, b.Shelf);
        Assert.Empty(source.Volumes);

        var target = new Shelf { Name = "target" };
        var retained = new Volume { Name = "retained" };
        var obsolete = new Volume { Name = "obsolete" };
        target.Volumes.AddRange([retained, obsolete]);
        var membership = target.Volumes;
        var edits = new List<ModelCollectionChange>();
        using (target.VmfChanges.AddPropertyListener("volumes", edit => edits.Add((ModelCollectionChange)edit)))
        {
            Shelf.NewBuilder().WithName("target").WithVolumes([retained, a, b])
                .AppendCollections(false).ApplyTo(target);
        }

        Assert.Same(membership, target.Volumes);
        Assert.Equal(3, membership.Count);
        Assert.All(membership, member => Assert.Same(target, member.Shelf));
        Assert.Empty(imported.Volumes);
        Assert.Null(obsolete.Shelf);
        Assert.Collection(edits,
            removal =>
            {
                Assert.Same(obsolete, Assert.Single(removal.Removed).Value);
                Assert.Empty(removal.Added);
            },
            addition =>
            {
                Assert.Empty(addition.Removed);
                Assert.Equal(2, addition.Added.Count);
            });
        Assert.All(edits.SelectMany(edit => edit.Removed.Concat(edit.Added)), entry => Assert.Null(entry.Index));
        Assert.Equal("/volumes", Assert.Single(target.Validate()).ModelPath);
    }

    [Fact]
    public void SameSourceAppendKeepsBothOwningAliasesAndAppendsANullSlot()
    {
        var source = BuilderSource();
        var list = source.Children;
        var repeated = list[0]!;
        var events = new List<string>();
        using var listener = Watch(source, "source", events);

        var staged = LegacyContainer.NewBuilder().ApplyFrom(source);
        Assert.Empty(events);
        Assert.Same(source, repeated.Owner);
        staged.ApplyTo(source);

        Assert.Same(list, source.Children);
        Assert.Equal(["null", "a", "null", "b", "a"], Names(source));
        Assert.Same(repeated, list[1]);
        Assert.Same(repeated, list[4]);
        Assert.Same(source, repeated.Owner);
        Assert.Equal(["source:[]:[0]"], events);
    }

    [Fact]
    public void SameSourceReplacementCopiesSlotsThroughOrdinaryInsertion()
    {
        var source = BuilderSource();
        var list = source.Children;
        var repeated = list[0]!;
        var events = new List<string>();
        using var listener = Watch(source, "source", events);

        LegacyContainer.NewBuilder().AppendCollections(false).ApplyFrom(source).ApplyTo(source);

        Assert.Same(list, source.Children);
        Assert.Equal(["null", "b", "a"], Names(source));
        Assert.Same(repeated, list[2]);
        Assert.Same(source, repeated.Owner);
        Assert.Equal(["source:[0, 1, 2, 3]:[]", "source:[]:[0, 1, 2]"], events);
    }

    [Fact]
    public void DistinctReplacementTransfersTheOriginalChildrenAndKeepsLiveLists()
    {
        var source = BuilderSource();
        var repeated = source.Children[0]!;
        var middle = source.Children[2]!;
        var old = Child("old");
        var target = LegacyContainer.NewBuilder().WithName("target").WithChildren([old]).Build();
        var targetList = target.Children;
        var events = new List<string>();
        using var sourceListener = Watch(source, "source", events);
        using var targetListener = Watch(target, "target", events);

        LegacyContainer.NewBuilder().AppendCollections(false).ApplyFrom(source).ApplyTo(target);

        Assert.Same(targetList, target.Children);
        Assert.Null(Assert.Single(source.Children));
        Assert.Equal(["null", "b", "a"], Names(target));
        Assert.Same(middle, targetList[1]);
        Assert.Same(repeated, targetList[2]);
        Assert.Same(target, repeated.Owner);
        Assert.Same(target, middle.Owner);
        Assert.Null(old.Owner);
        Assert.Equal(["target:[0]:[]", "source:[2]:[]", "source:[0, 2]:[]", "target:[]:[0, 1, 2]"], events);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void InvalidCycleLeavesTheGraphAndEarlierBuilderPropertiesUnchanged(bool append)
    {
        var root = new BuilderCycleNode { Caption = "root" };
        var branch = new BuilderCycleNode { Caption = "branch" };
        var leaf = new BuilderCycleNode { Caption = "leaf" };
        root.Children.Add(branch);
        branch.Children.Add(leaf);
        var events = new List<ModelChange>();
        using var listener = branch.VmfChanges.AddLocalListener(events.Add);
        var staged = BuilderCycleNode.NewBuilder().WithCaption("changed").WithChildren([root]).AppendCollections(append);

        Assert.Throws<ArgumentException>(() => staged.ApplyTo(branch));

        Assert.Empty(events);
        Assert.Equal("branch", branch.Caption);
        Assert.Same(branch, Assert.Single(root.Children));
        Assert.Same(leaf, Assert.Single(branch.Children));
        Assert.Same(root, branch.__vmfContainerLink.Owner);
        Assert.Same(branch, leaf.__vmfContainerLink.Owner);
    }

    [Fact]
    public void ReplacementRechecksAContainmentCycleIntroducedByAnEarlierObserver()
    {
        var root = new BuilderCycleNode();
        var target = new BuilderCycleNode { Caption = "branch" };
        var leaf = new BuilderCycleNode();
        target.Children.Add(leaf);
        var events = new List<string>();
        using var list = target.Children.AddListener(_ => events.Add("children"));
        using var listener = target.VmfChanges.AddPropertyListener("caption", change =>
        {
            events.Add(change.PropertyName);
            root.Children.Add(target);
        });
        var staged = BuilderCycleNode.NewBuilder().WithCaption("changed").WithChildren([root]).AppendCollections(false);

        Assert.Throws<ArgumentException>(() => staged.ApplyTo(target));

        Assert.Equal(["caption"], events);
        Assert.Equal("changed", target.Caption);
        Assert.Same(target, Assert.Single(root.Children));
        Assert.Same(leaf, Assert.Single(target.Children));
        Assert.Same(root, target.__vmfContainerLink.Owner);
        Assert.Same(target, leaf.__vmfContainerLink.Owner);
    }

    private static LegacyContainer BuilderSource()
    {
        var source = LegacyContainer.NewBuilder().WithName("source")
            .WithChildren([Child("discarded"), null, Child("b"), Child("a")]).Build();
        source.Children[0] = source.Children[3];
        return source;
    }

    private static LegacyChild Child(string name) => LegacyChild.NewBuilder().WithName(name).Build();

    private static IEnumerable<string> Names(LegacyContainer owner) =>
        owner.Children.Select(child => child?.Name ?? "null");

    private static IDisposable Watch(LegacyContainer owner, string scope, List<string> events)
    {
        var list = owner.Children;
        return list.AddListener(edit =>
        {
            Assert.Same(list, edit.Source);
            events.Add($"{scope}:[{string.Join(", ", edit.Removed.Select(entry => entry.Index))}]"
                + $":[{string.Join(", ", edit.Added.Select(entry => entry.Index))}]");
        });
    }
}
