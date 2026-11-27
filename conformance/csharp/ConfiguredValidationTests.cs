using System.Text.Json;
using System.Text.Json.Nodes;
using System.Xml;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;
using YamlDotNet.Core;

namespace Vmf.CSharp.Conformance;

public class ConfiguredValidationTests
{
    [Fact]
    public void RelationshipMultiplicityUsesIdentityCountsAndValidatesAfterHydration()
    {
        var a = new SignalNode { Name = "a" };
        var b = new SignalNode { Name = "b" };
        var c = new SignalNode { Name = "c" };
        var disabled = new SignalNode();
        var matrix = SignalMatrix.NewBuilder().WithNodes([a, b, c]).Build();
        var validator = NewValidator().Build();
        Assert.Equal("/selected", Assert.Single(matrix.Validate()).ModelPath);
        Assert.True(matrix.Selected.AddRange([a, b, c]));
        matrix.DisabledLink = b;
        matrix.DisabledOwned = disabled;
        Assert.Same(matrix, disabled.DisabledOwner);

        var paths = new[] { "/disabledLink", "/disabledOwned", "/selected", "/disabledOwned/disabledOwner" };
        var report = validator.Validate(matrix);
        Assert.Equal(paths, Paths(report));
        Assert.Equal(paths, matrix.Validate().Select(issue => issue.ModelPath));
        Assert.Equal([1, 1, 3, 1], report.MultiplicityViolations.Select(violation => violation.ActualCount));
        Assert.Throws<ModelValidationException>(() => ModelSnapshot.Read<SignalMatrix>(ModelSnapshot.Write(matrix), validator));

        matrix.Selected.Remove(c);
        matrix.DisabledLink = null;
        matrix.DisabledOwned = null;
        Assert.Null(disabled.DisabledOwner);
        var restored = ModelSnapshot.Read<SignalMatrix>(ModelSnapshot.Write(matrix), validator);
        Assert.Equal(2, restored.Selected.Count);
        Assert.Contains(restored.Nodes[1]!, restored.Selected);
        var schema = JsonNode.Parse(SnapshotSchema.Generate<SignalMatrix>(ModelSnapshot.Registry, SnapshotSchemaProfile.References));
        var fields = schema!["properties"]!;
        Assert.Equal("null", fields["disabledOwned"]!["type"]!.GetValue<string>());
        Assert.Equal(1, fields["selected"]!["minItems"]!.GetValue<int>());
        Assert.Equal(2, fields["selected"]!["maxItems"]!.GetValue<int>());
    }

    [Fact]
    public void BuilderImportsRelationshipStateBeforeMultiplicityValidation()
    {
        var a = new SignalNode { Name = "a" };
        var b = new SignalNode { Name = "b" };
        var c = new SignalNode { Name = "c" };
        var disabled = new SignalNode { Name = "disabled" };
        var source = SignalMatrix.NewBuilder().WithSelected([a, b, c]).WithDisabledLink(b)
            .WithDisabledOwned(disabled).Build();
        var builder = SignalMatrix.NewBuilder().ApplyFrom(source);

        var oldSelected = new SignalNode { Name = "old selected" };
        var oldOwned = new SignalNode { Name = "old owned" };
        var target = SignalMatrix.NewBuilder().WithSelected([oldSelected]).WithDisabledOwned(oldOwned).Build();
        var selected = target.Selected;
        builder.ApplyTo(target);
        Assert.Same(selected, target.Selected);
        Assert.Equal(4, selected.Count);
        Assert.Contains(oldSelected, selected);
        Assert.Same(oldOwned, target.DisabledOwned);
        Assert.Same(source, disabled.DisabledOwner);
        Assert.Same(b, target.DisabledLink);
        Assert.Contains(NewValidator().Build().Validate(target).Issues, issue => issue.Path == "/selected"
            && issue.MultiplicityViolation is { ActualCount: 4 });

        source.Selected.Clear();
        builder.AppendCollections(false).ApplyTo(target);
        Assert.Equal(3, selected.Count);
        Assert.All(new[] { a, b, c }, member => Assert.Contains(member, selected));
        Assert.Same(oldOwned, target.DisabledOwned);

        var built = builder.Build();
        Assert.Same(disabled, built.DisabledOwned);
        Assert.Same(built, disabled.DisabledOwner);
        Assert.Null(source.DisabledOwned);
        Assert.Same(b, built.DisabledLink);
        Assert.Equal(new[] { "/disabledLink", "/disabledOwned", "/selected", "/disabledOwned/disabledOwner" },
            built.Validate().Select(issue => issue.ModelPath));
    }

