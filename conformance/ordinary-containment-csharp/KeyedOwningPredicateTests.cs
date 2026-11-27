using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class KeyedOwningPredicateTests
{
    private const string Checkpoint = "keyed-owning-predicate-0";
    private static readonly JournalBinding Binding = new("keyed-owning-predicate", 1, model => model switch
    {
        ReadingRoom => "room",
        Shelf shelf => shelf.Name!,
        Volume book => book.Name!,
        _ => throw new ArgumentException("Unexpected keyed predicate model.", nameof(model)),
    });

    [Fact]
    public void UndoPreflightsAllKeysAndPreservesValidPerRunCallbacks()
    {
        var room = Fixture();
        var first = room.Shelves[0];
        var kept = room.Shelves[1];
        var last = room.Shelves[2];
        var events = new List<string>();
        using var view = room.AsReadOnly().Shelves.AddListener(edit => events.Add("view:" + Slots(edit)));
        using var writable = room.Shelves.AddListener(edit => events.Add("mutable:" + Slots(edit)));
        room.VmfChanges.Start();
        Assert.Equal(2, room.Shelves.RemoveWhere(shelf => !ReferenceEquals(shelf, kept)));
        room.VmfChanges.Stop();
        var edit = Assert.Single(room.VmfChanges.History, change => change.PropertyName == "shelves");
        var removal = new[] { "view:[0,2]/[]", "mutable:[0,2]/[]" };
        var restoration = new[] { "view:[]/[0]", "mutable:[]/[0]", "view:[]/[2]", "mutable:[]/[2]" };
        Assert.Equal(removal, events);
        Assert.Null(room.FindShelvesByName("first"));
        Assert.Null(room.AsReadOnly().FindShelvesByName("last"));
        events.Clear();
        Assert.True(edit.Undo());
        Assert.Equal(restoration, events);
        Assert.Same(first, room.FindShelvesByName("first"));
        Assert.Same(last.AsReadOnly(), room.AsReadOnly().FindShelvesByName("last"));
        events.Clear();
        Assert.True(edit.Redo());
        Assert.Equal(removal, events);

        kept.Name = "last";
        events.Clear();
        Assert.False(edit.CanUndo);
        Assert.False(edit.Undo());
        Assert.Same(kept, Assert.Single(room.Shelves));
        Assert.Same(kept, room.FindShelvesByName("last"));
        Assert.Empty(events);
        kept.Name = "kept";
        last.Name = "first";
        Assert.False(edit.CanUndo);
        Assert.False(edit.Undo());
        Assert.Same(kept, Assert.Single(room.Shelves));
        Assert.Empty(events);

        last.Name = "last";
        Assert.True(edit.Undo());
        Assert.Equal(restoration, events);
        Assert.Same(first, room.Shelves[0]);
        Assert.Same(last, room.Shelves[2]);
    }

    [Fact]
    public void SparseRemovalIsRecordedBeforeItsNestedObserverEditAcrossFormats()
    {
        var room = Fixture();
        var surviving = room.Shelves[1];
        var feature = Assert.Single(surviving.Volumes);
        int notifications = 0;
        using var watch = room.Shelves.AddListener(edit =>
        {
            Assert.Equal([0, 2], edit.Removed.Select(slot => slot.Index));
            Assert.Same(surviving, Assert.Single(room.Shelves));
            notifications++;
            room.Featured = feature;
        });
        using var recorder = JournalRecorder<ReadingRoom>.Start(room, ModelSnapshot.Registry, Binding, Checkpoint, 0);
        Assert.Equal(2, room.Shelves.RemoveWhere(shelf => !ReferenceEquals(shelf, surviving)));
        Assert.Equal(1, notifications);
        Assert.Same(feature, room.Featured);
        var journal = recorder.Finish();

        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[] formats =
        [
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        ];
        foreach (var (format, write, read) in formats)
        {
            string wire = write(journal);
            Replay(read(wire));
            if (directory is null) continue;
            File.WriteAllText(Path.Combine(directory, $"csharp-keyed-owning-predicate.{format}"), wire);
            var java = read(File.ReadAllText(Path.Combine(directory, $"java-keyed-owning-predicate.{format}")));
            Assert.Equal(JournalJson.Write(journal), JournalJson.Write(java));
            Replay(java);
        }
    }

    private static void Replay(ChangeJournal journal)
    {
        Assert.Equal(1, journal.WireVersion);
        Assert.Collection(Assert.Single(journal.Transactions).Operations, operation =>
        {
            var edit = Assert.IsType<JournalListEdit>(operation);
            Assert.Equal("shelves", edit.Property);
            Assert.Equal(JournalListEditKind.Remove, edit.Kind);
            Assert.Equal([0, 2], edit.Indices);
        }, operation =>
        {
            var edit = Assert.IsType<JournalSet>(operation);
            Assert.Equal("featured", edit.Property);
        });
        var original = Fixture();
        var replay = JournalReplayer.Replay(original, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        var survivor = Assert.Single(replay.Shelves);
        Assert.Same(survivor, replay.FindShelvesByName("kept"));
        Assert.Same(replay.Featured, Assert.Single(survivor.Volumes));
        Assert.Same(survivor, replay.Featured!.Shelf);
        Assert.Equal(3, original.Shelves.Count);
        Assert.Null(original.Featured);
    }

    private static ReadingRoom Fixture()
    {
        var first = new Shelf { Name = "first" };
        var kept = new Shelf { Name = "kept" };
        var last = new Shelf { Name = "last" };
        kept.Volumes.Add(new Volume { Name = "volume" });
        var room = new ReadingRoom();
        room.Shelves.AddRange([first, kept, last]);
        return room;
    }

    private static string Slots<T>(ModelListChange<T> edit) =>
        $"[{string.Join(',', edit.Removed.Select(slot => slot.Index))}]/[{string.Join(',', edit.Added.Select(slot => slot.Index))}]";
}
