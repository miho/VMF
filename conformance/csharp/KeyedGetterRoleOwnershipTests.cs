using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Traits;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class KeyedGetterRoleOwnershipTests
{
    [Fact]
    public void LookupFollowsTransfersAndKeyChangesThroughMutableAndProjectedOwners()
    {
        var source = new KeyedFolder();
        var destination = new KeyedFolder();
        var task = KeyedTask.NewBuilder().WithKey("task").WithTaskId("T").Build();
        var note = KeyedNote.NewBuilder().WithKey("note").WithMessage("M").Build();
        var duplicate = KeyedNote.NewBuilder().WithKey("task").Build();
        source.Entries.Add(task);
        destination.Entries.Add(note);
        var other = new KeyedFolder();
        other.Entries.Add(duplicate);
        var view = destination.AsReadOnly();
        Assert.Same(task, source.FindEntriesByKey("task"));
        destination.Entries.Add(task);
        Assert.Null(source.FindEntriesByKey("task"));
        Assert.Same(task, destination.FindEntriesByKey("task"));
        Assert.Same(task.AsReadOnly(), view.FindEntriesByKey("task"));
        Assert.Throws<ArgumentException>(() => destination.Entries.Add(duplicate));
        Assert.Same(duplicate, other.FindEntriesByKey("task"));
        Assert.Throws<ArgumentException>(() => task.Key = "note");
        Assert.Throws<ArgumentException>(() => task.Key = null);
        Assert.Equal("task", task.Key);

        task.Key = "renamed";
        Assert.Null(view.FindEntriesByKey("task"));
        Assert.Same(task.AsReadOnly(), view.FindEntriesByKey("renamed"));
        ModelSnapshot.Reflection.Reflect(task).FindProperty("key")!.Reset();
        Assert.Same(task, destination.FindEntriesByKey("reset"));
        Assert.Null(destination.FindEntriesByKey("renamed"));
        Assert.Throws<ArgumentException>(() => ModelSnapshot.Reflection.Reflect(note).FindProperty("key")!.Reset());
        Assert.Same(note, destination.FindEntriesByKey("note"));
        Assert.True(destination.Entries.Remove(note));
        ModelSnapshot.Reflection.Reflect(note).FindProperty("key")!.Reset();
        Assert.Equal("reset", note.Key);
    }

    [Fact]
    public void AReadOnlyAliasCanFindAndRemoveItsKeyedEntry()
    {
        var folder = new KeyedFolder();
        var task = KeyedTask.NewBuilder().WithKey("task").Build();
        folder.Entries.Add(task);
        var projected = folder.AsReadOnly().FindEntriesByKey("task")!;
        bool foundAlias = folder.Entries.Contains(projected);
        Assert.True(foundAlias);
        Assert.Equal(0, folder.Entries.IndexOf(projected));
        Assert.Contains(projected, folder.AsReadOnly().Entries);
        Assert.True(folder.Entries.Remove(projected));
        Assert.Empty(folder.Entries);
        Assert.Null(folder.FindEntriesByKey("task"));
        Assert.Null(folder.AsReadOnly().FindEntriesByKey("task"));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void KeyedOwningRoleExchangesConcreteSubtypesAndAliases(bool references)
    {
        var task = KeyedTask.NewBuilder().WithTaskId("T").Build();
        var note = KeyedNote.NewBuilder().WithKey("note").WithMessage("M").Build();
        var folder = KeyedFolder.NewBuilder().WithEntries([task, note]).WithSelected(task).Build();
        string document = references ? ModelSnapshot.Write(folder) : ModelSnapshot.WriteWithoutReferences(folder);
        KeyedFolder Read(string input) => references ? ModelSnapshot.Read<KeyedFolder>(input)
            : ModelSnapshot.ReadWithoutReferences<KeyedFolder>(input);
        Verify(Read(document), references);

        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_TRAITS") is { } exchange)
        {
            string suffix = references ? "reference" : "values";
            string java = File.ReadAllText(Path.Combine(exchange, $"java-keyed-getter-role-{suffix}.json"));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(document), JsonNode.Parse(java)));
            Verify(Read(java), references);
            File.WriteAllText(Path.Combine(exchange, $"csharp-keyed-getter-role-{suffix}.json"), document);
        }
    }

    private static void Verify(KeyedFolder folder, bool references)
    {
        var task = Assert.IsType<KeyedTask>(folder.FindEntriesByKey("reset"));
        var note = Assert.IsType<KeyedNote>(folder.FindEntriesByKey("note"));
        Assert.Equal("T", task.TaskId);
        Assert.Equal("M", note.Message);
        Assert.Same(task.AsReadOnly(), folder.AsReadOnly().FindEntriesByKey("reset"));
        Assert.Same(references ? task : null, folder.Selected);
    }
}
