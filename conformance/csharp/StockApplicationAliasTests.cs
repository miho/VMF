using System.Text.Json;
using System.Xml;
using System.Xml.Linq;
using Vmf.Runtime;
using Xunit;
using External = Eu.Mihosoft.Vmf.Jackson.Test.External_types01;
using Simple = Eu.Mihosoft.Vmf.Jackson.Test.Simple;

namespace Vmf.CSharp.Conformance;

public class StockApplicationAliasTests
{
    private const string Prefix = "eu.mihosoft.vmf.jackson.test.simple.";

    [Theory]
    [InlineData(VmfDocumentFormat.Json, false)]
    [InlineData(VmfDocumentFormat.Json, true)]
    [InlineData(VmfDocumentFormat.Yaml, false)]
    [InlineData(VmfDocumentFormat.Yaml, true)]
    [InlineData(VmfDocumentFormat.Xml, false)]
    [InlineData(VmfDocumentFormat.Xml, true)]
    public void ApplicationAliasesPreserveTheStockSubtypeAndReferenceProfiles(VmfDocumentFormat format, bool references)
    {
        var aliases = new Dictionary<string, string>
        {
            ["person"] = Prefix + "Person",
            ["employee-legacy"] = Prefix + "Employee",
            ["operator"] = Prefix + "Employee",
            ["old-workforce"] = Prefix + "MyModel",
            ["workforce"] = Prefix + "MyModel",
        };
        var registry = Simple.ModelSnapshot.Registry.WithTypeAliases(aliases)
            .WithExternalValueCodecs(ExternalSnapshotCodecs.Empty);
        aliases.Clear();

        var address = Simple.Address.NewBuilder().WithCity("Leipzig").Build();
        var owner = Simple.MyModel.NewBuilder().WithPersons([
            new Simple.Person { Name = "Worker", Address = address },
            new Simple.Employee { Name = "007", EmployeeId = "E<&17", Address = address },
        ]).Build();

        (Func<object, SnapshotRegistry, string> Write, Func<string, SnapshotRegistry, Simple.MyModel> Read) codec = (format, references) switch
        {
            (VmfDocumentFormat.Json, true) => (SnapshotJson.Write, SnapshotJson.Read<Simple.MyModel>),
            (VmfDocumentFormat.Json, false) => (SnapshotJson.WriteWithoutReferences, SnapshotJson.ReadWithoutReferences<Simple.MyModel>),
            (VmfDocumentFormat.Yaml, true) => (SnapshotYaml.Write, SnapshotYaml.Read<Simple.MyModel>),
            (VmfDocumentFormat.Yaml, false) => (SnapshotYaml.WriteWithoutReferences, SnapshotYaml.ReadWithoutReferences<Simple.MyModel>),
            (VmfDocumentFormat.Xml, true) => ((value, schema) => SnapshotXml.Write(value, schema, "workforce"), SnapshotXml.Read<Simple.MyModel>),
            _ => ((value, schema) => SnapshotXml.WriteWithoutReferences(value, schema, "workforce"),
                SnapshotXml.ReadWithoutReferences<Simple.MyModel>),
        };

        string written = codec.Write(owner, registry);
        Assert.Contains("operator", written);
        Assert.DoesNotContain("employee-legacy", written);
        if (format == VmfDocumentFormat.Xml)
        {
            Assert.Equal("workforce", XDocument.Parse(written).Root!.Name.LocalName);
        }
        Verify(codec.Read(written, registry));
        string old = written.Replace("operator", "employee-legacy", StringComparison.Ordinal)
            .Replace("<workforce>", "<old-workforce>", StringComparison.Ordinal)
            .Replace("</workforce>", "</old-workforce>", StringComparison.Ordinal);
        Verify(codec.Read(old, registry));

        void Verify(Simple.MyModel restored)
        {
            Assert.Collection(restored.Persons,
                person => Assert.Equal("Worker", person?.Name),
                person =>
                {
                    var employee = Assert.IsType<Simple.Employee>(person);
                    Assert.Equal("007", employee.Name);
                    Assert.Equal("E<&17", employee.EmployeeId);
                    Assert.Same(restored, employee.Model);
                });
            Assert.Equal("Leipzig", restored.Persons[0]!.Address?.City);
            Assert.Equal(references, ReferenceEquals(restored.Persons[0]!.Address, restored.Persons[1]!.Address));
        }
    }

    [Fact]
    public void PlainXmlKeepsHostCodecsAndAcceptsEarlierRootNames()
    {
        var registry = External.ModelSnapshot.Registry.WithTypeAliases(new Dictionary<string, string>
        {
            ["external-new"] = "eu.mihosoft.vmf.jackson.test.external_types01.ExternalTypeModel01",
        });
        var owner = new External.ExternalTypeModel01 { EnumValue = External.ExternalEnum.SECOND };

        foreach (string root in new[] { "external-new", "external-type-model-01" })
        {
            string wire = SnapshotXml.WritePlain(owner, registry, root);
            Assert.Equal(root, XDocument.Parse(wire).Root!.Name.LocalName);
            Assert.DoesNotContain("vmf-type", wire);
            Assert.Equal(External.ExternalEnum.SECOND, SnapshotXml.ReadPlain<External.ExternalTypeModel01>(wire, registry).EnumValue);
            if (root == "external-new")
            {
                Assert.Throws<XmlException>(() => External.ModelSnapshot.ReadPlainXml<External.ExternalTypeModel01>(wire));
            }
        }
    }

    [Fact]
    public void ConflictingNamesStayRejectedAndFeatureBundlesUseQualifiedNames()
    {
        var registry = Simple.ModelSnapshot.Registry.WithTypeAliases(new Dictionary<string, string> { ["operator"] = Prefix + "Employee" });
        Assert.Throws<ArgumentException>(() => registry.WithTypeAliases(new Dictionary<string, string> { ["operator"] = Prefix + "Person" }));
        Assert.Throws<ArgumentException>(() => registry.WithTypeAliases(new Dictionary<string, string> { ["missing"] = Prefix + "Missing" }));
        Assert.Throws<ArgumentException>(() => registry.WithTypeAliases(new Dictionary<string, string> { [Prefix + "Person"] = Prefix + "Employee" }));

        var owner = new Simple.MyModel();
        owner.Persons.Add(new Simple.Employee { Name = "007" });
        Assert.Throws<JsonException>(() => SnapshotJson.WritePlain(owner, registry));
        var codec = new FeatureBundleCodec(registry);
        var wire = codec.Write(owner, new FeatureAttachments(registry));
        Assert.DoesNotContain("operator", wire);
        Assert.Contains(Prefix + "Employee", wire);
        Assert.IsType<Simple.Employee>(Assert.Single(codec.Read<Simple.MyModel>(wire).Owner.Persons));
    }
}
