using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ImmutableValueTests
{
    private static readonly string Fixture = Path.Combine(AppContext.BaseDirectory, "fixtures", "immutable-selection.json");

    [Fact]
    public void ImmutableValuesHaveStructuralEqualityAndAnIdentityView()
    {
        var first = new ValueLabel("A");
        var second = new ValueLabel("A");
        Assert.Equal(first, second);
        Assert.Equal(first.GetHashCode(), second.GetHashCode());
        Assert.NotSame(first, second);
        var node = new ValueNode(first);
        var equalContent = new ValueNode(second);
        Assert.NotEqual(node, equalContent);
        Assert.True(node.ContentEquals(equalContent));
        Assert.Same(first, first.AsReadOnly());
        Assert.False(typeof(ValueLabel).GetProperty("Text")!.CanWrite);
        Assert.Equal("A", ModelSnapshot.ReadPlain<ValueLabel>(
            ModelSnapshot.WritePlain(first)).Text);
    }

    [Fact]
    public void ReferenceSnapshotsResolveForwardImmutableAliasesAndPreserveTwins()
    {
        Verify(ModelSnapshot.Read<ValueSelection>(File.ReadAllText(Fixture)));
        string? java = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_JAVA_VALUES");
        if (java is not null)
        {
            Verify(ModelSnapshot.Read<ValueSelection>(File.ReadAllText(java)));
        }

        var shared = new ValueLabel("A");
        var twin = new ValueLabel("A");
        var leaf = new ValueNode(shared);
        var parent = new ValueNode(shared, leaf);
        var selection = new ValueSelection { Current = shared, First = parent, Second = twin };
        selection.Nodes.Add(parent);
        selection.Nodes.Add(leaf);
        selection.Selected.Add(shared);
        selection.Selected.Add(twin);

        var json = ModelSnapshot.Write(selection.AsReadOnly());
        Verify(ModelSnapshot.Read<ValueSelection>(json));
        string? output = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_CSHARP_VALUES");
        if (output is not null)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(output)!);
            File.WriteAllText(output, json);
        }
    }

    [Fact]
    public void BadImmutableIdsAndCyclesAreRejected()
    {
        var fixture = File.ReadAllText(Fixture);
        Assert.Throws<JsonException>(() => ModelSnapshot.Read<ValueSelection>(
            fixture.Replace("\"next\": \"leaf\"", "\"next\": \"parent\"")));
        Assert.Throws<JsonException>(() => ModelSnapshot.Read<ValueSelection>(
            fixture.Replace("\"label\": \"shared\"", "\"label\": \"parent\"")));
        Assert.Throws<JsonException>(() => ModelSnapshot.Read<ValueSelection>(
            fixture.Replace("\"@vmf-id\": \"twin\"", "\"@vmf-id\": \"shared\"")));
    }

    private static void Verify(ValueSelection selection)
    {
        var shared = Assert.IsType<ValueLabel>(selection.Current);
        var twin = Assert.IsType<ValueLabel>(selection.Second);
        var parent = Assert.IsType<ValueNode>(selection.First);
        var leaf = Assert.IsType<ValueNode>(parent.Next);
        Assert.Equal("A", shared.Text);
        Assert.Equal(shared, twin);
        Assert.NotSame(shared, twin);
        Assert.Equal(2, selection.Selected.Count);
        Assert.True(selection.Selected.Contains(shared));
        Assert.True(selection.Selected.Contains(twin));
        Assert.Equal(2, selection.Nodes.Count);
        Assert.True(selection.Nodes.Contains(parent));
        Assert.True(selection.Nodes.Contains(leaf));
        Assert.Same(shared, parent.Label);
        Assert.Same(shared, leaf.Label);
        Assert.Same(shared, selection.AsReadOnly().Current);
    }
}