    [Fact]
    public void ChecksDisabledScalarsAndRequiredInverseContainersAfterHydration()
    {
        var validator = NewValidator().Build();
        var probe = new InstrumentProbe { Reserved = "forbidden" };
        var detached = validator.Validate(probe);
        Assert.Equal(["ERROR|vmf.multiplicity|/rack", "ERROR|vmf.multiplicity|/reserved"], Summaries(detached));
        Assert.Equal([0, 1], detached.MultiplicityViolations.Select(violation => violation.ActualCount));
        Assert.Equal(["/rack", "/reserved"], probe.Validate().Select(issue => issue.ModelPath));

        var rack = new InstrumentRack();
        rack.Probes.Add(probe);
        Assert.Same(rack, probe.Rack);
        Assert.Equal("/probes/0/reserved", Assert.Single(rack.Validate()).ModelPath);
        probe.Reserved = null;
        Assert.True(validator.Validate(rack.AsReadOnly()).IsValid);

        var restored = ModelSnapshot.ReadPlain<InstrumentRack>("""{"probes":[{}]}""", validator);
        Assert.Same(restored, Assert.Single(restored.Probes)!.Rack);
        var rejected = Assert.Throws<ModelValidationException>(() =>
            ModelSnapshot.ReadPlain<InstrumentRack>("""{"probes":[{"reserved_slot":"x"}]}""", validator));
        Assert.Equal("/probes/0/reserved", Assert.Single(rejected.Report.Issues).Path);
        var schema = JsonNode.Parse(SnapshotSchema.Generate<InstrumentProbe>(ModelSnapshot.Registry, SnapshotSchemaProfile.Plain));
        var fields = schema!["properties"]!;
        Assert.Equal("null", fields["reserved_slot"]!["type"]!.GetValue<string>());
        Assert.Null(fields["rack"]);
    }

    [Theory]
    [InlineData(null, 0)]
    [InlineData("AB12", 0)]
    [InlineData("NP90", 0)]
    [InlineData("AI12", 1)]
    [InlineData("BO09", 1)]
    [InlineData("ab12", 1)]
    [InlineData("AB12\n", 2)]
    [InlineData("AB00", 1)]
    [InlineData("IO00", 2)]
    public void BindsEachNativePatternToItsOwnDeclarationAndReportsTheLogicalPath(string? input, int count)
    {
        var model = new NonzeroPatternedLabel { Label = input };
        var validator = NewValidator().Build();

        foreach (var target in new object[] { model, model.AsReadOnly() })
        {
            var report = validator.Validate(target);
            Assert.Equal(count == 0, report.IsValid);
            Assert.Equal(count, report.Issues.Count);
            Assert.All(report.Issues, issue =>
            {
                Assert.Equal("vmf.pattern", issue.Code);
                Assert.Equal("/label", issue.Path);
                Assert.Equal("tracking_label", issue.Property!.WireName);
                Assert.Same(target, issue.Object);
            });
        }
    }

