using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class JournalBindingTests
{
    private const string Checkpoint = "application-start";
    private static readonly JournalBinding Binding = new JournalBinding("conformance.application-keys", 1, Id, Key)
        .WithPropertyName(Name)
        .WithObjectTypes(Type, Create, static (type, id, initialize) =>
        {
            var value = Assert.IsType<ValueLabel>(initialize());
            Assert.Equal("label/v1", type);
            Assert.Equal(id, value.Text);
            return value;
        });

    [Fact]
    public void ApplicationJournalTypesAndPropertyKeysRoundTripAcrossFormats()
    {
        var archive = ArchiveRoot.NewBuilder().WithHead(new ArchiveNode { Name = "main" }).Build();
        var mutable = MakeJournal(archive, static model =>
        {
            var child = new ArchiveNode { Name = "new-item" };
            model.Head!.Children.Add(child);
            model.Head.Anchor = child;
        });

        var baseline = new ValueLabel("baseline");
        var selection = ValueSelection.NewBuilder().WithCurrent(baseline).WithSecond(baseline).Build();
        var immutable = MakeJournal(selection, static model =>
        {
            var value = new ValueLabel("created");
            model.Current = value;
            model.Second = value;
        });
        Assert.Equal("contents", mutable.Journal.Transactions[0].Operations[0].Property);
        var child = Assert.Single(mutable.Journal.Transactions[0].Operations[0].Created);
        Assert.Equal("entry/v1", child.Type);
        Assert.Contains("code", child.Properties.Keys);
        Assert.Equal("selected", immutable.Journal.Transactions[0].Operations[0].Property);
        var label = Assert.Single(immutable.Journal.Transactions[0].Operations[0].Created);
        Assert.Equal("label/v1", label.Type);
        Assert.Contains("caption", label.Properties.Keys);
        Exchange("application-mutable", mutable.Checkpoint, mutable.Journal, VerifyMutable);
        Exchange("application-immutable", immutable.Checkpoint, immutable.Journal, VerifyImmutable);
    }

    [Fact]
    public void CheckpointCopiesKeepExternallyIdentifiedImmutableAliasesDuringHistorySeek()
    {
        var left = new ValueLabel("same");
        var right = new ValueLabel("same");
        var selection = ValueSelection.NewBuilder().WithCurrent(left).WithSecond(left).WithSelected([right]).Build();
        string checkpoint = ModelSnapshot.Write(selection);
        var binding = CheckpointIdentityBinding(selection);
        using var recorder = JournalRecorder<ValueSelection>.Start(selection, ModelSnapshot.Registry, binding, Checkpoint, 20);
        selection.Current = right;
        recorder.PublishFrame();
        selection.Second = right;
        var journal = recorder.Finish();
        Exchange("application-checkpoint-identity", checkpoint, journal, VerifyCheckpointIdentities);
    }

    [Fact]
    public void InvalidAliasesAndFactoryInstancesLeaveTheCheckpointUntouched()
    {
        var archive = ArchiveRoot.NewBuilder().WithHead(new ArchiveNode { Name = "main" }).Build();
        var (checkpoint, journal) = MakeJournal(archive, static model =>
            model.Head!.Children.Add(new ArchiveNode { Name = "new-item" }));
        var fresh = ModelSnapshot.Read<ArchiveRoot>(checkpoint);
        var wire = JsonNode.Parse(JournalJson.Write(journal))!;
        wire["transactions"]![0]!["operations"]![0]!["property"] = "old-contents";
        var stale = JournalJson.Read(wire.ToJsonString());
        Assert.Contains("Invalid journal property", Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(fresh, Binding, Checkpoint, stale, ModelSnapshot.Registry)).Message);

        CheckFactory((_, _) => fresh.Head!, "reused");
        CheckFactory(static (_, _) => new ArchiveNode { Name = "wrong" }, "wrong ID or type");
        CheckFactory(static (_, _) => new ArchiveRoot(), "wrong ID or type");
        CheckFactory(static (_, _) => new object(), "Unknown journal creation type");
        Assert.Equal(checkpoint, ModelSnapshot.Write(fresh));

        void CheckFactory(Func<string, string, object> factory, string reason) =>
            Assert.Contains(reason, Assert.Throws<InvalidOperationException>(() =>
                JournalReplayer.Replay(fresh, Binding.WithObjectTypes(Type, factory), Checkpoint, journal, ModelSnapshot.Registry)).Message);
    }

    private static (string Checkpoint, ChangeJournal Journal) MakeJournal<T>(T model, Action<T> edit) where T : class
    {
        string checkpoint = ModelSnapshot.Write(model);
        var journal = JournalRecorder<T>.RecordFrame(model, ModelSnapshot.Registry, Binding, Checkpoint, 0, edit);
        Assert.Equal(1, journal.WireVersion);
        return (checkpoint, journal);
    }

    private static void VerifyMutable(string snapshot, ChangeJournal journal)
    {
        var input = ModelSnapshot.Read<ArchiveRoot>(snapshot);
        var result = JournalReplayer.Replay(input, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        var head = Assert.IsType<ArchiveNode>(result.Head);
        var child = Assert.Single(head.Children);
        Assert.Equal("new-item", child.Name);
        Assert.Same(head, child.Parent);
        Assert.Same(child, head.Anchor);
        Assert.Empty(input.Head!.Children);
    }

    private static void VerifyImmutable(string snapshot, ChangeJournal journal)
    {
        var input = ModelSnapshot.Read<ValueSelection>(snapshot);
        var result = JournalReplayer.Replay(input, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        Assert.Equal("created", result.Current?.Text);
        Assert.Same(result.Current, result.Second);
        Assert.Equal("baseline", input.Current?.Text);
    }

    private static JournalBinding CheckpointIdentityBinding(ValueSelection selection)
    {
        var left = Assert.IsType<ValueLabel>(selection.Current);
        var right = Assert.Single(selection.Selected);
        Assert.Same(left, selection.Second);
        Assert.NotSame(left, right);
        Assert.Equal(left, right);
        var table = new Dictionary<object, string>(ReferenceEqualityComparer.Instance)
        {
            [left] = "stable-left",
            [right] = "stable-right",
        };
        return new JournalBinding("conformance.checkpoint-identity", 1, instance => instance is ValueSelection
            ? "selection" : table[instance]);
    }

    private static void VerifyCheckpointIdentities(string snapshot, ChangeJournal journal)
    {
        var input = ModelSnapshot.Read<ValueSelection>(snapshot);
        var binding = CheckpointIdentityBinding(input);
        var left = input.Current!;
        var right = Assert.Single(input.Selected);
        Assert.Equal(1, journal.WireVersion);
        Assert.Collection(journal.Transactions, first => VerifyEdit(first, "current"), second => VerifyEdit(second, "second"));

        var history = new JournalHistory<ValueSelection>(binding, ModelSnapshot.Registry);
        history.AddSegment(Checkpoint, input, journal);
        var before = history.Seek(20);
        var midway = history.Seek(21);
        var after = history.Seek(22);
        Assert.NotSame(input, before);
        Assert.NotSame(before, midway);
        Assert.NotSame(midway, after);
        Assert.Same(left, before.Current);
        Assert.Same(left, before.Second);
        Assert.Same(right, midway.Current);
        Assert.Same(left, midway.Second);
        Assert.Same(right, after.Current);
        Assert.Same(right, after.Second);
        Assert.Same(right, Assert.Single(after.Selected));

        var replayed = JournalReplayer.Replay(input, binding, Checkpoint, journal, ModelSnapshot.Registry);
        Assert.Same(right, replayed.Current);
        Assert.Same(replayed.Current, replayed.Second);
        midway.Current = left;
        Assert.Same(right, history.Seek(21).Current);
        Assert.Same(left, input.Current);
        Assert.Same(left, input.Second);

        static void VerifyEdit(JournalFrame frame, string property)
        {
            var edit = Assert.IsType<JournalSet>(Assert.Single(frame.Operations));
            Assert.Equal(property, edit.Property);
            Assert.Equal(("IMMUTABLE_REFERENCE", "stable-left"), (edit.Before.Kind, edit.Before.Text));
            Assert.Equal(("IMMUTABLE_REFERENCE", "stable-right"), (edit.After.Kind, edit.After.Text));
            Assert.Empty(edit.Created);
        }
    }

    private static void Exchange(string fixture, string checkpoint, ChangeJournal journal, Action<string, ChangeJournal> verify)
    {
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_CREATION_JOURNAL");
        if (exchange is not null)
            File.WriteAllText(Path.Combine(exchange, $"csharp-{fixture}-checkpoint.json"), checkpoint);

        CheckFormat("json", JournalJson.Write, JournalJson.Read);
        CheckFormat("xml", JournalXml.Write, JournalXml.Read);
        CheckFormat("yaml", JournalYaml.Write, JournalYaml.Read);

        void CheckFormat(string format, Func<ChangeJournal, string> write, Func<string, ChangeJournal> read)
        {
            string wire = write(journal);
            verify(checkpoint, read(wire));
            if (exchange is null) return;
            string original = File.ReadAllText(Path.Combine(exchange, $"java-{fixture}-checkpoint.json"));
            var fromJava = read(File.ReadAllText(Path.Combine(exchange, $"java-{fixture}-journal.{format}")));
            verify(original, fromJava);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(journal)), JsonNode.Parse(JournalJson.Write(fromJava))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-{fixture}-journal.{format}"), wire);
        }
    }

    private static string Id(object instance) => instance switch
    {
        ArchiveRoot or ValueSelection => "root",
        ArchiveNode { Name: { } name } => name,
        ValueLabel { Text: { } text } => text,
        _ => throw new ArgumentException("Missing object ID."),
    };

    private static string Type(object instance) => instance switch
    {
        ArchiveRoot => "archive/v1",
        ArchiveNode => "entry/v1",
        ValueSelection => "selection/v1",
        ValueLabel => "label/v1",
        _ => throw new ArgumentException("Unknown application type."),
    };

    private static object Create(string type, string id) => type switch
    {
        "entry/v1" => new ArchiveNode { Name = id },
        "label/v1" => new ValueLabel("prototype"),
        _ => throw new InvalidOperationException($"Unknown creation type '{type}'."),
    };

    private static string Key(object instance, string name) => (instance, name) switch
    {
        (ArchiveNode, "children") => "contents",
        (ArchiveNode, "name") => "code",
        (ValueLabel, "text") => "caption",
        (ValueSelection, "current") => "selected",
        _ => name,
    };

    private static string Name(object instance, string key) => (instance, key) switch
    {
        (ArchiveNode, "contents" or "old-contents") => "children",
        (ArchiveNode, "code") => "name",
        (ValueLabel, "caption") => "text",
        (ValueSelection, "selected") => "current",
        _ => key,
    };
}
