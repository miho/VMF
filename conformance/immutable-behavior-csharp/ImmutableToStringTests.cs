using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Immutablebehavior;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ImmutableToStringTests
{
    [Fact]
    public void DelegatedObjectOverridesShareLazyBindingsAndPreserveNull()
    {
        ImmutableToStringBindings.NullCreated = ImmutableToStringBindings.TextCreated
            = ImmutableToStringBindings.InheritedCreated = 0;
        var plain = new PlainSummary("plain-data");
        var standalone = new StandaloneSummary("standalone-data");
        var empty = new NullSummary("still-data");
        var text = new TextSummary("alpha");
        var derived = new InheritedSummary("beta");
        Assert.Equal((0, 0, 0), Counts());

        Assert.Equal(ModelSnapshot.Reflection.FormatSummary(plain), plain.ToString());
        string nonNullableSummary = standalone.ToString();
        Assert.Equal(ModelSnapshot.Reflection.FormatSummary(standalone), nonNullableSummary);
        Assert.Null(((object)empty).ToString());
        Assert.Null(((PlainSummary)empty).ToString());
        Assert.Null(empty.AsReadOnly().ToString());
        Assert.Null(typeof(ITextSummaryCalls).GetMethod(nameof(ToString)));
        Assert.DoesNotContain(typeof(NullSummary).GetInterfaces(), type => type.Name == "INullSummaryCalls");

        Assert.Equal("alpha:1", ((object)text).ToString());
        Assert.Equal(1, ((ITextSummaryCalls)text).Calls());
        Assert.Equal("alpha:2", text.AsReadOnly().ToString());
        Assert.Equal("beta:1", ((object)derived).ToString());
        Assert.Equal(1, ((ITextSummaryCalls)derived).Calls());
        Assert.Equal((1, 1, 1), Counts());

        VerifySnapshots(references: false);
        VerifySnapshots(references: true);
    }

    private static void VerifySnapshots(bool references)
    {
        var value = new TextSummary("snapshot");
        int previousBindings = ImmutableToStringBindings.TextCreated;
        string Write(TextSummary root) => references
            ? ModelSnapshot.Write(root)
            : ModelSnapshot.WriteWithoutReferences(root);
        string before = Write(value);
        using var document = JsonDocument.Parse(before);
        Assert.Equal("snapshot", document.RootElement.GetProperty("label").GetString());
        Assert.False(document.RootElement.TryGetProperty("calls", out _));
        Assert.Equal(previousBindings, ImmutableToStringBindings.TextCreated);
        Assert.Equal("snapshot:1", value.ToString());
        Assert.Equal(before, Write(value));

        var loaded = ModelSnapshot.Read<TextSummary>(before);
        Assert.Equal(previousBindings + 1, ImmutableToStringBindings.TextCreated);
        Assert.Equal("snapshot:1", loaded.ToString());
        Assert.Equal(previousBindings + 2, ImmutableToStringBindings.TextCreated);
    }

    private static (int Null, int Text, int Inherited) Counts() =>
        (ImmutableToStringBindings.NullCreated, ImmutableToStringBindings.TextCreated,
            ImmutableToStringBindings.InheritedCreated);
}
