using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Traits;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class GetterRoleOwnershipTests
{
    [Fact]
    public void OwningRoleSlotsTransferTheMutableValuesAndProjectTheirReadOnlyAliases()
    {
        var (folder, task, note) = MoveChildren();
        var projection = folder.AsReadOnly();
        Assert.Same(task.AsReadOnly(), Assert.Single(projection.Items));
        Assert.Same(task.AsReadOnly(), projection.Selected);
        Assert.Same(note.AsReadOnly(), projection.First);
        Assert.False(ModelSnapshot.Reflection.Reflect(projection.Items[0]!).FindProperty("label")!.CanWrite);
        Assert.Empty(folder.Validate());

        var untouched = new Folder();
        Assert.Throws<ArgumentException>(() => untouched.Items.Add(task.AsReadOnly()));
        Assert.Throws<ArgumentException>(() => folder.First = note.AsReadOnly());
        Assert.Empty(untouched.Items);
        Assert.Same(task, Assert.Single(folder.Items));
        Assert.Same(note, folder.First);

        var built = Folder.NewBuilder().WithFirst(note).WithItems([task]).WithSelected(task).Build();
        Assert.Empty(folder.Items);
        Assert.Null(folder.First);
        Assert.Same(task, Assert.Single(built.Items));
        Assert.Same(note, built.First);
        Assert.Same(built.AsReadOnly().Items[0], built.AsReadOnly().Selected);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void GetterRoleOwnershipExchangesConcreteValuesAndReferenceAliases(bool references)
    {
        var (folder, _, _) = MoveChildren();
        string document = references ? ModelSnapshot.Write(folder) : ModelSnapshot.WriteWithoutReferences(folder);
        Folder Read(string input) => references ? ModelSnapshot.Read<Folder>(input)
            : ModelSnapshot.ReadWithoutReferences<Folder>(input);
        Verify(Read(document), references);

        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_TRAITS") is { } exchange)
        {
            string suffix = references ? "reference" : "values";
            string java = File.ReadAllText(Path.Combine(exchange, $"java-owned-getter-role-{suffix}.json"));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(document), JsonNode.Parse(java)));
            Verify(Read(java), references);
            File.WriteAllText(Path.Combine(exchange, $"csharp-owned-getter-role-{suffix}.json"), document);
        }
    }

    [Fact]
    public void StrictPlainAllowsAnEmptyRoleButNeedsTypeMetadataForConcreteChildren()
    {
        var empty = ModelSnapshot.ReadPlain<Folder>(ModelSnapshot.WritePlain(new Folder()));
        Assert.Empty(empty.Items);
        Assert.Null(empty.First);
        Assert.Null(empty.Selected);

        var (folder, _, _) = MoveChildren();
        Assert.Throws<JsonException>(() => ModelSnapshot.WritePlain(folder));
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadPlain<Folder>("""{"items":[{"label":"unknown"}]}"""));
    }

    [Fact]
    public void AGenericOwnerTransfersASpecializedChildWithASecondaryGetterRole()
    {
        var task = TaskItem.NewBuilder().WithLabel("generic").WithCaption("base").WithTaskId("T").Build();
        GenericFolder<TaskItem> source = new TaskFolder();
        var destination = new TaskFolder();
        source.Entries.Add(task);
        destination.Head = task;
        destination.Active = task;
        Assert.Empty(source.Entries);
        Assert.Same(task, destination.Head);
        Assert.Equal("base", ((AssetData)task).Caption);
        Assert.Same(task.AsReadOnly(), destination.AsReadOnly().Head);
        Assert.Same(destination.AsReadOnly().Active, destination.AsReadOnly().Head);
        Assert.Empty(destination.Validate());

        var reference = ModelSnapshot.Read<TaskFolder>(ModelSnapshot.Write(destination));
        Assert.Same(reference.Active, reference.Head);
        Assert.Equal("base", reference.Head!.Caption);
        var byValue = ModelSnapshot.ReadWithoutReferences<TaskFolder>(ModelSnapshot.WriteWithoutReferences(destination));
        Assert.Null(byValue.Active);
        Assert.Equal("T", byValue.Head!.TaskId);
    }


    private static (Folder Folder, TaskItem Task, NoteItem Note) MoveChildren()
    {
        var left = new Folder();
        var right = new Folder();
        var task = TaskItem.NewBuilder().WithLabel("task").WithTaskId("T").Build();
        var note = NoteItem.NewBuilder().WithLabel("note").WithMessage("M").Build();
        left.Items.Add(task);
        left.First = note;
        right.Items.Add(task);
        right.First = note;
        right.Selected = task;
        Assert.Empty(left.Items);
        Assert.Null(left.First);
        Assert.Same(task, Assert.Single(right.Items));
        Assert.Same(note, right.First);
        return (right, task, note);
    }

    private static void Verify(Folder folder, bool references)
    {
        var task = Assert.IsType<TaskItem>(Assert.Single(folder.Items));
        var note = Assert.IsType<NoteItem>(folder.First);
        Assert.Equal("T", task.TaskId);
        Assert.Equal("M", note.Message);
        if (references)
        {
            Assert.Same(task, folder.Selected);
            Assert.Same(folder.AsReadOnly().Items[0], folder.AsReadOnly().Selected);
        }
        else
        {
            Assert.Null(folder.Selected);
        }
    }
}
