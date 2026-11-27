using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Traits;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class GetterRoleSetOwnershipTests
{
    [Fact]
    public void OrderedAndUnorderedRoleSetsTransferAndRecognizeProjectedAliases()
    {
        var source = new RoleSetFolder();
        var destination = new RoleSetFolder();
        var task = RoleSetTask.NewBuilder().WithTaskId("T").Build();
        var note = RoleSetNote.NewBuilder().WithMessage("N").Build();
        var view = destination.AsReadOnly();

        Assert.True(source.Ordered.Add(task));
        Assert.False(source.Ordered.Add(task));
        Assert.True(destination.Unordered.Add(task));
        Assert.False(destination.Unordered.Add(task));
        Assert.Empty(source.Ordered);
        Assert.True(destination.Unordered.Contains(task.AsReadOnly()));
        Assert.True(view.Unordered.Contains(task.AsReadOnly()));

        Assert.True(destination.Ordered.Add(task));
        Assert.True(destination.Ordered.Add(note));
        Assert.Empty(view.Unordered);
        bool containsOrderedAlias = destination.Ordered.Contains(task.AsReadOnly());
        Assert.True(containsOrderedAlias);
        Assert.Equal(0, destination.Ordered.IndexOf(task.AsReadOnly()));
        Assert.Same(task.AsReadOnly(), view.Ordered[0]);
        destination.Ordered.Move(0, 1);
        Assert.Same(note.AsReadOnly(), view.Ordered[0]);
        Assert.Same(task.AsReadOnly(), view.Ordered[1]);
        Assert.True(destination.Ordered.Remove(task.AsReadOnly()));
        Assert.True(destination.Unordered.Add(task));
        Assert.True(destination.Unordered.Remove(task.AsReadOnly()));
        Assert.Empty(view.Unordered);
    }

    [Fact]
    public void IdentityKeyedRoleListsAdmitEqualDistinctKeysAndKeepOwnersAfterRejectedEdits()
    {
        var one = Key("same");
        var two = Key("same");
        var three = Key("other");
        Assert.NotSame(one, two);
        Assert.Equal(one, two);
        var task = RoleSetTask.NewBuilder().WithToken(one).Build();
        var note = RoleSetNote.NewBuilder().WithToken(two).Build();
        var duplicate = RoleSetNote.NewBuilder().WithToken(one).Build();
        var source = new RoleSetFolder();
        var previous = new RoleSetFolder();
        source.Ordered.Add(task);
        previous.Unordered.Add(duplicate);
        var builder = RoleSetFolder.NewBuilder();
        Assert.Throws<ArgumentException>(() => builder.WithIndexed([task, duplicate]));
        Assert.Same(task, Assert.Single(source.Ordered));
        Assert.True(previous.Unordered.Contains(duplicate));

        var destination = builder.WithIndexed([note, task]).Build();
        Assert.Empty(source.Ordered);
        Assert.Same(task, destination.FindIndexedByToken(one));
        Assert.Same(note, destination.FindIndexedByToken(two));
        var view = destination.AsReadOnly();
        Assert.Same(task.AsReadOnly(), view.FindIndexedByToken(one.AsReadOnly()));
        Assert.Same(note.AsReadOnly(), view.FindIndexedByToken(two.AsReadOnly()));
        Assert.Equal(1, destination.Indexed.IndexOf(task.AsReadOnly()));

        Assert.Throws<ArgumentException>(() => destination.Indexed.Add(duplicate));
        Assert.True(previous.Unordered.Contains(duplicate));
        Assert.Throws<ArgumentException>(() => task.Token = two);
        Assert.Throws<ArgumentException>(() => task.Token = null);
        Assert.Same(one, task.Token);

        task.Token = three;
        Assert.Null(destination.FindIndexedByToken(one));
        Assert.Same(task.AsReadOnly(), view.FindIndexedByToken(three.AsReadOnly()));
        Assert.Throws<ArgumentException>(() => ModelSnapshot.Reflection.Reflect(task).FindProperty("token")!.Reset());
        Assert.True(destination.OrderedIndex.Add(task));
        Assert.False(destination.OrderedIndex.Add(task));
        Assert.Null(destination.FindIndexedByToken(three));
        Assert.Same(task.AsReadOnly(), view.FindOrderedIndexByToken(three.AsReadOnly()));
        Assert.True(destination.OrderedIndex.Remove(task.AsReadOnly()));
        ModelSnapshot.Reflection.Reflect(task).FindProperty("token")!.Reset();
        Assert.Null(task.Token);
        Assert.Null(view.FindIndexedByToken(three.AsReadOnly()));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void RoleSetsAndIdentityKeysExchangeGeneratedSubtypes(bool references)
    {
        var orderedFirst = RoleSetTask.NewBuilder().WithTaskId("ordered first").Build();
        var orderedLast = RoleSetNote.NewBuilder().WithMessage("ordered last").Build();
        var unordered = RoleSetNote.NewBuilder().WithMessage("unordered").Build();
        var root = new RoleSetFolder();
        root.Ordered.AddRange([orderedFirst, orderedLast]);
        root.Unordered.Add(unordered);
        if (references)
        {
            var first = Key("same");
            var second = Key("same");
            var third = Key("for ordered index");
            var indexedTask = RoleSetTask.NewBuilder().WithTaskId("indexed task").WithToken(first).Build();
            var indexedNote = RoleSetNote.NewBuilder().WithMessage("indexed note").WithToken(second).Build();
            root.Indexed.AddRange([indexedTask, indexedNote]);
            root.OrderedIndex.Add(RoleSetTask.NewBuilder().WithTaskId("ordered indexed").WithToken(third).Build());
            root.Keys.AddRange([first, second, third]);
            root.Selected = indexedTask;
            Assert.Throws<JsonException>(() => ModelSnapshot.WriteWithoutReferences(root));
        }

        var document = references ? ModelSnapshot.Write(root) : ModelSnapshot.WriteWithoutReferences(root);
        RoleSetFolder Read(string json) => references ? ModelSnapshot.Read<RoleSetFolder>(json)
            : ModelSnapshot.ReadWithoutReferences<RoleSetFolder>(json);
        Verify(Read(document), references);

        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_TRAITS") is { } exchange)
        {
            string suffix = references ? "reference" : "values";
            string java = File.ReadAllText(Path.Combine(exchange, $"java-role-sets-{suffix}.json"));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(document), JsonNode.Parse(java)));
            Verify(Read(java), references);
            File.WriteAllText(Path.Combine(exchange, $"csharp-role-sets-{suffix}.json"), document);
        }
    }

    private static RoleKey Key(string code) => RoleKey.NewBuilder().WithCode(code).Build();

    private static void Verify(RoleSetFolder root, bool references)
    {
        Assert.Equal("ordered first", Assert.IsType<RoleSetTask>(root.Ordered[0]).TaskId);
        Assert.Equal("ordered last", Assert.IsType<RoleSetNote>(root.Ordered[1]).Message);
        Assert.Equal("unordered", Assert.IsType<RoleSetNote>(Assert.Single(root.Unordered)).Message);
        if (!references)
        {
            Assert.Empty(root.Indexed);
            Assert.Empty(root.OrderedIndex);
            Assert.Empty(root.Keys);
            Assert.Null(root.Selected);
            return;
        }

        var first = root.Keys[0]!;
        var second = root.Keys[1]!;
        var third = root.Keys[2]!;
        Assert.NotSame(first, second);
        Assert.Equal(first, second);
        var task = Assert.IsType<RoleSetTask>(root.FindIndexedByToken(first));
        var note = Assert.IsType<RoleSetNote>(root.FindIndexedByToken(second));
        Assert.Equal("indexed task", task.TaskId);
        Assert.Equal("indexed note", note.Message);
        var ordered = Assert.IsType<RoleSetTask>(root.FindOrderedIndexByToken(third));
        Assert.Equal("ordered indexed", ordered.TaskId);
        Assert.Same(ordered.AsReadOnly(), root.AsReadOnly().FindOrderedIndexByToken(third.AsReadOnly()));
        Assert.Same(task, root.Selected);
        Assert.Same(task.AsReadOnly(), root.AsReadOnly().FindIndexedByToken(first.AsReadOnly()));
        Assert.Same(note.AsReadOnly(), root.AsReadOnly().FindIndexedByToken(second.AsReadOnly()));
    }
}
