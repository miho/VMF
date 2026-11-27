using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Traits;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class KeyedUnorderedOwnershipTests
{
    [Fact]
    public void LookupFollowsKeyChangesAndRejectsCollidingTransfers()
    {
        var first = Key("same");
        var second = Key("same");
        var next = Key("next");
        Assert.Equal(first, second);
        var task = RoleSetTask.NewBuilder().WithToken(first).Build();
        var note = RoleSetNote.NewBuilder().WithToken(second).Build();
        var duplicate = RoleSetNote.NewBuilder().WithToken(first).Build();
        var src = UnorderedKeyedFolder.NewBuilder().WithItems([task]).Build();
        var target = UnorderedKeyedFolder.NewBuilder().WithItems([note]).Build();
        var previous = RoleSetFolder.NewBuilder().WithUnordered([duplicate]).Build();
        var view = target.AsReadOnly();
        int departures = 0;
        using var observer = src.VmfChanges.AddPropertyListener("items", change =>
        {
            Assert.Equal(ModelChangeKind.ReferenceSet, change.Kind);
            Assert.Null(src.FindItemsByToken(first));
            Assert.Same(task, target.FindItemsByToken(first));
            Assert.Throws<ArgumentException>(() => task.Token = second);
            departures++;
        });

        Assert.True(target.Items.Add(task));
        Assert.Equal(1, departures);
        Assert.False(target.Items.Add(task));
        Assert.Empty(src.Items);
        Assert.Same(task, target.FindItemsByToken(first));
        Assert.Same(note, target.FindItemsByToken(second));
        Assert.Same(note.AsReadOnly(), view.FindItemsByToken(second.AsReadOnly()));
        Assert.True(view.Items.Contains(task.AsReadOnly()));
        Assert.Throws<ArgumentException>(() => target.Items.Add(duplicate));
        Assert.Same(duplicate, Assert.Single(previous.Unordered));
        Assert.Throws<ArgumentException>(() => task.Token = second);
        Assert.Throws<ArgumentException>(() => task.Token = null);
        Assert.Same(first, task.Token);
        task.Token = next;
        Assert.Null(target.FindItemsByToken(first));
        Assert.Same(task.AsReadOnly(), view.FindItemsByToken(next.AsReadOnly()));
        Assert.True(target.Items.Remove(task.AsReadOnly()));
        Assert.Null(view.FindItemsByToken(next.AsReadOnly()));
    }

    [Fact]
    public void NativeBuilderRejectsCollidingKeysWhileStaging()
    {
        var key = Key("same");
        var first = RoleSetTask.NewBuilder().WithToken(key).Build();
        var second = RoleSetNote.NewBuilder().WithToken(key).Build();
        var left = UnorderedKeyedFolder.NewBuilder().WithItems([first]).Build();
        var right = UnorderedKeyedFolder.NewBuilder().WithItems([second]).Build();

        Assert.Throws<ArgumentException>(() => UnorderedKeyedFolder.NewBuilder().WithItems([first, second]));
        Assert.Same(first, left.FindItemsByToken(key));
        Assert.Same(second, right.FindItemsByToken(key));
    }

    [Fact]
    public void ApplyToChecksTargetKeysBeforeChangingMembership()
    {
        var first = Key("same token");
        var second = Key("another token");
        var keep = RoleSetTask.NewBuilder().WithToken(first).Build();
        var collision = RoleSetNote.NewBuilder().WithToken(first).Build();
        var added = RoleSetNote.NewBuilder().WithToken(second).Build();
        var target = UnorderedKeyedFolder.NewBuilder().WithItems([keep]).Build();
        var source = UnorderedKeyedFolder.NewBuilder().WithItems([collision, added]).Build();
        Assert.Throws<ArgumentException>(() => UnorderedKeyedFolder.NewBuilder().AppendCollections(true)
            .WithItems([collision, added]).ApplyTo(target));
        Assert.Same(keep, Assert.Single(target.Items));
        Assert.Equal(2, source.Items.Count);

        UnorderedKeyedFolder.NewBuilder().AppendCollections(true).WithItems([added]).ApplyTo(target);
        Assert.Same(keep, target.FindItemsByToken(first));
        Assert.Same(added, target.FindItemsByToken(second));
        Assert.Same(collision, Assert.Single(source.Items));
    }

    [Theory]
    [InlineData("json", false)]
    [InlineData("xml", false)]
    [InlineData("yaml", false)]
    [InlineData("json", true)]
    [InlineData("xml", true)]
    [InlineData("yaml", true)]
    public void SnapshotsExchangeScalarKeysAndHydrateForwardIdentityKeys(string format, bool references)
    {
        var value = BuildSnapshot(references);
        var document = (format, references) switch
        {
            ("json", false) => ModelSnapshot.WriteWithoutReferences(value),
            ("xml", false) => ModelSnapshot.WriteXmlWithoutReferences(value),
            ("yaml", false) => ModelSnapshot.WriteYamlWithoutReferences(value),
            ("json", true) => ModelSnapshot.Write(value),
            ("xml", true) => ModelSnapshot.WriteXml(value),
            ("yaml", true) => ModelSnapshot.WriteYaml(value),
            _ => throw new ArgumentException(nameof(format))
        };
        UnorderedKeyedFolder Read(string input) => (format, references) switch
        {
            ("json", false) => ModelSnapshot.ReadWithoutReferences<UnorderedKeyedFolder>(input),
            ("xml", false) => ModelSnapshot.ReadXmlWithoutReferences<UnorderedKeyedFolder>(input),
            ("yaml", false) => ModelSnapshot.ReadYamlWithoutReferences<UnorderedKeyedFolder>(input),
            ("json", true) => ModelSnapshot.Read<UnorderedKeyedFolder>(input),
            ("xml", true) => ModelSnapshot.ReadXml<UnorderedKeyedFolder>(input),
            ("yaml", true) => ModelSnapshot.ReadYaml<UnorderedKeyedFolder>(input),
            _ => throw new ArgumentException(nameof(format))
        };
        VerifySnapshot(Read(document), references);

        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_TRAITS") is { } exchange)
        {
            string suffix = references ? "reference" : "values";
            string java = File.ReadAllText(Path.Combine(exchange, $"java-keyed-unordered-{suffix}.{format}"));
            if (format == "json") Assert.True(JsonNode.DeepEquals(JsonNode.Parse(document), JsonNode.Parse(java)));
            VerifySnapshot(Read(java), references);
            File.WriteAllText(Path.Combine(exchange, $"csharp-keyed-unordered-{suffix}.{format}"), document);
        }
    }

    [Fact]
    public void ExactTypePlainJsonRestoresScalarKeysAndRejectsDuplicates()
    {
        var folder = new UnorderedKeyedExactFolder();
        folder.Items.Add(UnorderedKeyedExactItem.NewBuilder().WithKey("one").WithMessage("M").Build());
        string plain = ModelSnapshot.WritePlain(folder);
        Assert.DoesNotContain("@vmf-", plain);
        Assert.Equal("M", ModelSnapshot.ReadPlain<UnorderedKeyedExactFolder>(plain).FindItemsByKey("one")?.Message);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_TRAITS") is { } exchange)
        {
            string java = File.ReadAllText(Path.Combine(exchange, "java-keyed-unordered-plain.json"));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(plain), JsonNode.Parse(java)));
            Assert.Equal("M", ModelSnapshot.ReadPlain<UnorderedKeyedExactFolder>(java).FindItemsByKey("one")?.Message);
            File.WriteAllText(Path.Combine(exchange, "csharp-keyed-unordered-plain.json"), plain);
        }
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadPlain<UnorderedKeyedExactFolder>(
            """{"items":[{"key":"same"},{"key":"same"}]}"""));
    }

    [Fact]
    public void ReferencesAreRequiredForPopulatedIdentityKeysAndDuplicateResolvedKeysFail()
    {
        var folder = BuildSnapshot(references: true);
        Assert.Throws<JsonException>(() => ModelSnapshot.WriteWithoutReferences(folder));
        var document = JsonNode.Parse(ModelSnapshot.Write(folder))!;
        document["items"]![1]!["token"] = document["items"]![0]!["token"]!.DeepClone();
        Assert.Throws<JsonException>(() => ModelSnapshot.Read<UnorderedKeyedFolder>(document.ToJsonString()));
    }

    private static RoleKey Key(string code) => RoleKey.NewBuilder().WithCode(code).Build();

    private static UnorderedKeyedFolder BuildSnapshot(bool references)
    {
        var result = new UnorderedKeyedFolder();
        result.Tagged.Add(KeyedTask.NewBuilder().WithKey("one").WithTaskId("S").Build());
        if (!references) return result;
        var first = Key("same");
        var second = Key("same");
        var task = RoleSetTask.NewBuilder().WithTaskId("T").WithToken(first).Build();
        var note = RoleSetNote.NewBuilder().WithMessage("N").WithToken(second).Build();
        result.Items.AddRange([task, note]);
        result.Keys.AddRange([first, second]);
        result.Selected = task;
        return result;
    }

    private static void VerifySnapshot(UnorderedKeyedFolder restored, bool references)
    {
        Assert.Equal("S", Assert.IsType<KeyedTask>(restored.FindTaggedByKey("one")).TaskId);
        if (!references)
        {
            Assert.Empty(restored.Items);
            Assert.Empty(restored.Keys);
            Assert.Null(restored.Selected);
            return;
        }
        var first = restored.Keys[0]!;
        var second = restored.Keys[1]!;
        Assert.NotSame(first, second);
        Assert.Equal(first, second);
        var task = Assert.IsType<RoleSetTask>(restored.FindItemsByToken(first));
        var note = Assert.IsType<RoleSetNote>(restored.FindItemsByToken(second));
        Assert.Equal("T", task.TaskId);
        Assert.Equal("N", note.Message);
        Assert.Same(task, restored.Selected);
        var view = restored.AsReadOnly();
        Assert.Same(task.AsReadOnly(), view.FindItemsByToken(first.AsReadOnly()));
        Assert.Same(note.AsReadOnly(), view.FindItemsByToken(second.AsReadOnly()));
    }
}
