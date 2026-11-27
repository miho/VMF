using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Jackson.Test.Validation.Schema;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockValidationSchemaTests
{
    private const string ValidDocument = """
        {"entries":[{"entry-name":"A1"}],"attributes":{"color":"red"},"labels":[{"key":1,"value":"first"}]}
        """;

    [Theory]
    [InlineData(null, true)]
    [InlineData("abc", true)]
    [InlineData("ABC", false)]
    [InlineData("abc\n", false)]
    [InlineData("123", false)]
    public void NativePatternUsesTheSameAnchorsAsJava(string? value, bool valid)
    {
        var mapper = ModelValidator.NewBuilder(ModelSnapshot.Reflection).Build();
        var document = ModelSnapshot.ReadPlain<Document>(ValidDocument, mapper);
        Assert.Equal("draft", document.Status);
        Assert.Equal("A1", document.Entries[0]!.Name);
        Assert.Same(document, document.Entries[0]!.Document);
        document.JavaOnly = value;

        var report = mapper.Validate(document.AsReadOnly());
        Assert.Equal(valid, report.IsValid);
        if (!valid)
        {
            var issue = Assert.Single(report.Issues);
            Assert.Equal("/javaOnly", issue.Path);
            Assert.Equal("vmf.pattern", issue.Code);
            Assert.Throws<ModelValidationException>(() =>
                ModelSnapshot.ReadPlain<Document>(ModelSnapshot.WritePlain(document), mapper));
        }
    }

    [Fact]
    public void SchemaRetainsPortableRulesAndRuntimeChecksHostPatterns()
    {
        var schema = JsonNode.Parse(SnapshotSchema.Generate<Document>(ModelSnapshot.Registry, SnapshotSchemaProfile.Plain))!;
        var properties = schema["properties"]!;
        Assert.Equal("draft", properties["status"]!["default"]!.GetValue<string>());
        Assert.Null(properties["javaOnly"]!["pattern"]);
        Assert.Contains("entries", schema["required"]!.AsArray().Select(field => field!.GetValue<string>()));
        Assert.DoesNotContain("status", schema["required"]!.AsArray().Select(field => field!.GetValue<string>()));

        var mapper = ModelValidator.NewBuilder(ModelSnapshot.Reflection).Build();
        var document = ModelSnapshot.ReadPlain<Document>(ValidDocument, mapper);
        document.Rate = 42.5;
        Assert.Equal("/rate", Assert.Single(mapper.Validate(document).Issues).Path);
    }

    [Fact]
    public void OppositeFillsTheOmittedEndInTheExistingReferenceDocument()
    {
        const string payload = """
            {"stations":[{"@vmf-id":"s","supervisor":"w"}],"workers":[{"@vmf-id":"w","station":"s"}]}
            """;
        var mapper = ModelValidator.NewBuilder(ModelSnapshot.Reflection).Build();
        var document = ModelSnapshot.Read<ReferenceDocument>(payload, mapper);
        var station = Assert.Single(document.Stations)!;
        var worker = Assert.Single(document.Workers)!;
        Assert.Same(worker, station.Supervisor);
        Assert.Same(worker, Assert.Single(station.Workers));
        Assert.Same(station, worker.Station);
    }
}
