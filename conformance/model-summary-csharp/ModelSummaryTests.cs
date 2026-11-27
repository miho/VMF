using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Modelsummaries;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ModelSummaryTests
{
    private const string Uuid = "980e13c6-84a0-49d6-821d-737269635aef";
    private const string Badge = """{"@type":"SummaryToken", "text": "badge"}""";
    private const string Child = """{"@type":"SummaryNode", "anchor": "null", "children": [], "name": "leaf", "peers": [], "ranked": [], "trail": []}""";

    [Fact]
    public void JavaSummarySuppressesCyclesAndLaterMutableAliasesByIdentity()
    {
        var root = new DerivedSummary { Name = "quote\"line\nbreak", Hidden = "hidden", Rank = 7 };
        var child = new SummaryNode { Name = "leaf", Hidden = "child secret" };
        root.Anchor = root;
        root.Children.Add(child);
        root.Peers.Add(child);
        root.Ranked.Add(child);
        root.Trail.AddRange([null, child, root]);

        string summary = Format(root);
        const string expected = "{\"@type\":\"DerivedSummary\", \"anchor\": \"{skipping recursion}\", \"children\": ["
            + Child + "], \"name\": \"quote\"line\nbreak\", \"peers\": [{skipping recursion}], \"rank\": \"7\","
            + " \"ranked\": [{skipping recursion}], \"trail\": [null, {skipping recursion}, {skipping recursion}]}";
        Assert.Equal(expected, summary);
        Assert.Equal(summary, Format(root.AsReadOnly()));
        Assert.Equal(summary, root.ToString());
        Assert.Equal(summary, root.AsReadOnly().ToString());
        Assert.Equal(Child, Format(child));
        Exchange("graph", summary);

        using var snapshot = JsonDocument.Parse(ModelSnapshot.Write(root));
        Assert.Equal("hidden", snapshot.RootElement.GetProperty("hidden").GetString());
        Assert.Equal(root.Name, snapshot.RootElement.GetProperty("caption").GetString());
    }

    [Fact]
    public void JavaSummaryExpandsImmutableAliasesAndFormatsManagedAndExternalValues()
    {
        var token = new SummaryToken("private", "badge");
        var model = new SummaryValues { External = Guid.Parse(Uuid), Magnitude = float.Epsilon, First = token };
        model.Checked.AddRange([2, 1]);
        model.Doubles.AddRange([0.0, -0.0, double.NaN, double.PositiveInfinity]);
        model.Readings.AddRange([double.Epsilon, 1e20, 0.001, 1e7, null]);
        model.Tokens.AddRange([token, null, token]);
        model.Values.Add("nil", null);
        model.Values.Add("large", 9007199254740993L);
        model.Words.AddRange(["true", "quote\"word", null]);

        var summary = Format(model);
        const string expected = "{\"@type\":\"SummaryValues\", \"checked\": \"[2, 1]\", \"doubles\": \"[0.0, -0.0, NaN, Infinity]\","
            + " \"external\": \"" + Uuid + "\", \"first\": \"" + Badge + "\", \"magnitude\": \"1.4E-45\","
            + " \"readings\": [4.9E-324, 1.0E20, 0.001, 1.0E7, null], \"tokens\": [" + Badge + ", null, " + Badge
            + "], \"values\": \"{nil=null, large=9007199254740993}\", \"words\": [true, quote\"word, null]}";
        Assert.Equal(expected, summary);
        Assert.Equal(summary, model.ToString());
        Assert.Equal(Badge, Format(token));
        Assert.Equal(Badge, token.ToString());
        Exchange("values", summary);
    }

    [Fact]
    public void VeryDeepReferenceCyclesAreDiagnosticValues()
    {
        var head = new SummaryNode { Name = "head" };
        var tail = head;
        for (int index = 0; index < 8192; index++)
        {
            var node = new SummaryNode();
            tail.Anchor = node;
            tail = node;
        }
        tail.Name = "end";
        tail.Anchor = head;
        string summary = Format(head);
        Assert.Contains("\"name\": \"end\"", summary);
        Assert.Contains("{skipping recursion}", summary);
    }

    private static string Format(object model) => ModelSnapshot.Reflection.FormatSummary(model);

    private static void Exchange(string name, string summary)
    {
        string? path = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_MODEL_SUMMARIES");
        if (path is null) return;
        Assert.Equal(File.ReadAllText(Path.Combine(path, $"java-{name}.txt")), summary);
        File.WriteAllText(Path.Combine(path, $"csharp-{name}.txt"), summary);
    }
}
