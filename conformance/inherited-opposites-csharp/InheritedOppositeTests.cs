using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Inheritedopposites;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class InheritedOppositeTests
{
    private static readonly string[] InheritedLive =
    [
        "a.parts:ReferenceSet", "b.parts:ReferenceSet", "part.team:Property",
        "a.featured:ReferenceSet", "part.team:Property", "b.featured:ReferenceSet"
    ];
    private static readonly string[] InheritedHistory = ["a.parts", "b.parts", "a.featured", "part.team"];

    [Fact]
    public void InterfaceOnlySecondaryReciprocalsShareStorageAndPreserveBothEndsInCopyAndSnapshot()
    {
        var a = new SpecializedDispatchTeam { Name = "north" };
        IDispatchTeam b = new SpecializedDispatchTeam { Name = "south" };
        var first = new SpecializedDispatchMember { Name = "first" };
        IDispatchMember second = new SpecializedDispatchMember { Name = "second" };
        var board = new DispatchBoard();
        board.Teams.AddRange([a, b]);
        board.People.AddRange([first, second]);

        IDispatchTeam secondary = a;
        DispatchHeader primary = a;
        var view = secondary.AsReadOnly();
        Assert.Same(primary, secondary);
        Assert.Same(a.AsReadOnly(), view);
        Assert.Same(a.Members, secondary.Members);
        Assert.True(secondary.Members.Add(first));
        Assert.Same(secondary, first.Team);
        Assert.False(a.Members.Add(first));

        first.Team = b;
        Assert.Empty(view.Members);
        Assert.Same(first, Assert.Single(b.Members));
        Assert.Same(b.AsReadOnly(), first.AsReadOnly().Team);
        second.Team = secondary;

        secondary.Lead = first;
        Assert.Same(secondary, first.LeadOf);
        Assert.Same(first.AsReadOnly(), view.Lead);
        first.LeadOf = b;
        Assert.Null(secondary.Lead);
        Assert.Same(first, b.Lead);
        b.Lead = second;
        Assert.Null(first.LeadOf);
        Assert.Same(b, second.LeadOf);
        Assert.Same(secondary, second.Team);
        secondary.Lead = first;

        var copy = ModelSnapshot.Reflection.DeepCopy(board);
        AssertSecondarySnapshot(copy);
        Assert.NotSame(secondary, copy.Teams[0]);
        Assert.NotSame(first, copy.People[0]);

        string snapshot = ModelSnapshot.Write(board);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SECONDARY_RECIPROCAL");
        if (exchange is not null)
        {
            Directory.CreateDirectory(exchange);
            File.WriteAllText(Path.Combine(exchange, "csharp-dispatch-board.json"), snapshot);
            snapshot = File.ReadAllText(Path.Combine(exchange, "java-dispatch-board.json"));
        }
        AssertSecondarySnapshot(ModelSnapshot.Read<DispatchBoard>(snapshot));
    }

    [Fact]
    public void ConcreteSecondaryRolesStillSupportDirectInstancesAndRoleMutations()
    {
        IConcreteDispatchTeam first = new ConcreteDispatchTeam();
        IConcreteDispatchTeam second = new NamedConcreteDispatchTeam { Name = "second" };
        IConcreteDispatchMember member = new NamedConcreteDispatchMember { Name = "member" };

        Assert.True(first.Members.Add(member));
        Assert.Same(first, member.Team);
        member.Team = second;
        Assert.Empty(first.Members);
        Assert.Same(member, Assert.Single(second.Members));
        Assert.Same(second.AsReadOnly(), member.AsReadOnly().Team);
    }

    private static void AssertSecondarySnapshot(DispatchBoard board)
    {
        var north = Assert.IsType<SpecializedDispatchTeam>(board.Teams[0]);
        var south = Assert.IsType<SpecializedDispatchTeam>(board.Teams[1]);
        var first = Assert.IsType<SpecializedDispatchMember>(board.People[0]);
        var second = Assert.IsType<SpecializedDispatchMember>(board.People[1]);
        Assert.Same(second, Assert.Single(north.Members));
        Assert.Same(north, second.Team);
        Assert.Same(first, Assert.Single(south.Members));
        Assert.Same(south, first.Team);
        Assert.Same(first, north.Lead);
        Assert.Same(north, first.LeadOf);
        Assert.Same(second, south.Lead);
        Assert.Same(south, second.LeadOf);
    }

    [Fact]
    public void PrimaryClassOppositesKeepOneInheritedSlotAndBothLiveInverses()
    {
        var root = new RelationshipSite();
        PrimaryTeam a = new PrimaryTeamSpecialization();
        PrimaryTeam b = new PrimaryTeamSpecialization();
        PrimaryPart part = new PrimaryPartSpecialization();
        root.PrimaryTeams.AddRange([a, b]);
        a.Parts.Add(part);
        a.Featured.Add(part);
        using var trace = new Trace(root, [(a, "a"), (b, "b"), (part, "part")]);

        part.Owner = b;
        part.Team = b;

        Assert.Empty(a.Parts);
        Assert.Empty(a.Featured);
        Assert.Same(part, Assert.Single(b.Parts));
        Assert.Same(part, Assert.Single(b.Featured));
        Assert.Same(b.AsReadOnly(), part.AsReadOnly().Owner);
        Assert.Same(b.AsReadOnly(), part.AsReadOnly().Team);
        trace.VerifyAndUndo(InheritedLive, InheritedHistory);
        Assert.Same(part, Assert.Single(a.Parts));
        Assert.Same(part, Assert.Single(a.Featured));
        Assert.Empty(b.Parts);
        Assert.Empty(b.Featured);
        Assert.Same(a, part.Owner);
        Assert.Same(a, part.Team);
    }

    [Fact]
    public void AbstractPrimaryOppositesKeepExactLiveAndRecordedEdits()
    {
        var root = new RelationshipSite();
        AbstractTeam a = new ConcreteTeam();
        AbstractTeam b = new ConcreteTeam();
        AbstractPart part = new ConcretePart();
        root.AbstractTeams.AddRange([a, b]);
        a.Parts.Add(part);
        a.Featured.Add(part);
        using var trace = new Trace(root, [(a, "a"), (b, "b"), (part, "part")]);

        part.Owner = b;
        part.Team = b;

        Assert.Empty(a.Parts);
        Assert.Empty(a.Featured);
        Assert.Same(part, Assert.Single(b.Parts));
        Assert.Same(part, Assert.Single(b.Featured));
        Assert.Same(b.AsReadOnly(), part.AsReadOnly().Owner);
        Assert.Same(b.AsReadOnly(), part.AsReadOnly().Team);
        trace.VerifyAndUndo(InheritedLive, InheritedHistory);
        Assert.Same(part, Assert.Single(a.Parts));
        Assert.Same(part, Assert.Single(a.Featured));
        Assert.Empty(b.Parts);
        Assert.Empty(b.Featured);
        Assert.Same(a, part.Owner);
        Assert.Same(a, part.Team);
    }

    [Fact]
    public void NamedGenericOppositesPreserveClosedTypesUndoAndSnapshotIdentity()
    {
        var root = new RelationshipSite();
        var a = new Owner();
        var b = new Owner();
        Owned target = new SpecializedOwned();
        OwnerOf<Owned> owningSlot = a;
        owningSlot.Primary = target;
        owningSlot.Highlighted = target;
        root.Owners.AddRange([a, b]);
        var t1 = new GenericTeam();
        var t2 = new GenericTeam();
        var member = new SpecializedMember();
        root.GenericTeams.AddRange([t1, t2]);
        root.People.Add(member);
        TeamOf<GenericMember> membership = t1;
        membership.Members.Add(member);

        using var trace = new Trace(root, [(a, "a"), (b, "b"), (target, "target"), (t1, "t1"),
            (t2, "t2"), (member, "member")]);
        target.Owner = b;
        target.Selector = b;
        member.Team = t2;

        Assert.Null(a.Primary);
        Assert.Null(a.Highlighted);
        Assert.Same(target, b.Primary);
        Assert.Same(target, b.Highlighted);
        Assert.Same(b, target.Owner);
        Assert.Same(b, target.Selector);
        Assert.Same(b.AsReadOnly(), target.AsReadOnly().Owner);
        Assert.Same(target.AsReadOnly(), b.AsReadOnly().Primary);
        Assert.Empty(t1.Members);
        Assert.Same(member, Assert.Single(t2.Members));
        Assert.Same(t2, member.Team);
        Assert.Equal(typeof(Owned), ModelSnapshot.Reflection.Reflect(b).FindProperty("primary")?.ValueType);
        Assert.Equal("owned_item", ModelSnapshot.Reflection.Reflect(b).FindProperty("primary")?.WireName);
        Assert.Equal(typeof(GenericMember), ModelSnapshot.Reflection.Reflect(t2).FindProperty("members")?.ValueType);

        trace.VerifyAndUndo(
            ["a.primary:Property", "b.primary:Property", "a.highlighted:Property", "target.selector:Property",
             "b.highlighted:Property", "member.team:Property", "t1.members:ReferenceSet", "member.team:Property",
             "t2.members:ReferenceSet"],
            ["a.primary", "b.primary", "target.selector", "t1.members", "member.team"]);
        Assert.Same(target, a.Primary);
        Assert.Same(target, a.Highlighted);
        Assert.Same(a, target.Owner);
        Assert.Same(a, target.Selector);
        Assert.Null(b.Primary);
        Assert.Null(b.Highlighted);
        Assert.Same(t1, member.Team);
        Assert.Same(member, Assert.Single(t1.Members));
        Assert.Empty(t2.Members);

        string snapshot = ModelSnapshot.Write(root.AsReadOnly());
        using var json = JsonDocument.Parse(snapshot);
        Assert.True(json.RootElement.GetProperty("owners")[0].TryGetProperty("owned_item", out _));
        Assert.False(json.RootElement.GetProperty("owners")[0].TryGetProperty("primary", out _));
        var restored = ModelSnapshot.Read<RelationshipSite>(snapshot);
        var restoredOwner = restored.Owners[0];
        var restoredTarget = Assert.IsType<SpecializedOwned>(restoredOwner.Primary);
        Assert.Same(restoredTarget, restoredOwner.Highlighted);
        Assert.Same(restoredOwner, restoredTarget.Owner);
        Assert.Same(restoredOwner, restoredTarget.Selector);
        var restoredMember = Assert.IsType<SpecializedMember>(Assert.Single(restored.People));
        var restoredTeam = restored.GenericTeams[0];
        Assert.Same(restoredMember, Assert.Single(restoredTeam.Members));
        Assert.Same(restoredTeam, restoredMember.Team);
    }

    [Fact]
    public void NativeSubclassesOutsideTheSpecializedEndpointsCannotPartiallyEditTheGraph()
    {
        var root = new RelationshipSite();
        var owner = new Owner();
        var target = new Owned();
        owner.Primary = target;
        owner.Highlighted = target;
        root.Owners.Add(owner);
        var team = new GenericTeam();
        var member = new GenericMember();
        team.Members.Add(member);
        root.GenericTeams.Add(team);
        root.People.Add(member);

        var unboundTarget = new UnboundTarget();
        var unboundOwner = new UnboundOwner();
        var unboundMember = new UnboundMember();
        var unboundTeam = new UnboundTeam();
        var live = new List<ModelChange>();
        using var listener = root.VmfChanges.AddRecursiveListener(live.Add);
        using var targetListener = unboundTarget.VmfChanges.AddLocalListener(live.Add);
        using var ownerListener = unboundOwner.VmfChanges.AddLocalListener(live.Add);
        using var memberListener = unboundMember.VmfChanges.AddLocalListener(live.Add);
        using var teamListener = unboundTeam.VmfChanges.AddLocalListener(live.Add);
        root.VmfChanges.Start();
        try
        {
            Assert.Throws<InvalidOperationException>(() => unboundTarget.Owner = owner);
            Assert.Throws<InvalidOperationException>(() => unboundTarget.Selector = owner);
            Assert.Throws<InvalidOperationException>(() => unboundOwner.Primary = target);
            Assert.Throws<InvalidOperationException>(() => unboundOwner.Highlighted = target);
            Assert.Throws<InvalidOperationException>(() => unboundMember.Team = team);
            Assert.Throws<InvalidOperationException>(() => unboundTeam.Members.Add(member));

            Assert.Same(target, owner.Primary);
            Assert.Same(target, owner.Highlighted);
            Assert.Same(owner, target.Owner);
            Assert.Same(owner, target.Selector);
            Assert.Same(member, Assert.Single(team.Members));
            Assert.Same(team, member.Team);
            Assert.Null(unboundTarget.Owner);
            Assert.Null(unboundTarget.Selector);
            Assert.Null(unboundOwner.Primary);
            Assert.Null(unboundOwner.Highlighted);
            Assert.Null(unboundMember.Team);
            Assert.Empty(unboundTeam.Members);
            Assert.Empty(live);
            Assert.Empty(root.VmfChanges.History);
        }
        finally
        {
            root.VmfChanges.Stop();
        }
    }

    [Fact]
    public void ExistingExactCompanionContainmentSupportsBothOwningShapes()
    {
        ICompanionHolder first = new ConcreteHolder();
        ICompanionHolder second = new ConcreteHolder();
        var listed = new CompanionChild { Holder = first };
        var direct = new CompanionChild { DirectHolder = first };
        Assert.Same(listed, Assert.Single(first.Parts));
        Assert.Same(direct, first.Child);

        listed.Holder = second;
        direct.DirectHolder = second;
        Assert.Empty(first.Parts);
        Assert.Null(first.Child);
        Assert.Same(listed, Assert.Single(second.Parts));
        Assert.Same(direct, second.Child);
        Assert.Same(second, listed.Holder);
        Assert.Same(second, direct.DirectHolder);
    }

    private sealed class UnboundTarget : OwnedTarget;
    private sealed class UnboundOwner : OwnerOf<Owned>;
    private sealed class UnboundMember : MemberBase;
    private sealed class UnboundTeam : TeamOf<GenericMember>;

    private sealed class Trace : IDisposable
    {
        private readonly ModelChanges changes;
        private IDisposable? listening;
        private readonly (object Owner, string Name)[] names;
        private readonly List<string> live = [];

        public Trace(RelationshipSite root, (object Owner, string Name)[] names)
        {
            this.names = names;
            changes = root.VmfChanges;
            listening = changes.AddRecursiveListener(change => live.Add(Key(change) + ":" + change.Kind));
            changes.Start();
            changes.StartTransaction();
        }

        public void VerifyAndUndo(string[] expectedLive, string[] expectedHistory)
        {
            var transaction = changes.PublishTransaction();
            Assert.NotNull(transaction);
            Assert.Equal(expectedLive, live);
            Assert.Equal(expectedHistory, changes.History.Select(Key));
            Dispose();
            Assert.True(transaction.Undo());
        }

        public void Dispose()
        {
            if (listening is null) return;
            listening.Dispose();
            listening = null;
            changes.Stop();
        }

        private string Key(ModelChange change) => names.Single(item => ReferenceEquals(item.Owner, change.Source)).Name
            + "." + change.PropertyName;
    }
}
