using Eu.Mihosoft.Vmf.Conformance.Traits;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class KeyedUnorderedJournalTests
{
    private static readonly JournalBinding Binding = new("vmf-test.keyed-unordered", 1, value => value switch
    {
        KeyedUnorderedHistory => "root",
        NamedKeyedUnorderedFolder folder => folder.Name!,
        RoleKey key => "key-" + key.Code,
        RoleSetTask task => "task-" + task.TaskId,
        RoleSetNote note => "note-" + note.Message,
        _ => throw new ArgumentException("Unrecognized journal identity.", nameof(value)),
    });

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void V1ReplaysTransferRekeyAndCreationAcrossKeyedUnorderedOwners(bool fromCallback)
    {
        var root = Initial();
        var checkpoint = ModelSnapshot.Write(root);
        var task = Assert.IsType<RoleSetTask>(root.Selected);
        var first = root.Keys[0]!;
        var next = root.Keys[2]!;
        var left = root.Left!;
        var right = root.Right!;
        using var observer = fromCallback ? left.VmfChanges.AddPropertyListener("items", edit =>
        {
            if (edit is not ModelCollectionChange collection ||
                !collection.Removed.Any(entry => ReferenceEquals(entry.Value, task))) return;
            Assert.Null(left.FindItemsByToken(first));
            Assert.Same(task, right.FindItemsByToken(first));
            Assert.Throws<ArgumentException>(() => task.Token = root.Keys[1]!);
            Assert.Same(first, task.Token);
            task.Token = next;
            Assert.Same(task, right.FindItemsByToken(next));
        }) : null;
        using var recorder = JournalRecorder<KeyedUnorderedHistory>.Start(root, ModelSnapshot.Registry, Binding, "initial", 0);
        Assert.True(right.Items.Add(task));
        if (!fromCallback) task.Token = next;
        Assert.Same(next, task.Token);
        var created = RoleSetNote.NewBuilder().WithMessage("new").WithToken(root.Keys[0]).Build();
        Assert.True(left.Items.Add(created));
        root.Selected = created;
        var journal = recorder.Finish();

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_TRAITS");
        string? javaCheckpoint = null;
        if (exchange is not null)
        {
            javaCheckpoint = File.ReadAllText(Path.Combine(exchange, "java-keyed-unordered-journal-checkpoint.json"));
            if (!fromCallback)
                File.WriteAllText(Path.Combine(exchange, "csharp-keyed-unordered-journal-checkpoint.json"), checkpoint);
        }

        VerifyFormat("json", JournalJson.Write, JournalJson.Read);
        VerifyFormat("xml", JournalXml.Write, JournalXml.Read);
        VerifyFormat("yaml", JournalYaml.Write, JournalYaml.Read);

        void VerifyFormat(string extension, Func<ChangeJournal, string> write, Func<string, ChangeJournal> read)
        {
            string document = write(journal);
            Verify(ModelSnapshot.Read<KeyedUnorderedHistory>(checkpoint), read(document), fromCallback);
            if (exchange is null) return;
            string suffix = fromCallback ? "-reentrant" : "";
            string name = $"keyed-unordered-journal{suffix}.{extension}";
            Verify(ModelSnapshot.Read<KeyedUnorderedHistory>(javaCheckpoint!),
                read(File.ReadAllText(Path.Combine(exchange, "java-" + name))), fromCallback);
            File.WriteAllText(Path.Combine(exchange, "csharp-" + name), document);
        }
    }

    private static KeyedUnorderedHistory Initial()
    {
        var first = new RoleKey { Code = "first" };
        var second = new RoleKey { Code = "second" };
        var next = new RoleKey { Code = "next" };
        var task = RoleSetTask.NewBuilder().WithTaskId("moving").WithToken(first).Build();
        var note = RoleSetNote.NewBuilder().WithMessage("right").WithToken(second).Build();
        var root = new KeyedUnorderedHistory
        {
            Left = NamedKeyedUnorderedFolder.NewBuilder().WithName("left").WithItems([task]).Build(),
            Right = NamedKeyedUnorderedFolder.NewBuilder().WithName("right").WithItems([note]).Build(),
            Selected = task,
        };
        root.Keys.AddRange([first, second, next]);
        return root;
    }

    private static void Verify(KeyedUnorderedHistory checkpoint, ChangeJournal journal, bool fromCallback)
    {
        Assert.Equal(1, journal.WireVersion);
        var edits = Assert.Single(journal.Transactions).Operations;
        string[] expected = fromCallback ? ["items", "token", "items", "items", "selected"]
            : ["items", "items", "token", "items", "selected"];
        Assert.Equal(expected, edits.Select(edit => edit.Property));
        var removal = Assert.IsType<JournalMembershipEdit>(edits[0]);
        var arrival = Assert.IsType<JournalMembershipEdit>(edits[fromCallback ? 2 : 1]);
        var creation = Assert.IsType<JournalMembershipEdit>(edits[3]);
        Assert.All(new[] { removal, arrival, creation }, edit => Assert.True(edit.References));
        Assert.Equal("task-moving", Assert.Single(removal.Removed).Text);
        Assert.Equal("task-moving", Assert.Single(arrival.Added).Text);
        Assert.IsType<JournalSet>(edits[fromCallback ? 1 : 2]);
        Assert.IsType<JournalSet>(edits[4]);
        Assert.Equal("note-new", Assert.Single(creation.Created).Id);

        var restored = JournalReplayer.Replay(checkpoint, Binding, "initial", journal, ModelSnapshot.Registry);
        var task = Assert.IsType<RoleSetTask>(restored.Right!.FindItemsByToken(restored.Keys[2]!));
        var note = Assert.IsType<RoleSetNote>(restored.Left!.FindItemsByToken(restored.Keys[0]!));
        Assert.Equal("moving", task.TaskId);
        Assert.Equal("new", note.Message);
        Assert.Same(note, restored.Selected);
        Assert.Null(restored.Right.FindItemsByToken(restored.Keys[0]!));
        Assert.Same(task.AsReadOnly(), restored.Right.AsReadOnly().FindItemsByToken(restored.Keys[2]!.AsReadOnly()));
        Assert.Equal(2, restored.Right.Items.Count);
        Assert.Single(restored.Left.Items);

        Assert.Same(checkpoint.Selected, checkpoint.Left!.FindItemsByToken(checkpoint.Keys[0]!));
        Assert.Single(checkpoint.Right!.Items);
        Assert.Null(checkpoint.Right.FindItemsByToken(checkpoint.Keys[2]!));
    }
}
