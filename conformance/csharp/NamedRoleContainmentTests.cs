using Eu.Mihosoft.Vmf.Conformance.Traits;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class NamedRoleContainmentTests
{
    [Fact]
    public void NamedInversesTransferTheSameMutableRoleBetweenAllOwnershipShapes()
    {
        var left = new NamedRoleFolder();
        var right = new NamedRoleFolder();
        var note = NamedOwnedNote.NewBuilder().WithLabel("note").Build();
        var task = NamedOwnedTask.NewBuilder().WithTaskNumber(71).Build();
        left.Single = note;
        Assert.Same(left, note.SingleOwner);
        Assert.Same(left.AsReadOnly(), left.AsReadOnly().Single!.SingleOwner);

        note.OrderedOwner = right;
        Assert.Null(left.Single);
        Assert.Null(note.SingleOwner);
        Assert.Same(note, Assert.Single(right.Ordered));

        right.Ordinary.Add(note);
        Assert.Empty(right.Ordered);
        Assert.Null(note.OrderedOwner);
        Assert.Same(right, note.OrdinaryOwner);

        note.UnorderedOwner = left;
        Assert.Empty(right.Ordinary);
        Assert.Null(note.OrdinaryOwner);
        Assert.Same(left, note.UnorderedOwner);
        Assert.True(left.Unordered.Contains(note.AsReadOnly()));

        task.SingleOwner = right;
        Assert.Same(task, right.Single);
        Assert.Same(right, task.SingleOwner);
        task.SingleOwner = null;
        Assert.Null(right.Single);
        note.UnorderedOwner = null;
        Assert.Empty(left.Unordered);
    }

    [Theory]
    [InlineData("json", false)]
    [InlineData("json", true)]
    [InlineData("xml", false)]
    [InlineData("xml", true)]
    [InlineData("yaml", false)]
    [InlineData("yaml", true)]
    public void PortableSnapshotsRestoreConcreteRoleTypesAndNamedInverses(string format, bool references)
    {
        var built = new NamedRoleFolder();
        var single = NamedOwnedTask.NewBuilder().WithLabel("single").WithTaskNumber(71).Build();
        built.Single = single;
        built.Ordinary.Add(NamedOwnedNote.NewBuilder().WithLabel("ordinary").WithNote("L").Build());
        built.Ordered.Add(NamedOwnedTask.NewBuilder().WithLabel("ordered").WithTaskNumber(72).Build());
        built.Unordered.Add(NamedOwnedNote.NewBuilder().WithLabel("unordered").WithNote("S").Build());
        built.Selected = single;

        string Write() => (format, references) switch
        {
            ("json", false) => ModelSnapshot.WriteWithoutReferences(built, global::Vmf.Runtime.ReferenceMode.Omit),
            ("json", true) => ModelSnapshot.Write(built),
            ("xml", false) => ModelSnapshot.WriteXmlWithoutReferences(built, global::Vmf.Runtime.ReferenceMode.Omit),
            ("xml", true) => ModelSnapshot.WriteXml(built),
            ("yaml", false) => ModelSnapshot.WriteYamlWithoutReferences(built, global::Vmf.Runtime.ReferenceMode.Omit),
            ("yaml", true) => ModelSnapshot.WriteYaml(built),
            _ => throw new ArgumentException(nameof(format))
        };
        NamedRoleFolder Read(string document) => (format, references) switch
        {
            ("json", false) => ModelSnapshot.ReadWithoutReferences<NamedRoleFolder>(document),
            ("json", true) => ModelSnapshot.Read<NamedRoleFolder>(document),
            ("xml", false) => ModelSnapshot.ReadXmlWithoutReferences<NamedRoleFolder>(document),
            ("xml", true) => ModelSnapshot.ReadXml<NamedRoleFolder>(document),
            ("yaml", false) => ModelSnapshot.ReadYamlWithoutReferences<NamedRoleFolder>(document),
            ("yaml", true) => ModelSnapshot.ReadYaml<NamedRoleFolder>(document),
            _ => throw new ArgumentException(nameof(format))
        };
        string output = Write();
        Verify(Read(output), references);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_TRAITS") is { } exchange)
        {
            string filename = $"named-role-ownership-{(references ? "reference" : "values")}.{format}";
            Verify(Read(File.ReadAllText(Path.Combine(exchange, "java-" + filename))), references);
            File.WriteAllText(Path.Combine(exchange, "csharp-" + filename), output);
        }
    }

    private static void Verify(NamedRoleFolder folder, bool references)
    {
        var single = Assert.IsType<NamedOwnedTask>(folder.Single);
        var ordinary = Assert.IsType<NamedOwnedNote>(Assert.Single(folder.Ordinary));
        var ordered = Assert.IsType<NamedOwnedTask>(Assert.Single(folder.Ordered));
        var unordered = Assert.IsType<NamedOwnedNote>(Assert.Single(folder.Unordered));
        Assert.Equal(71, single.TaskNumber);
        Assert.Equal("L", ordinary.Note);
        Assert.Equal(72, ordered.TaskNumber);
        Assert.Equal("S", unordered.Note);
        Assert.Same(folder, single.SingleOwner);
        Assert.Same(folder, ordinary.OrdinaryOwner);
        Assert.Same(folder, ordered.OrderedOwner);
        Assert.Same(folder, unordered.UnorderedOwner);
        Assert.Same(folder.AsReadOnly(), folder.AsReadOnly().Single!.SingleOwner);
        Assert.Same(references ? single : null, folder.Selected);
    }
}