    [Fact]
    public void ValidatesNativePatternsOnImportAndKeepsThePortableSchemaConstraints()
    {
        var validator = NewValidator().Build();
        var valid = ModelSnapshot.ReadPlain<NonzeroPatternedLabel>("""{"tracking_label":"NP90"}""", validator);
        Assert.Equal("NP90", valid.Label);
        var invalid = Assert.Throws<ModelValidationException>(() =>
            ModelSnapshot.ReadPlain<NonzeroPatternedLabel>("""{"tracking_label":"AB00"}""", validator));
        Assert.Equal("vmf.pattern", Assert.Single(invalid.Report.Issues).Code);

        var schema = JsonNode.Parse(SnapshotSchema.Generate<NonzeroPatternedLabel>(ModelSnapshot.Registry, SnapshotSchemaProfile.Plain));
        var field = schema!["properties"]!["tracking_label"]!;
        Assert.Equal(4, field["minLength"]!.GetValue<int>());
        Assert.Equal(8, field["maxLength"]!.GetValue<int>());
        Assert.Null(field["pattern"]);
    }

    [Fact]
    public void ReportsDeclaredConstraintsBeforeAssignableRulesAndPreservesTheView()
    {
        var entry = new ScalarEntry { Sequence = 1, StatusLabel = "x" };
        var batch = new ScalarBatch { Title = "B" };
        batch.Entries.Add(entry);
        var invocations = new List<string>();
        var builder = NewValidator()
            .WithRule<ScalarBounds>((model, context) =>
            {
                Assert.Same(entry, model);
                Assert.Same(entry, context.Object);
                Assert.Same(batch, context.Root);
                invocations.Add($"mutable base {context.Path}");
                context.Report(ValidationSeverity.Warning, "sequence.review", "sequence", "Review sequence");
            })
            .WithRule<ScalarEntry>((_, context) =>
            {
                invocations.Add($"mutable subtype {context.Path}");
                context.Report(ValidationSeverity.Error, "status.review", "statusLabel", "Review status");
            })
            .WithRule<IReadOnlyScalarBounds>((_, context) =>
            {
                invocations.Add($"read-only base {context.Path}");
                context.Report(ValidationSeverity.Info, "read-only.review", "Review read-only entry");
            })
            .WithGraphRule((root, context) =>
            {
                Assert.Same(root, context.Root);
                Assert.Same(root, context.Object);
                Assert.Equal("", context.Path);
                invocations.Add("graph");
                context.Report(ValidationSeverity.Warning, "graph.review", "Review model");
            });
        var validator = builder.Build();
        builder.WithGraphRule((_, _) => throw new InvalidOperationException("Builder altered an existing validator."));

        var report = validator.Validate(batch);
        Assert.False(report.IsValid);
        Assert.Equal(["mutable base /entries/0", "mutable subtype /entries/0", "graph"], invocations);
        Assert.Equal([
            "ERROR|vmf.range|/entries/0/sequence",
            "ERROR|vmf.length|/entries/0/statusLabel",
            "ERROR|vmf.pattern|/entries/0/statusLabel",
            "WARNING|sequence.review|/entries/0/sequence",
            "ERROR|status.review|/entries/0/statusLabel",
            "WARNING|graph.review|",
        ], Summaries(report));
        Assert.Same(entry, report.Issues[0].Object);
        Assert.Equal("sequence", report.Issues[0].Property!.Name);
        Assert.Equal("statusLabel", report.Issues[4].Property!.Name);
        Assert.Equal("status_label", report.Issues[4].Property!.WireName);
        Assert.Null(report.Issues[5].Property);
        Assert.Throws<NotSupportedException>(() => ((IList<ValidationDiagnostic>)report.Issues).Clear());

        invocations.Clear();
        entry.Sequence = 9_007_199_254_740_993L;
        entry.StatusLabel = "A-12";
        var view = batch.AsReadOnly();
        var readOnly = validator.Validate(view);
        Assert.True(readOnly.IsValid);
        Assert.Equal(["read-only base /entries/0", "graph"], invocations);
        Assert.Equal(["INFO|read-only.review|/entries/0", "WARNING|graph.review|"], Summaries(readOnly));
        Assert.Same(view.Entries[0], readOnly.Issues[0].Object);

        var missing = NewValidator().WithRule<ScalarEntry>((_, context) =>
            context.Report(ValidationSeverity.Error, "wrong", "status_label", "Wire alias is independent")).Build();
        Assert.Throws<ArgumentException>(() => missing.Validate(batch));
    }

