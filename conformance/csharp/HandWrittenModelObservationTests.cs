using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

/// <summary>
/// A model written by hand uses the runtime without the interfaces of generated code: it has a containment link, but
/// does not name it. The change streams keep what they know about such an object by its identity. A hand-written
/// class may name its link as generated classes do (<see cref="IModelLinkAccess"/>); then its state is kept there.
/// </summary>
public class HandWrittenModelObservationTests
{
    [Fact]
    public void AReparentedChildReportsBothContainersToItsOwnListener()
    {
        var first = new Folder("first");
        var second = new Folder("second");
        var item = new Folder("item");
        first.Children.Add(item);
        var containers = new List<(string Property, object? Old, object? New, bool Inverse)>();
        using var watch = ModelChanges.For(item).AddListener(
            change => containers.Add((change.PropertyName, change.OldValue, change.NewValue, change.IsInverse)),
            ModelChangeScope.Local);

        second.Children.Add(item);

        Assert.Equal(new (string, object?, object?, bool)[] { ("parent", first, null, true), ("parent", null, second, true) },
            containers);
        Assert.Empty(first.Children);
        Assert.Same(item, Assert.Single(second.Children));
    }

    [Fact]
    public void ARecursiveListenerFollowsTheChildrenOfATrackedList()
    {
        var root = new Folder("root");
        var child = new Folder("child");
        var grandchild = new Folder("grandchild");
        child.Children.Add(grandchild);
        var seen = new List<(object Source, string Property)>();
        using var watch = ModelChanges.For(root).AddListener(change => seen.Add((change.Source, change.PropertyName)));

        root.Children.Add(child);
        grandchild.Name = "renamed";
        child.Tags.Add("tagged");
        root.Children.Remove(child);
        grandchild.Name = "unobserved";
        child.Tags.Add("unobserved");

        // The child joins the observed tree with the list change, and leaves it after reporting its container.
        Assert.Equal(new (object, string)[]
        {
            (root, "children"), (grandchild, "name"), (child, "tags"), (child, "parent"), (root, "children"),
        }, seen);
    }

    [Fact]
    public void ARecursiveListenerFollowsTheChildrenOfAListWithoutAChangeSource()
    {
        var root = new Folder("root");
        var child = new Folder("child");
        var seen = new List<(object Source, string Property)>();
        using var watch = ModelChanges.For(root).AddListener(change => seen.Add((change.Source, change.PropertyName)));

        // The list reports no change of its own; the listener still learns of the child, and of its departure.
        root.Untracked.Add(child);
        child.Name = "renamed";
        root.Untracked.Remove(child);
        child.Name = "unobserved";

        Assert.Equal(new (object, string)[] { (child, "name"), (child, "__vmf_prop_unnamed_container") }, seen);
    }

    [Fact]
    public void TheEntriesOfAChangeAreTheSameOnEveryRead()
    {
        var root = new Folder("root");
        var child = new Folder("child");
        var changes = new List<ModelCollectionChange>();
        using var watch = ModelChanges.For(root).AddListener(
            change => changes.Add(Assert.IsType<ModelCollectionChange>(change)), ModelChangeScope.Local);

        root.Children.Add(child);
        root.Tags.Add("first");
        root.Tags[0] = "second";

        Assert.Equal(3, changes.Count);
        foreach (var change in changes)
        {
            Assert.Same(change.Added, change.Added);
            Assert.Same(change.Removed, change.Removed);
            Assert.Same(change.Added[0], change.Added[0]);
        }
        Assert.Empty(changes[0].Removed);
        Assert.Equal(new ModelCollectionEntry(child, Index: 0), Assert.Single(changes[0].Added));
        Assert.Equal(new ModelCollectionEntry("first", Index: 0), Assert.Single(changes[2].Removed));
        Assert.Equal(new ModelCollectionEntry("second", Index: 0), Assert.Single(changes[2].Added));
    }

    [Fact]
    public void AHandWrittenModelThatNamesItsLinkIsObservedLikeAGeneratedOne()
    {
        var root = new Named("root");
        var child = new Named("child");
        var seen = new List<(object Source, string Property)>();
        using var watch = ModelChanges.For(root).AddListener(change => seen.Add((change.Source, change.PropertyName)));

        root.Children.Add(child);
        child.Name = "renamed";
        root.Children.Remove(child);
        child.Name = "unobserved";

        Assert.Same(ModelChanges.For(root), ModelChanges.For(root));
        Assert.Equal(new (object, string)[] { (root, "children"), (child, "name"), (child, "parent"), (root, "children") },
            seen);
    }

    [Fact]
    public void AModelThatNamesItsLinkNeedsItBeforeItsContainments()
    {
        // Its containments would be registered in the table and looked for at the link: listeners would miss them.
        var error = Assert.Throws<InvalidOperationException>(() => new LateLink());

        Assert.Contains("containment link", error.Message);
    }

    /// <summary>Names its link, as generated classes do: the runtime keeps the state of the object there.</summary>
    private sealed class Named : IModelLinkAccess
    {
        private readonly ContainmentLink link = new();
        private string? name;

        public Named(string name)
        {
            this.name = name;
            Children = new(this, link, static child => child.link, new ModelChangeSource(this, link, "children"), "parent");
        }

        ContainmentLink IModelLinkAccess.VmfContainmentLink => link;

        public ContainmentList<Named, Named> Children { get; }

        public string? Name
        {
            get => name;
            set => ModelChanges.SetProperty(this, link, "name", ref name, value,
                static model => model.Name, static (model, value) => model.Name = value, isModelReference: false);
        }
    }

    private sealed class LateLink : IModelLinkAccess
    {
        private readonly ContainmentLink? link;

        public LateLink()
        {
            Children = new(this, static child => child.link!);
            link = new();
        }

        ContainmentLink IModelLinkAccess.VmfContainmentLink => link!;

        public ContainmentList<LateLink, LateLink> Children { get; }
    }

    private sealed class Folder
    {
        private readonly ContainmentLink link = new();
        private string? name;

        public Folder(string name)
        {
            this.name = name;
            Children = new(this, link, static child => child.link, new ModelChangeSource(this, link, "children"), "parent");
            Untracked = new(this, link, static child => child.link);
            Tags = new(allowNull: true, changeSource: new(this, link, "tags"));
        }

        public ContainmentList<Folder, Folder> Children { get; }
        public ContainmentList<Folder, Folder> Untracked { get; }
        public ScalarList<string?> Tags { get; }

        public string? Name
        {
            get => name;
            set => ModelChanges.SetProperty(this, link, "name", ref name, value,
                static model => model.Name, static (model, value) => model.Name = value, isModelReference: false);
        }
    }
}
