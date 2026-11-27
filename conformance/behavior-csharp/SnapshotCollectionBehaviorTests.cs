using Eu.Mihosoft.Vmf.Conformance.Behavior;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class SnapshotCollectionBehaviorTests
{
    [Theory]
    [InlineData(false, VmfDocumentFormat.Json, "json")]
    [InlineData(false, VmfDocumentFormat.Yaml, "yaml")]
    [InlineData(false, VmfDocumentFormat.Xml, "xml")]
    [InlineData(true, VmfDocumentFormat.Json, "json")]
    [InlineData(true, VmfDocumentFormat.Yaml, "yaml")]
    [InlineData(true, VmfDocumentFormat.Xml, "xml")]
    public void SnapshotFieldsReplaceManagedHookContentsAndAppendToLegacyLists(bool references, VmfDocumentFormat format,
        string extension)
    {
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR");
        foreach (string state in new[] { "omitted", "empty", "populated" })
        {
            string document = state == "omitted"
                ? Omission(format, "HookedSnapshotCollections") : Write(Specimen(state), references, format);
            Verify(Read<HookedSnapshotCollections>(document, references, format), state);
            if (!references) Verify(Read<HookedSnapshotCollections>(document, references, format, typedValues: true), state);
            if (exchange is null) continue;
            string name = $"hooked-{(references ? "refs" : "plain")}-{state}.{extension}";
            var peer = File.ReadAllText(Path.Combine(exchange, "java-" + name));
            Verify(Read<HookedSnapshotCollections>(peer, references, format), state);
            File.WriteAllText(Path.Combine(exchange, "csharp-" + name), document);
        }

        var defaults = Read<SnapshotCollectionDefaults>(Omission(format, "SnapshotCollectionDefaults"), references, format);
        Assert.Equal([7], defaults.Defaulted);
        var literal = Read<HookedLiteralCollections>(Omission(format, "HookedLiteralCollections"), references, format);
        VerifyLiteralDefaults(literal);
    }

    [Fact]
    public void BuilderReplacesManagedHookSeedsAndKeepsTheOrdinaryList()
    {
        var direct = new HookedSnapshotCollections();
        Assert.Equal([1], direct.Numbers);
        Assert.Equal(["seed"], direct.Ordered);
        Assert.True(direct.Flags.Contains(1));
        Assert.Equal(2, direct.Values.Count);
        Assert.Empty(direct.ObservedMapEdits()!);

        var builder = HookedSnapshotCollections.NewBuilder();
        Verify(builder.Build(), "omitted");
        Verify(builder.AppendCollections(false).WithNumbers([]).WithOrdered([]).WithFlags([])
            .WithValues([]).WithLegacy([]).Build(), "empty");

        builder.WithNumbers([2, 2]).WithOrdered(["last", "first"]).WithFlags([2, 3])
            .WithValues([new("present", null), new("next", 2)]).WithLegacy(["filled"]);
        Verify(builder.Build(), "populated");
        Verify(builder.Build(), "populated");

        var full = HookedSnapshotCollections.NewBuilder().WithNumbers([8, 8, 8])
            .WithOrdered(["x", "y", "z"]).Build();
        Assert.Equal([8, 8, 8], full.Numbers);
        Assert.Equal(["x", "y", "z"], full.Ordered);
        Assert.Empty(full.Values);
        Assert.Equal(["seed"], full.Legacy);

        var target = new HookedSnapshotCollections();
        var transfer = HookedSnapshotCollections.NewBuilder().WithNumbers([2]).WithLegacy(["tail"])
            .WithValues([new("present", null), new("next", 2)]);
        transfer.ApplyTo(target);
        Assert.Equal([1, 2], target.Numbers);
        Assert.Equal(["seed", "tail"], target.Legacy);
        Assert.Equal(">present,next", target.ObservedMapEdits());
        transfer.AppendCollections(false).ApplyTo(target);
        Assert.Equal([2], target.Numbers);
        Assert.Equal(["tail"], target.Legacy);
        Assert.Equal(">present,next|seed-a>|seed-b>", target.ObservedMapEdits());
    }

    [Fact]
    public void LiteralManagedDefaultsReplaceHookSeedsBeforeExplicitSnapshotFields()
    {
        VerifyLiteralDefaults(new HookedLiteralCollections());
        VerifyLiteralDefaults(HookedLiteralCollections.NewBuilder().Build());
        var explicitFields = ModelSnapshot.ReadPlain<HookedLiteralCollections>("""{"numbers":[8],"values":{"present":null}}""");
        Assert.Equal([8], explicitFields.Numbers);
        Assert.Null(Assert.Single(explicitFields.Values).Value);
        Assert.True(explicitFields.Values.ContainsKey("present"));
        Assert.Equal("seed-a>|seed-b>|>saved|saved>|>present", explicitFields.ObservedMapEdits());
    }

    private static void VerifyLiteralDefaults(HookedLiteralCollections value)
    {
        Assert.Equal([7], value.Numbers);
        Assert.Equal(["ready"], value.Ordered);
        Assert.True(value.Flags.Contains(3));
        Assert.Single(value.Flags);
        Assert.Equal(7, Assert.Single(value.Values).Value);
        Assert.Equal(7, value.Values["saved"]);
        Assert.Equal("seed-a>|seed-b>|>saved", value.ObservedMapEdits());
    }

    private static HookedSnapshotCollections Specimen(string state)
    {
        var value = new HookedSnapshotCollections();
        value.Numbers.Clear();
        value.Ordered.Clear();
        value.Flags.Clear();
        value.Values.Clear();
        value.Legacy.Clear();
        if (state == "populated")
        {
            value.Numbers.AddRange([2, 2]);
            value.Ordered.AddRange(["last", "first"]);
            value.Flags.AddRange([2, 3]);
            value.Values.Add("present", null);
            value.Values.Add("next", 2);
            value.Legacy.Add("filled");
        }
        return value;
    }

    private static void Verify(HookedSnapshotCollections restored, string state)
    {
        if (state == "populated")
        {
            Assert.Equal([2, 2], restored.Numbers);
            Assert.Equal(["last", "first"], restored.Ordered);
            Assert.Equal([2, 3], restored.Flags.Order());
            Assert.Equal(["present", "next"], restored.Values.Keys);
            Assert.Null(restored.Values["present"]);
            Assert.Equal(2, restored.Values["next"]);
            Assert.Equal(["seed", "filled"], restored.Legacy);
        }
        else
        {
            Assert.Empty(restored.Numbers);
            Assert.Empty(restored.Ordered);
            Assert.Empty(restored.Flags);
            Assert.Empty(restored.Values);
            Assert.Equal(["seed"], restored.Legacy);
        }
        Assert.Equal(state == "populated" ? "seed-a>|seed-b>|>present,next" : "seed-a>|seed-b>",
            restored.ObservedMapEdits());
        Assert.Equal(1, restored.Invocation());
        Assert.Equal(2, restored.Invocation());
    }

    internal static string Omission(VmfDocumentFormat format, string model) => format == VmfDocumentFormat.Xml
        ? $"<eu.mihosoft.vmf.conformance.behavior.{model}/>" : "{}";

    internal static string Write<T>(T value, bool references, VmfDocumentFormat format) where T : class =>
        (references, format) switch
        {
            (true, VmfDocumentFormat.Xml) => ModelSnapshot.WriteXml(value),
            (true, VmfDocumentFormat.Yaml) => ModelSnapshot.WriteYaml(value),
            (true, _) => ModelSnapshot.Write(value),
            (false, VmfDocumentFormat.Xml) => ModelSnapshot.WritePlainXml(value),
            (false, VmfDocumentFormat.Yaml) => ModelSnapshot.WritePlainYaml(value),
            (false, _) => ModelSnapshot.WritePlain(value),
        };

    internal static T Read<T>(string document, bool references, VmfDocumentFormat format, bool typedValues = false) where T : class =>
        (references, typedValues, format) switch
        {
            (true, _, VmfDocumentFormat.Xml) => ModelSnapshot.ReadXml<T>(document),
            (true, _, VmfDocumentFormat.Yaml) => ModelSnapshot.ReadYaml<T>(document),
            (true, _, _) => ModelSnapshot.Read<T>(document),
            (false, true, VmfDocumentFormat.Xml) => ModelSnapshot.ReadXmlWithoutReferences<T>(document),
            (false, true, VmfDocumentFormat.Yaml) => ModelSnapshot.ReadYamlWithoutReferences<T>(document),
            (false, true, _) => ModelSnapshot.ReadWithoutReferences<T>(document),
            (false, false, VmfDocumentFormat.Xml) => ModelSnapshot.ReadPlainXml<T>(document),
            (false, false, VmfDocumentFormat.Yaml) => ModelSnapshot.ReadPlainYaml<T>(document),
            _ => ModelSnapshot.ReadPlain<T>(document),
        };
}