    [Fact]
    public void VisitsContainmentBeforeReferencesAndKeepsTheFirstPathForEachAlias()
    {
        var head = Node("head");
        var left = Node("left");
        var remote = Node("remote");
        var root = new ArchiveRoot { Head = head };
        head.Children.Add(left);
        head.Inset = Node("right");
        root.Review.AddRange([null, left, remote, remote]);
        head.Anchor = remote;
        remote.Children.Add(Node("deep"));
        remote.Trail.Add(Node("far"));
        remote.Anchor = head;

        var containmentOnly = NewValidator().WithRule<object>((_, context) =>
            context.Report(ValidationSeverity.Info, "visit", "Visited model")).Build();
        Assert.Equal(["", "/head", "/head/children/0", "/head/inset"], Paths(containmentOnly.Validate(root)));

        var recursive = NewValidator().WithFollowReferences().WithRule<object>((_, context) =>
            context.Report(ValidationSeverity.Info, "visit", "Visited model")).Build();
        var report = recursive.Validate(root);
        Assert.Equal(["", "/head", "/head/children/0", "/head/inset", "/review/2", "/review/2/children/0",
            "/review/2/trail/0"], Paths(report));
        Assert.Same(remote, report.Issues[4].Object);
        var view = root.AsReadOnly();
        var readOnly = recursive.Validate(view);
        Assert.Equal(Paths(report), Paths(readOnly));
        Assert.Same(view.Review[2], readOnly.Issues[4].Object);
        Assert.Equal([""], Paths(recursive.Validate(left)));
    }

    [Fact]
    public void VisitsImmutableAliasesOnceWithoutCollapsingEqualValues()
    {
        var first = new ValueLabel("same");
        var second = new ValueLabel("same");
        var root = new ValueSelection { Current = first };
        root.Selected.Add(first);
        root.Selected.Add(second);
        var seen = new List<ValueLabel>();
        var validator = NewValidator().WithFollowReferences().WithRule<ValueLabel>((model, _) => seen.Add(model)).Build();

        Assert.Equal(first, second);
        Assert.NotSame(first, second);
        Assert.True(validator.Validate(root).IsValid);
        Assert.Collection(seen, item => Assert.Same(first, item), item => Assert.Same(second, item));
    }

    [Fact]
    public void OptInImportValidatesTheHydratedGraphOnceAndCarriesTheReport()
    {
        const string json = """
            {"@vmf-id":"archive","review":["late",null,"late"],
              "head":{"@vmf-id":"head","name":"head","children":[{"@vmf-id":"late","name":"late"}],"anchor":"late"}}
            """;
        int calls = 0;
        var validator = NewValidator().WithGraphRule((model, context) =>
        {
            calls++;
            var archive = Assert.IsType<ArchiveRoot>(model);
            var child = Assert.Single(archive.Head!.Children);
            Assert.Same(child, archive.Head.Anchor);
            Assert.Same(child, archive.Review[0]);
            context.Report(ValidationSeverity.Warning, "archive.review", "Review archive");
        }).Build();

        var hydrated = ModelSnapshot.Read<ArchiveRoot>(json, validator);
        Assert.Equal(1, calls);
        Assert.Same(hydrated.Head!.Anchor, hydrated.Review[0]);
        var error = Assert.Throws<ModelValidationException>(() =>
            ModelSnapshot.Read<ArchiveRoot>(json.Replace("\"late\",null,\"late\"", "\"late\",null",
                StringComparison.Ordinal), validator));
        Assert.Equal(2, calls);
        var diagnostic = error.Report.Issues[0];
        Assert.Equal("vmf.multiplicity", diagnostic.Code);
        Assert.Equal("/review", diagnostic.Path);
        var violation = Assert.Single(error.Report.MultiplicityViolations);
        Assert.Equal(1, violation.ActualCount);
        Assert.Equal("review", violation.Property.Name);
        Assert.Same(diagnostic.Object, violation.Object);
        Assert.Same(violation, diagnostic.MultiplicityViolation);

        Assert.Throws<JsonException>(() => ModelSnapshot.Read<ArchiveRoot>(json.Replace(
            "\"anchor\":\"late\"", "\"anchor\":\"missing\"", StringComparison.Ordinal), validator));
        Assert.Equal(2, calls);
    }

