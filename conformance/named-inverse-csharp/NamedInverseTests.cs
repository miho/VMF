using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Namedinverse;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class NamedInverseTests
{
    private const string Checkpoint = "named-inverse-6";
    private static readonly JournalBinding Binding = new("conformance.named-inverse", 1, model => model switch
    {
        Grammar => "grammar",
        Rule { Name: { } name } => name,
        Member { Name: { } name } => name,
        _ => throw new ArgumentException("Unknown named-inverse object")
    });

    [Fact]
    public void AlternateSlotUsesOneWritableParentAndTheDeclaredCanonicalSetter()
    {
        var first = new Rule { Name = "first" };
        var second = new Rule { Name = "second" };
        var member = new Member { Name = "member" };
        var trace = new List<string>();
        using var canonical = first.VmfChanges.AddPropertyListener("properties", change => Trace(trace, "canonical", change));
        using var alternate = first.VmfChanges.AddPropertyListener("customProperties", change => Trace(trace, "custom", change));
        using var destination = second.VmfChanges.AddPropertyListener("properties", change => Trace(trace, "new", change));
        using var parent = member.VmfChanges.AddPropertyListener("parent", change =>
        {
            Assert.True(change.IsInverse);
            Assert.Same(change.NewValue, member.Parent);
            trace.Add("parent");
        });

        first.CustomProperties.Add(member);
        Assert.Same(first, member.Parent);
        Assert.Same(first.AsReadOnly(), member.AsReadOnly().Parent);
        Assert.Same(first, ModelSnapshot.Reflection.Reflect(member).FindProperty("parent")!.Get());
        AssertTrace(trace, "parent", "custom+");
        member.Parent = first;
        Assert.Same(member, Assert.Single(first.CustomProperties));
        Assert.Empty(first.Properties);
        AssertTrace(trace);
        first.Properties.Add(member);
        AssertTrace(trace, "custom-", "parent", "parent", "canonical+");
        first.CustomProperties.Add(member);
        AssertTrace(trace, "canonical-", "parent", "parent", "custom+");
        member.Parent = second;
        Assert.Empty(first.CustomProperties);
        Assert.Same(member, Assert.Single(second.Properties));
        AssertTrace(trace, "custom-", "parent", "parent", "new+");
        member.Parent = null;
        Assert.Null(member.Parent);
        Assert.Empty(second.Properties);
        AssertTrace(trace, "new-", "parent");
        first.CustomProperties.Add(member);
        trace.Clear();
        member.Parent = null;
        Assert.Null(member.Parent);
        Assert.Empty(first.CustomProperties);
        AssertTrace(trace, "custom-", "parent");
    }

    [Fact]
    public void SnapshotAndJournalRouteThroughThePhysicalOwningFields()
    {
        var grammar = Grammar.NewBuilder().WithRules([
            Rule.NewBuilder().WithName("first").WithCustomProperties([new Member { Name = "member" }]).Build(),
            new Rule { Name = "second" }
        ]).Build();
        string checkpoint = ModelSnapshot.Write(grammar);
        using var recorder = JournalRecorder<Grammar>.Start(grammar, ModelSnapshot.Registry, Binding, Checkpoint, 6);
        grammar.Rules[0]!.CustomProperties[0]!.Parent = grammar.Rules[1];
        var journal = recorder.Finish();
        var formats = new (string Extension, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_NAMED_INVERSE");
        if (exchange is not null) File.WriteAllText(Path.Combine(exchange, "csharp-checkpoint.json"), checkpoint);
        foreach (var format in formats)
        {
            string output = format.Write(journal);
            Verify(checkpoint, format.Read(output));
            if (exchange is null) continue;
            Verify(File.ReadAllText(Path.Combine(exchange, "java-checkpoint.json")),
                format.Read(File.ReadAllText(Path.Combine(exchange, $"java-journal.{format.Extension}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-journal.{format.Extension}"), output);
        }
    }

    private static void Verify(string checkpoint, ChangeJournal journal)
    {
        using var document = JsonDocument.Parse(checkpoint);
        var custom = document.RootElement.GetProperty("rules")[0].GetProperty("customProperties")[0];
        Assert.False(custom.TryGetProperty("parent", out _));
        var initial = ModelSnapshot.Read<Grammar>(checkpoint);
        var oldOwner = initial.Rules[0]!;
        Assert.Same(oldOwner, oldOwner.CustomProperties[0]!.Parent);
        Assert.Equal(1, journal.WireVersion);
        Assert.Equal(["customProperties", "properties"], Assert.Single(journal.Transactions).Operations.Select(edit => edit.Property));
        var replayed = JournalReplayer.Replay(initial, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        var newOwner = replayed.Rules[1]!;
        Assert.Empty(replayed.Rules[0]!.CustomProperties);
        Assert.Same(newOwner, Assert.Single(newOwner.Properties)!.Parent);
        Assert.Single(oldOwner.CustomProperties);
    }

    private static void Trace(List<string> trace, string property, ModelChange change)
    {
        var collection = Assert.IsType<ModelCollectionChange>(change);
        if (collection.Removed.Count != 0) trace.Add(property + "-");
        if (collection.Added.Count != 0) trace.Add(property + "+");
    }

    private static void AssertTrace(List<string> trace, params string[] expected)
    {
        Assert.Equal(expected, trace);
        trace.Clear();
    }
}
