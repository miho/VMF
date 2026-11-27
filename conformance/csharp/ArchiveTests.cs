using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ArchiveTests
{
    private static readonly string Fixture = Path.Combine(AppContext.BaseDirectory, "fixtures", "archive-references.json");

    [Fact]
    public void RecursiveGraphRestoresAliasesInBothDirections()
    {
        var root = ReadFixture();
        Verify(root);
        var json = ModelSnapshot.Write(root.AsReadOnly());
        Verify(ModelSnapshot.Read<ArchiveRoot>(json));

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ARCHIVE");
        if (interchange is not null)
        {
            Verify(ModelSnapshot.Read<ArchiveRoot>(File.ReadAllText(Path.Combine(interchange, "archive-java.json"))));
            File.WriteAllText(Path.Combine(interchange, "archive-csharp.json"), json);
        }
    }

    [Fact]
    public void CycleRejectionPreservesAllOwningProperties()
    {
        var root = ReadFixture();
        var head = Assert.IsType<ArchiveNode>(root.Head);
        var left = Assert.Single(head.Children);
        var right = Assert.IsType<ArchiveNode>(head.Inset);

        Assert.ThrowsAny<ArgumentException>(() => right.Children.Add(head));
        Assert.ThrowsAny<ArgumentException>(() => left.Inset = head);
        Assert.ThrowsAny<ArgumentException>(() => head.Inset = head);
        Assert.Same(head, root.Head);
        Assert.Same(right, head.Inset);
        Assert.Same(left, Assert.Single(head.Children));
        Assert.Null(left.Inset);
        Assert.Empty(right.Children);

        // Replacing an occupied slot with an ancestor must preserve the existing child.
        var nested = new ArchiveNode { Name = "nested" };
        left.Inset = nested;
        Assert.ThrowsAny<ArgumentException>(() => left.Inset = head);
        Assert.Same(nested, left.Inset);
        Assert.Same(left, nested.InsetOf);
    }

    [Fact]
    public void ReciprocalEditsCanMoveBetweenAListAndAnOccupiedSlot()
    {
        var root = ReadFixture();
        var head = Assert.IsType<ArchiveNode>(root.Head);
        var left = Assert.Single(head.Children);
        var right = Assert.IsType<ArchiveNode>(head.Inset);
        var readOnly = head.AsReadOnly();

        left.InsetOf = null;
        Assert.Same(head, left.Parent);
        left.InsetOf = head;
        Assert.Same(left, head.Inset);
        Assert.Same(left.AsReadOnly(), readOnly.Inset);
        Assert.Same(head, left.InsetOf);
        Assert.Null(left.Parent);
        Assert.Null(right.InsetOf);
        Assert.Empty(head.Children);

        left.Parent = head;
        Assert.Null(head.Inset);
        Assert.Null(left.InsetOf);
        Assert.Same(head, left.Parent);
        Assert.Same(left, Assert.Single(head.Children));
        left.Parent = null;
        Assert.Empty(head.Children);
        Assert.Null(left.Parent);

        var replacement = new ArchiveNode { Name = "replacement" };
        root.Head = replacement;
        Assert.Same(replacement, root.Head);
        root.Head = null;
        var required = Assert.Single(root.Validate());
        Assert.Equal("vmf.multiplicity", required.ModelCode);
        Assert.Equal("/head", required.ModelPath);
    }

    [Fact]
    public void ReadOnlyCollectionsReflectSequenceSlotsAndOrderedIdentityMoves()
    {
        var head = Assert.IsType<ArchiveNode>(ReadFixture().Head);
        var left = Assert.Single(head.Children);
        var right = Assert.IsType<ArchiveNode>(head.Inset);
        var view = head.AsReadOnly();
        Assert.Same(view.Children[0], view.Featured[0]);
        Assert.Same(view.Inset, view.Trail[0]);
        Assert.Null(view.Trail[1]);
        Assert.Same(view, view.Trail[4]);
        Assert.False(head.Featured.Add(left));
        Assert.ThrowsAny<ArgumentException>(() => head.Featured.Insert(0, right));
        Assert.ThrowsAny<ArgumentException>(() => head.Featured.Add(null!));
        head.Featured.Move(1, 0);
        Assert.Same(right.AsReadOnly(), view.Featured[0]);
        Assert.Same(left.AsReadOnly(), view.Featured[1]);
        head.Trail.Add(right);
        head.Trail.Add(null);
        Assert.Equal(7, view.Trail.Count);
        Assert.Same(right.AsReadOnly(), view.Trail[5]);
        Assert.Null(view.Trail[6]);
    }

    [Fact]
    public void NullSlotsDoNotContributeToReferenceMultiplicity()
    {
        var root = ReadFixture();
        Assert.Equal(3, root.Review.Count);
        root.Review.RemoveAt(0);
        var required = Assert.Single(root.Validate());
        Assert.Equal("vmf.multiplicity", required.ModelCode);
        Assert.Equal("/review", required.ModelPath);
        root.Review.Add(root.Head);
        Assert.Empty(root.Validate());
    }

    [Fact]
    public void ExactInheritedOppositeInvokesSubtypeValidation()
    {
        var owner = new ArchiveOwner();
        var leaf = new ArchiveOwnedLeaf { Owner = owner };
        Assert.Same(leaf, Assert.Single(owner.Nodes));
        Assert.Same(owner, leaf.Owner);
        var required = Assert.Single(owner.Validate());
        Assert.Equal("vmf.multiplicity", required.ModelCode);
        Assert.Equal("/nodes/0/label", required.ModelPath);
        leaf.Label = "ready";
        Assert.Empty(owner.Validate());
        leaf.Owner = null;
        Assert.Empty(owner.Nodes);
        Assert.Null(leaf.Owner);
    }

    [Theory]
    [InlineData("[\"left\", \"left\"]")]
    [InlineData("[\"left\", null]")]
    public void OrderedIdentitySnapshotsRejectDuplicateOrNullSlots(string collection)
    {
        string json = File.ReadAllText(Fixture).Replace("\"featured\": [\"left\", \"right\"]",
            "\"featured\": " + collection, StringComparison.Ordinal);
        Assert.Throws<JsonException>(() => ModelSnapshot.Read<ArchiveRoot>(json));
    }

    private static ArchiveRoot ReadFixture() =>
        ModelSnapshot.Read<ArchiveRoot>(File.ReadAllText(Fixture));

    private static void Verify(ArchiveRoot root)
    {
        var head = Assert.IsType<ArchiveNode>(root.Head);
        var left = Assert.Single(head.Children);
        var right = Assert.IsType<ArchiveNode>(head.Inset);
        Assert.Equal("main", head.Name);
        Assert.Equal("left", left.Name);
        Assert.Equal("right", right.Name);
        Assert.Same(head, left.Parent);
        Assert.Null(left.InsetOf);
        Assert.Same(head, right.InsetOf);
        Assert.Null(right.Parent);

        Assert.Equal(5, head.Trail.Count);
        Assert.Same(right, head.Trail[0]);
        Assert.Null(head.Trail[1]);
        Assert.Same(left, head.Trail[2]);
        Assert.Same(right, head.Trail[3]);
        Assert.Same(head, head.Trail[4]);
        Assert.Equal(2, head.Featured.Count);
        Assert.Same(left, head.Featured[0]);
        Assert.Same(right, head.Featured[1]);
        Assert.Empty(root.Validate());
    }
}
