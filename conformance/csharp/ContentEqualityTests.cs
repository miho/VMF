using Eu.Mihosoft.Vmf.Conformance.Equality;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Xunit;
using EqualityModels = Eu.Mihosoft.Vmf.Conformance.Equality.ModelSnapshot;
using ValueModels = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public sealed class ContentEqualityTests
{
    private static readonly double NegativeZero = BitConverter.Int64BitsToDouble(long.MinValue);

    [Fact]
    public void ClrEqualityPoliciesUseContentOnlyForOptedInTypes()
    {
        var identity = new EqualityIdentity { Name = "match" };
        var otherIdentity = new EqualityIdentity { Name = "match" };
        Assert.True(identity.Equals(identity));
        Assert.False(identity.Equals(otherIdentity));
        var identityView = identity.AsReadOnly();
        Assert.False(identity.Equals(identityView));
        Assert.False(identityView.Equals(identity));
        Assert.True(identityView.Equals(identityView));
        Assert.True(EqualityModels.Reflection.ContentEquals(identityView, identity));
        Assert.Equal(identity.ContentHashCode(), EqualityModels.Reflection.ContentHashCode(identityView));
        Assert.True(identity.ContentEquals(otherIdentity));
        Assert.Equal(identity.ContentHashCode(), otherIdentity.ContentHashCode());

        var first = new EqualityNode { Name = "root", External = identity, Note = "ignored first" };
        var second = new EqualityNode { Name = "root", External = otherIdentity, Note = "ignored second" };
        AssertEquivalent(first, second);
        Assert.True(Assert.IsAssignableFrom<IEquatable<EqualityNode>>(first).Equals(second));
        Assert.False(first.Equals(new object()));
        Assert.False(first.Equals((object?)null));
        Assert.False(first.ContentEquals(new object()));
        Assert.False(first.ContentEquals(null));
        Assert.False(new HashSet<EqualityNode> { first }.Add(second));

        otherIdentity.Name = "changed";
        Assert.False(first.Equals(second));
        Assert.False(first.ContentEquals(second));
    }

    [Fact]
    public void ContainmentPolicyComparesOwningEdgesAndIgnoresExternalReferencesAndInverses()
    {
        var first = new EqualityContainer
        {
            Name = "owner", Child = new EqualityNode { Name = "single" }, External = new EqualityIdentity { Name = "left" },
        };
        var second = new EqualityContainer
        {
            Name = "owner", Child = new EqualityNode { Name = "single" }, External = new EqualityIdentity { Name = "right" },
        };
        first.Children.Add(new EqualityNode { Name = "a" });
        first.Children.Add(new EqualityNode { Name = "b" });
        second.Children.Add(new EqualityNode { Name = "a" });
        second.Children.Add(new EqualityNode { Name = "b" });
        Assert.True(first.Equals(second));
        Assert.True(first.ContentEquals(second));
        Assert.Equal(first.GetHashCode(), second.GetHashCode());

        second.Child!.Name = "changed single";
        Assert.False(first.Equals(second));
        second.Child.Name = "single";
        second.Children.Move(0, 1);
        Assert.False(first.Equals(second));

        second.Name = "different owner";
        Assert.Same(first, first.Children[0].Container);
        Assert.Same(second, second.Children[1].Container);
        AssertEquivalent(first.Children[0], second.Children[1]);
    }

    [Fact]
    public void EffectiveDerivedFieldsAndReadOnlyViewsUseTheConcreteContent()
    {
        EqualityNode first = new EqualityDerived { Name = "base", Extra = "trait", Note = "ignored" };
        var second = new EqualityDerived { Name = "base", Extra = "trait" };
        AssertEquivalent(first, second);
        Assert.False(first.Equals(new EqualityNode { Name = "base" }));

        var firstView = first.AsReadOnly();
        var secondView = second.AsReadOnly();
        Assert.True(EqualityModels.Reflection.ContentEquals(firstView, secondView));
        Assert.True(first.Equals(secondView));
        Assert.True(secondView.Equals(first));
        Assert.True(firstView.Equals(secondView));
        Assert.Equal(first.GetHashCode(), secondView.GetHashCode());
        Assert.Equal(first.ContentHashCode(), EqualityModels.Reflection.ContentHashCode(secondView));

        second.Extra = "changed trait";
        Assert.False(firstView.Equals(secondView));
        second.Extra = "trait";
        second.Name = "changed base";
        Assert.False(EqualityModels.Reflection.ContentEquals(firstView, secondView));
    }

    [Fact]
    public void ReferenceCollectionContentDistinguishesOrderAndEquivalentMultiplicityFromIdentity()
    {
        var shared = new EqualityNode { Name = "entry" };
        var left = new EqualityNode { Name = "outer" };
        var right = new EqualityNode { Name = "outer" };
        left.Links.AddRange([shared, null, shared]);
        right.Links.AddRange([new EqualityNode { Name = "entry" }, null, new EqualityNode { Name = "entry" }]);
        left.Members.AddRange([shared, new EqualityNode { Name = "second" }, new EqualityNode { Name = "entry" }]);
        right.Members.AddRange([new EqualityNode { Name = "second" }, new EqualityNode { Name = "entry" }, new EqualityNode { Name = "entry" }]);
        Assert.Equal(3, left.Members.Count);
        Assert.False(left.Members.Contains(new EqualityNode { Name = "entry" }));
        AssertEquivalent(left, right);

        right.Links.Move(1, 0);
        Assert.False(left.Equals(right));
        right.Links.Move(0, 1);
        right.Members.Clear();
        right.Members.AddRange([new EqualityNode { Name = "second" }, new EqualityNode { Name = "second" }, new EqualityNode { Name = "entry" }]);
        Assert.False(left.Equals(right));
    }

    [Fact]
    public void CyclicEqualityAndHashAreStableWhenEquivalentStatesHaveDifferentIdentityCounts()
    {
        var self = new EqualityNode { Name = "cycle" };
        self.Next = self;
        self.Members.Add(self);
        var head = new EqualityNode { Name = "cycle" };
        var tail = new EqualityNode { Name = "cycle" };
        head.Next = tail;
        tail.Next = head;
        head.Members.Add(tail);
        tail.Members.Add(head);
        AssertEquivalent(self, head);
        Assert.False(new HashSet<EqualityNode> { self }.Add(head));

        tail.Name = "changed";
        Assert.False(self.Equals(head));
        Assert.False(head.ContentEquals(self));
    }

    [Fact]
    public void PortableScalarContentsPreserveSignedZeroNaNAndMapKeyPresence()
    {
        var first = new EqualityNode { Reading = +0.0 };
        var second = new EqualityNode { Reading = NegativeZero };
        Assert.False(first.Equals(second));
        first.Reading = double.NaN;
        second.Reading = BitConverter.Int64BitsToDouble(0x7ff8000000000001L);
        AssertEquivalent(first, second);

        var left = new CollectionProfile();
        var right = new CollectionProfile();
        left.Markers.Put(+0.0, null);
        left.Markers.Put(NegativeZero, '-');
        right.Markers.Put(NegativeZero, '-');
        right.Markers.Put(+0.0, null);
        left.Levels.AddRange([+0.0, NegativeZero, double.NaN]);
        right.Levels.AddRange([double.NaN, NegativeZero, +0.0]);
        var reflection = ValueModels.Reflection;
        Assert.True(reflection.ContentEquals(left, right));
        Assert.Equal(reflection.ContentHashCode(left), reflection.ContentHashCode(right.AsReadOnly()));
        right.Markers.Remove(+0.0);
        Assert.False(reflection.ContentEquals(left, right));
    }

    [Fact]
    public void DeepAcyclicContentCanBeComparedWithoutGrowingTheCallStack()
    {
        var left = new EqualityNode();
        var right = new EqualityNode();
        var first = left;
        var second = right;
        for (int index = 0; index < 20_000; index++)
        {
            var firstNext = new EqualityNode();
            var secondNext = new EqualityNode();
            switch (index % 3)
            {
                case 0:
                    first.Next = firstNext;
                    second.Next = secondNext;
                    break;
                case 1:
                    first.Links.AddRange([null, firstNext]);
                    second.Links.AddRange([null, secondNext]);
                    break;
                default:
                    first.Members.Add(firstNext);
                    second.Members.Add(secondNext);
                    break;
            }
            first = firstNext;
            second = secondNext;
        }
        AssertEquivalent(left, right);
        second.Name = "different leaf";
        Assert.False(left.ContentEquals(right));
    }

    [Fact]
    public void DeepUnorderedCyclesUseStructuralMembersAfterUnmatchedCandidates()
    {
        var left = new EqualityNode { Name = "unordered root" };
        var right = new EqualityNode { Name = "unordered root" };
        var first = left;
        var second = right;
        for (int index = 0; index < 180; index++)
        {
            var firstNext = new EqualityNode();
            var secondNext = new EqualityNode();
            first.Members.Add(firstNext);
            second.Members.Add(secondNext);
            first = firstNext;
            second = secondNext;
        }
        var leftOne = new EqualityNode { Name = "candidate", Reading = 1, Next = left };
        var leftTwo = new EqualityNode { Name = "candidate", Reading = 2, Next = left };
        var rightOne = new EqualityNode { Name = "candidate", Reading = 1, Next = right };
        var rightTwo = new EqualityNode { Name = "candidate", Reading = 2, Next = right };
        first.Members.AddRange([leftOne, leftTwo]);
        second.Members.AddRange([rightTwo, rightOne]);

        AssertEquivalent(left, right);
        rightOne.Reading = 3;
        Assert.False(left.ContentEquals(right));
        Assert.False(right.ContentEquals(left));
        rightOne.Reading = 1;
        AssertEquivalent(left, right);
    }

    private static void AssertEquivalent(EqualityNode left, EqualityNode right)
    {
        Assert.True(left.ContentEquals(right));
        Assert.True(right.ContentEquals(left));
        Assert.True(left.Equals(right));
        Assert.True(right.Equals(left));
        Assert.Equal(left.ContentHashCode(), right.ContentHashCode());
        Assert.Equal(left.GetHashCode(), right.GetHashCode());
    }
}