    [Fact]
    public void EveryImportProfileRunsOnceAfterDefaultsAreHydrated()
    {
        const string empty = "{}";
        const string xml = "<eu.mihosoft.vmf.conformance.values.DefaultSettings/>";
        int calls = 0;
        var validator = NewValidator().WithRule<DefaultBase>((model, _) =>
        {
            calls++;
            Assert.Equal("idle\nready", model.Name);
            Assert.Equal(7, model.Limit);
        }).Build();
        var models = new[]
        {
            ModelSnapshot.Read<DefaultSettings>(empty, validator),
            ModelSnapshot.ReadWithoutReferences<DefaultSettings>(empty, validator),
            ModelSnapshot.ReadPlain<DefaultSettings>(empty, validator),
            ModelSnapshot.ReadXml<DefaultSettings>(xml, validator),
            ModelSnapshot.ReadXmlWithoutReferences<DefaultSettings>(xml, validator),
            ModelSnapshot.ReadPlainXml<DefaultSettings>(xml, validator),
            ModelSnapshot.ReadYaml<DefaultSettings>(empty, validator),
            ModelSnapshot.ReadYamlWithoutReferences<DefaultSettings>(empty, validator),
            ModelSnapshot.ReadPlainYaml<DefaultSettings>(empty, validator),
        };
        Assert.Equal(models.Length, calls);
        Assert.All(models, model => Assert.Equal("idle\nready", model.Name));
    }

