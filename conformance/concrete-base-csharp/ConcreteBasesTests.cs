using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Multiplebases;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ConcreteBasesTests
{
    [Fact]
    public void BuildersStageScalarAndCollectionFieldsAcrossModelRoles()
    {
        var header = new CollectionHeader { Sequence = 12 };
        var flat = CollectionHeader.NewBuilder().ApplyFrom(header);
        header.Sequence = 99;
        var targetHeader = new CollectionHeader();
        flat.ApplyTo(targetHeader);
        Assert.Equal(12L, targetHeader.Sequence);

        double negativeZero = BitConverter.Int64BitsToDouble(long.MinValue);
        var source = JoinedCollections.NewBuilder().WithSequence(7).WithLabels(["staged", null]).Build();
        source.Limits["cap"] = 4L;
        source.Limits["optional"] = null;
        source.Levels.Add(negativeZero);
        source.Levels.Add(2.0);
        var builder = JoinedCollections.NewBuilder().ApplyFrom(source);
        source.Sequence = 99;
        source.Labels[0] = "changed";
        source.Limits["cap"] = 99L;
        source.Levels.Remove(2.0);

        var target = JoinedCollections.NewBuilder().WithSequence(1).WithLabels(["existing"]).Build();
        target.Limits["existing"] = 3L;
        target.Levels.Add(0.0);
        var labels = target.Labels;
        var limits = target.Limits;
        var levels = target.Levels;
        builder.ApplyTo(target);
        Assert.Equal(7L, target.Sequence);
        Assert.Equal(new string?[] { "existing", "staged", null }, labels.ToArray());
        Assert.Equal(3, limits.Count);
        Assert.Equal(4L, limits["cap"]);
        Assert.Null(limits["optional"]);
        Assert.True(levels.Contains(negativeZero));
        Assert.True(levels.Contains(0.0));
        Assert.True(levels.Contains(2.0));

        builder.AppendCollections(false).ApplyTo(target);
        Assert.Same(labels, target.Labels);
        Assert.Same(limits, target.Limits);
        Assert.Same(levels, target.Levels);
        Assert.Equal(new string?[] { "staged", null }, labels.ToArray());
        Assert.Equal(2, limits.Count);
        Assert.Equal(2, levels.Count);
        Assert.False(levels.Contains(0.0));

        ICollectionRole role = source;
        var mixed = JoinedCollections.NewBuilder().ApplyFrom(targetHeader).ApplyFrom(role).Build();
        Assert.Equal(12L, mixed.Sequence);
        Assert.Equal(new string?[] { "changed", null }, mixed.Labels.ToArray());
        Assert.Equal(99L, mixed.Limits["cap"]);
    }

    [Fact]
    public void CollectionHistoryAppliesThroughTheWritableSecondaryRole()
    {
        ICollectionRole source = new CollectionRole();
        ICollectionRole target = new JoinedCollections();
        double negativeZero = BitConverter.Int64BitsToDouble(long.MinValue);
        source.Labels.AddRange(["first", "old", "discard"]);
        target.Labels.AddRange(["other", "target-old", "target-discard"]);
        source.Limits["present"] = null;
        target.Limits["present"] = 99L;
        target.Limits["untouched"] = 42L;
        source.Levels.Add(negativeZero);
        target.Levels.Add(negativeZero);
        target.Levels.Add(0.0);
        source.VmfChanges.Start();
        target.VmfChanges.Start();

        source.Labels[1] = "changed";
        source.Labels.RemoveAt(2);
        source.Labels.Insert(1, "inserted");
        source.Limits["present"] = 7L;
        source.Levels.Remove(negativeZero);
        source.Levels.Add(7.0);
        var original = source.VmfChanges.History.ToArray();
        Assert.Equal(["labels", "labels", "labels", "limits", "levels", "levels"], original.Select(edit => edit.PropertyName));
        Assert.All(original, edit => edit.Apply(target));

        var applied = target.VmfChanges.History.Cast<ModelCollectionChange>().ToArray();
        Assert.Equal(["other", "inserted", "changed"], target.Labels);
        Assert.Equal("target-old", Assert.Single(applied[0].Removed).Value);
        Assert.Equal("target-discard", Assert.Single(applied[1].Removed).Value);
        Assert.Equal(99L, Assert.Single(applied[3].Removed).Value);
        Assert.Equal(7L, target.Limits["present"]);
        Assert.Equal(42L, target.Limits["untouched"]);
        Assert.True(target.Levels.Contains(0.0));
        Assert.False(target.Levels.Contains(negativeZero));
        Assert.True(target.Levels.Contains(7.0));
        Assert.All(applied, edit => Assert.Same(target, edit.Source));

        Assert.All(original.Reverse(), edit => Assert.True(edit.Undo()));
        Assert.Equal(["first", "old", "discard"], source.Labels);
        Assert.True(source.Limits.ContainsKey("present"));
        Assert.Null(source.Limits["present"]);
        Assert.True(source.Levels.Contains(negativeZero));
        Assert.Equal(["other", "inserted", "changed"], target.Labels);
        Assert.All(applied, edit => edit.Apply(source));
        Assert.Equal(["first", "inserted", "changed"], source.Labels);
        Assert.Equal(7L, source.Limits["present"]);
        Assert.False(source.Levels.Contains(negativeZero));
        Assert.True(source.Levels.Contains(7.0));
        Assert.Throws<ArgumentException>(() => original[0].Apply(new CollectionHeader()));

        target.Limits["occupied"] = 2L;
        source.Limits["new"] = 8L;
        int historyCount = target.VmfChanges.History.Count;
        Assert.Throws<ArgumentException>(() => source.VmfChanges.History[^1].Apply(target));
        Assert.False(target.Limits.ContainsKey("new"));
        Assert.Equal(historyCount, target.VmfChanges.History.Count);

        source.VmfChanges.Stop();
        target.VmfChanges.Stop();
    }

    [Fact]
    public void ScalarHistoryAppliesToAnotherImplementationOfTheSameSecondaryRole()
    {
        ISecondaryRecord source = new SecondaryRecord();
        ISecondaryRecord target = new JoinedRecord { Weight = 99 };
        source.VmfChanges.Start();
        target.VmfChanges.Start();

        source.Weight = 17;
        var edit = Assert.Single(source.VmfChanges.History);
        Assert.Same(source, edit.Source);
        edit.Apply(target);
        Assert.Equal(17, target.Weight);
        var transferred = Assert.Single(target.VmfChanges.History);
        Assert.Same(target, transferred.Source);
        Assert.Equal(99, transferred.OldValue);

        Assert.True(edit.Undo());
        Assert.Equal(11, source.Weight);
        Assert.Equal(17, target.Weight);
        transferred.Apply(source);
        Assert.Equal(17, source.Weight);
        Assert.Throws<ArgumentException>(() => edit.Apply(new PrimaryRecord()));

        source.Peer = new PrimaryRecord();
        var reference = source.VmfChanges.History[^1];
        Assert.Throws<ArgumentException>(() => reference.Apply(target));
        Assert.Null(target.Peer);
        target.VmfChanges.Stop();
        source.VmfChanges.Stop();
    }

    [Fact]
    public void PrimaryClassAndSecondaryContractShareDefaultsIdentityAndReflection()
    {
        var value = new JoinedRecord();
        PrimaryRecord primary = value;
        ISecondaryRecord secondary = value;
        Assert.Same(primary, secondary);
        Assert.Equal(5, primary.Level);
        Assert.Equal(11, secondary.Weight);

        var reflection = ModelSnapshot.Reflection.Reflect(secondary);
        Assert.Equal(5, reflection.Properties.Count);
        Assert.Equal("shared", reflection.FindProperty("title")!.Get());
        reflection.FindProperty("weight")!.Set(13);
        secondary.Title = "changed";
        secondary.Peer = primary;
        Assert.Equal("changed", primary.Title);
        Assert.Equal(13, value.Weight);
        Assert.Same(value, secondary.Peer);
        Assert.Same(value.AsReadOnly(), secondary.AsReadOnly());
        Assert.Equal(13, secondary.AsReadOnly().Weight);

        var catalog = ModelSnapshot.TypeCatalog;
        var joined = catalog.Find(typeof(JoinedRecord))!;
        var left = catalog.Find(typeof(PrimaryRecord))!;
        var right = catalog.Find(typeof(SecondaryRecord))!;
        Assert.Equal([left.Declaration, right.Declaration], joined.DirectSupertypes);
        Assert.Equal(11, joined.FindProperty("weight")?.DeclaredDefault?.GetInt32());
        Assert.True(catalog.IsSubtypeOf(joined.Declaration, right.Declaration));
        Assert.Equal([typeof(JoinedRecord), typeof(SecondaryRecord)],
            catalog.TypesAssignableTo(typeof(ISecondaryRecord)).Select(type => type.RuntimeType));
        Assert.Same(joined, catalog.TypeOf(secondary.AsReadOnly()));
    }

    [Fact]
    public void GetterOnlyPrimaryAndConcreteSecondaryContractsExposeTheSameCoordinates()
    {
        var point = CoordinateXY.NewBuilder().WithX(3).WithY(4).Build();
        WithXY primary = point;
        ICoordinateX left = point;
        ICoordinateY right = point;
        Assert.Equal(3, left.X);
        Assert.Equal(4, right.Y);
        left.X = 8;
        right.Y = 9;
        Assert.Equal(8, primary.X);
        Assert.Equal(9, primary.Y);
        Assert.Same(point.AsReadOnly(), left.AsReadOnly());
        Assert.Same(point.AsReadOnly(), right.AsReadOnly());
        Assert.Equal(2, ModelSnapshot.Reflection.Reflect(point).Properties.Count);
        Assert.Equal(3, ModelSnapshot.TypeCatalog.TypeOf(point)!.DirectSupertypes.Count);
    }

    [Fact]
    public void SecondaryTypedSnapshotRestoresTheConcreteJoinedRecord()
    {
        var value = JoinedRecord.NewBuilder().WithId("joined").WithWeight(14).Build();
        string wire = ModelSnapshot.WriteWithoutReferences(new RecordEnvelope { Record = value });
        const string Expected = """
            {"record":{"@vmf-type":"eu.mihosoft.vmf.conformance.multiplebases.JoinedRecord","id":"joined","level":5,"title":"shared","weight":14}}
            """;
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(Expected), JsonNode.Parse(wire)));

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_CONCRETE_BASES");
        if (exchange is not null)
        {
            Directory.CreateDirectory(exchange);
            File.WriteAllText(Path.Combine(exchange, "csharp-record.json"), wire);
            wire = File.ReadAllText(Path.Combine(exchange, "java-record.json"));
        }
        var restored = Assert.IsType<JoinedRecord>(ModelSnapshot.ReadWithoutReferences<RecordEnvelope>(wire).Record);
        Assert.Equal(5, restored.Level);
        Assert.Equal(14, ((ISecondaryRecord)restored).Weight);
        Assert.Equal("shared", restored.Title);
    }

    [Fact]
    public void SecondaryUnorderedOwnershipSharesOneSetAcrossMutableAndReadOnlyProjections()
    {
        var source = new JoinedOwner { Id = "source", Title = "header" };
        OwnerHeader primary = source;
        IRecordOwner ownerRole = source;
        IContainmentSet<OwnedRecord> records = ownerRole.Records;
        var child = new OwnedRecord { Name = "same" };
        var peer = new OwnedRecord { Name = "same" };
        var view = ownerRole.AsReadOnly();

        Assert.Same(primary, ownerRole);
        Assert.Same(source.AsReadOnly(), view);
        Assert.Same(source.Records, records);
        Assert.NotSame(child, peer);
        Assert.Equal(child, peer);
        Assert.True(records.Add(child));
        Assert.False(source.Records.Add(child));
        Assert.True(source.Records.Add(peer));
        Assert.Equal(2, records.Count);
        Assert.Same(source, child.Owner);
        Assert.Same(view, child.AsReadOnly().Owner);
        Assert.True(view.Records.Contains(child.AsReadOnly()));
        Assert.False(view.Records is IContainmentSet<OwnedRecord>);

        var excess = new OwnedRecord { Name = "same" };
        Assert.False(view.Records.Contains(excess.AsReadOnly()));
        Assert.True(records.Add(excess));
        Assert.NotEmpty(source.Validate());
        Assert.True(records.Remove(excess));
        Assert.Null(excess.Owner);
        Assert.Empty(source.Validate());

        var target = new RecordOwner();
        child.Owner = target;
        Assert.False(view.Records.Contains(child.AsReadOnly()));
        Assert.True(target.Records.Contains(child));
        Assert.Same(target, child.Owner);
        Assert.True(records.Add(child));
        Assert.Empty(target.Records);
        Assert.Same(source, child.Owner);
        child.Owner = null;
        Assert.Null(child.Owner);
        Assert.False(records.Contains(child));
    }

    [Fact]
    public void SecondaryUnorderedOwnershipCopiesAndExchangesItsInverseAndAlias()
    {
        var child = new OwnedRecord { Name = "same" };
        var peer = new OwnedRecord { Name = "same" };
        var owner = JoinedOwner.NewBuilder().WithId("source").WithTitle("header")
            .WithRecords([peer, child]).Build();
        var graph = new OwnershipEnvelope { Owner = owner, Featured = child };

        var copy = ModelSnapshot.Reflection.DeepCopy(graph);
        AssertOwnedSnapshot(copy);
        Assert.NotSame(owner, copy.Owner);
        Assert.NotSame(child, copy.Featured);

        string wire = ModelSnapshot.Write(graph);
        var wireGraph = JsonNode.Parse(wire)!.AsObject();
        var wireOwner = wireGraph["owner"]!.AsObject();
        Assert.Equal("eu.mihosoft.vmf.conformance.multiplebases.JoinedOwner", (string?)wireOwner["@vmf-type"]);
        Assert.All(wireOwner["records"]!.AsArray(), member => Assert.False(member!.AsObject().ContainsKey("owner")));
        Assert.Equal((string?)wireGraph["featured"], (string?)wireOwner["records"]!.AsArray()
            .Select(member => member!["@vmf-id"]).Single(id => id is not null));

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_CONCRETE_BASES");
        if (exchange is not null)
        {
            Directory.CreateDirectory(exchange);
            File.WriteAllText(Path.Combine(exchange, "csharp-secondary-ownership.json"), wire);
            wire = File.ReadAllText(Path.Combine(exchange, "java-secondary-ownership.json"));
        }
        AssertOwnedSnapshot(ModelSnapshot.Read<OwnershipEnvelope>(wire));
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void SecondaryOwnershipJournalExchangesCreationRemovalAndAnAlias(string format)
    {
        var binding = new JournalBinding("conformance.secondary-ownership", 1, value => value switch
        {
            JoinedOwner owner => owner.Id!,
            OwnedRecord child => child.Name!,
            _ => "envelope",
        });
        var original = new OwnedRecord { Name = "original" };
        var owner = new JoinedOwner { Id = "joined" };
        IRecordOwner role = owner;
        role.Records.Add(original);
        var checkpoint = new OwnershipEnvelope { Owner = owner, Featured = original };
        string snapshot = ModelSnapshot.Write(checkpoint);
        var journal = JournalRecorder<OwnershipEnvelope>.RecordFrame(ModelSnapshot.Read<OwnershipEnvelope>(snapshot),
            ModelSnapshot.Registry, binding, "ownership-base", 0, working =>
            {
                var records = working.Owner!.Records;
                var former = working.Featured!;
                var added = new OwnedRecord { Name = "replacement" };
                Assert.True(records.Add(added));
                working.Featured = added;
                Assert.True(records.Remove(former));
            });

        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) codec = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        string wire = codec.Write(journal);
        Replay(snapshot, wire);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_CONCRETE_BASES") is { } directory)
        {
            string CheckpointPath(string producer) => Path.Combine(directory, $"{producer}-secondary-journal-checkpoint.json");
            string JournalPath(string producer) => Path.Combine(directory, $"{producer}-secondary-ownership-journal.{format}");
            Replay(File.ReadAllText(CheckpointPath("java")), File.ReadAllText(JournalPath("java")));
            File.WriteAllText(CheckpointPath("csharp"), snapshot);
            File.WriteAllText(JournalPath("csharp"), wire);
        }

        void Replay(string source, string records)
        {
            var fresh = ModelSnapshot.Read<OwnershipEnvelope>(source);
            var decoded = codec.Read(records);
            Assert.Equal(1, decoded.WireVersion);
            Assert.Collection(Assert.Single(decoded.Transactions).Operations,
                operation => Assert.Equal("records", Assert.IsType<JournalMembershipEdit>(operation).Property),
                operation => Assert.Equal("featured", Assert.IsType<JournalSet>(operation).Property),
                operation => Assert.Equal("records", Assert.IsType<JournalMembershipEdit>(operation).Property));

            var result = JournalReplayer.Replay(fresh, binding, "ownership-base", decoded, ModelSnapshot.Registry);
            Assert.IsType<JoinedOwner>(result.Owner);
            var resultChild = Assert.Single(result.Owner!.Records);
            Assert.Equal("replacement", resultChild.Name);
            Assert.Same(resultChild, result.Featured);
            Assert.Same(result.Owner, resultChild.Owner);
            Assert.Equal("original", Assert.Single(fresh.Owner!.Records).Name);
            Assert.Same(fresh.Featured, Assert.Single(fresh.Owner.Records));
            Assert.Same(fresh.Owner, fresh.Featured!.Owner);
        }
    }

    private static void AssertOwnedSnapshot(OwnershipEnvelope graph)
    {
        var owner = Assert.IsType<JoinedOwner>(graph.Owner);
        Assert.Equal("source", owner.Id);
        Assert.Equal("header", owner.Title);
        Assert.Equal(2, owner.Records.Count);
        Assert.True(owner.Records.Contains(graph.Featured));
        Assert.Same(owner, graph.Featured!.Owner);
        Assert.All(owner.Records, record =>
        {
            Assert.Same(owner, record.Owner);
            Assert.Equal("same", record.Name);
        });
    }
}