    [Fact]
    public void EscapesLogicalNamesAndChecksBoundsInManualReflectionRegistrations()
    {
        const string name = "logical~/field";
        var metadata = new SnapshotType("manual", typeof(ManualModel), static () => new ManualModel(),
            [new SnapshotProperty("wire", name, SnapshotPropertyKind.Scalar, typeof(string),
                static model => ((ManualModel)model).Value, set: static (model, value) => ((ManualModel)model).Value = (string?)value)]);
        var reflection = new ModelReflectionRegistry(new SnapshotRegistry([metadata]),
            [new ModelReflectionType(typeof(ManualModel),
                [new ModelReflectionField(name, static model => ((ManualModel)model).Value, lowerBound: 1)])]);
        var validator = ModelValidator.NewBuilder(reflection)
            .WithRule<ManualModel>((_, context) => context.Report(ValidationSeverity.Warning, "review", name, "Review"))
            .Build();
        var model = new ManualModel();
        var report = validator.Validate(model);

        Assert.Equal(["ERROR|vmf.multiplicity|/logical~0~1field", "WARNING|review|/logical~0~1field"], Summaries(report));
        Assert.Equal(0, Assert.Single(report.MultiplicityViolations).ActualCount);
        Assert.Equal("wire", report.Issues[0].Property!.WireName);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void RejectsOutOfRangeNumbersBeforeConfiguredValidation(bool references)
    {
        int calls = 0;
        var validator = NewValidator().WithGraphRule((_, _) => calls++).Build();
        Check(new ScalarSample
        {
            IntValue = int.MinValue, OptionalInt = int.MaxValue,
            LongValue = long.MinValue, OptionalLong = long.MaxValue, CharValue = 'x',
            FloatValue = 0.125f, DoubleValue = 0.25, OptionalFloat = 0.0625f, OptionalDouble = 0.03125,
            TinyFloat = float.Epsilon, TinyDouble = double.Epsilon, SignedZero = -0.0,
            NegativeInfinity = double.NegativeInfinity,
        }, restored =>
        {
            Assert.Equal(int.MinValue, restored.IntValue);
            Assert.Equal(int.MaxValue, restored.OptionalInt);
            Assert.Equal(long.MinValue, restored.LongValue);
            Assert.Equal(long.MaxValue, restored.OptionalLong);
            Assert.Equal(0.125f, restored.FloatValue);
            Assert.Equal(0.25, restored.DoubleValue);
            Assert.Equal(0.0625f, restored.OptionalFloat);
            Assert.Equal(0.03125, restored.OptionalDouble);
            Assert.Equal(float.Epsilon, restored.TinyFloat);
            Assert.Equal(double.Epsilon, restored.TinyDouble);
            Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(restored.SignedZero!.Value));
            Assert.Equal(double.NegativeInfinity, restored.NegativeInfinity);
        }, ("-2147483648", "-4294967296"), ("2147483647", "4294967296"),
            ("-9223372036854775808", "-18446744073709551616"), ("9223372036854775807", "18446744073709551616"),
            ("0.125", "1e40"), ("0.25", "1e400"), ("0.0625", "-1e-50"), ("0.03125", "-1e-400"));

        var collections = new CollectionProfile();
        collections.Counts.Add(int.MaxValue);
        collections.Readings.Add(0.015625);
        collections.Levels.Add(1.0);
        collections.Limits.Add("limit", 1);
        Check(collections, restored =>
        {
            Assert.Equal(int.MaxValue, Assert.Single(restored.Counts));
            Assert.Equal(0.015625, Assert.Single(restored.Readings));
        }, ("2147483647", "4294967296"), ("0.015625", "1e-400"));
        Check(new ScalarBounds { Sequence = 9_007_199_254_740_993L },
            restored => Assert.Equal(9_007_199_254_740_993L, restored.Sequence),
            ("9007199254740993", "18455751272964292609"));

        Assert.Equal(9, calls);

        void Check<T>(T sample, Action<T> verify, params (string Boundary, string Overflow)[] changes) where T : class
        {
            CheckFormat(references ? ModelSnapshot.Write(sample) : ModelSnapshot.WritePlain(sample),
                json => references ? ModelSnapshot.Read<T>(json, validator) : ModelSnapshot.ReadPlain<T>(json, validator),
                typeof(JsonException));
            CheckFormat(references ? ModelSnapshot.WriteXml(sample) : ModelSnapshot.WritePlainXml(sample),
                xml => references ? ModelSnapshot.ReadXml<T>(xml, validator) : ModelSnapshot.ReadPlainXml<T>(xml, validator),
                typeof(XmlException));
            CheckFormat(references ? ModelSnapshot.WriteYaml(sample) : ModelSnapshot.WritePlainYaml(sample),
                yaml => references ? ModelSnapshot.ReadYaml<T>(yaml, validator) : ModelSnapshot.ReadPlainYaml<T>(yaml, validator),
                typeof(YamlException));

            void CheckFormat(string document, Func<string, T> read, Type expectedError)
            {
                verify(read(document));
                foreach (var (boundary, overflow) in changes)
                {
                    var modified = document.Replace(boundary, overflow, StringComparison.Ordinal);
                    Assert.NotEqual(document, modified);
                    Assert.IsType(expectedError, Record.Exception(() => read(modified)));
                }
            }
        }
    }

    private sealed class ManualModel
    {
        public string? Value { get; set; }
    }
    private static ModelValidator.Builder NewValidator() => ModelValidator.NewBuilder(ModelSnapshot.Reflection);

    private static ArchiveNode Node(string name) => new() { Name = name };

    private static IEnumerable<string> Paths(ValidationReport report) => report.Issues.Select(issue => issue.Path);

    private static IEnumerable<string> Summaries(ValidationReport report) =>
        report.Issues.Select(issue => $"{issue.Severity.ToString().ToUpperInvariant()}|{issue.Code}|{issue.Path}");
}
