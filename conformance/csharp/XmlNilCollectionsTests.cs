using System.Xml;
using Eu.Mihosoft.Vmf.Conformance.Collectiondefaults;
using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;
using CollectionDefaults = Eu.Mihosoft.Vmf.Conformance.Collectiondefaults.ModelSnapshot;
using Ordinary = Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment.ModelSnapshot;
using Values = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public sealed class XmlNilCollectionsTests
{
    [Theory]
    [InlineData("plain")]
    [InlineData("typed")]
    [InlineData("references")]
    public void NilAndBareWrappersExplicitlyClearCollectionsInEveryProfile(string profile)
    {
        var omitted = Read<SchemaLiteralSamples>(profile, CollectionDefaults.Registry, "");
        Assert.Equal([7, 11], omitted.Values);
        Assert.Equal(2L, Assert.Single(omitted.Codes));
        Assert.Equal("ready", omitted.Choices[4]);
        VerifyDefaultsPresence(omitted, false);

        foreach (string wrapper in new[] { "", " xsi:nil=\"true\"", " xsi:nil=\"1\"" })
        {
            string Empty(string name) => $"<{name}{wrapper}/>";
            var profileModel = Read<CollectionProfile>(profile, Values.Registry,
                Empty("messages") + Empty("counts") + Empty("phases") + Empty("levels") + Empty("options"));
            Assert.Empty(profileModel.Messages);
            Assert.Empty(profileModel.Counts);
            Assert.Empty(profileModel.Phases);
            Assert.Empty(profileModel.Levels);
            Assert.Empty(profileModel.Options);
            Assert.Empty(Read<DefaultSettings>(profile, Values.Registry, Empty("entries")).Entries);
            Assert.Empty(Read<LegacyContainer>(profile, Ordinary.Registry, Empty("children")).Children);

            var cleared = Read<SchemaLiteralSamples>(profile, CollectionDefaults.Registry,
                Empty("values") + Empty("codes") + Empty("choices"));
            Assert.Empty(cleared.Values);
            Assert.Empty(cleared.Codes);
            Assert.Empty(cleared.Choices);
            VerifyDefaultsPresence(cleared, true);
        }
    }

    [Theory]
    [InlineData("plain")]
    [InlineData("typed")]
    [InlineData("references")]
    public void FalseNilWrappersAndNullableMembersKeepTheirValues(string profile)
    {
        var model = Read<CollectionProfile>(profile, Values.Registry,
            """
            <messages xsi:nil="false"><item xsi:nil="true"/><item></item><item>hello</item></messages>
            <levels xsi:nil="0"><member><value>2</value></member></levels>
            <options xsi:nil="false"><entry><key>present</key><null>true</null></entry></options>
            """);
        Assert.Equal(new string?[] { null, "", "hello" }, model.Messages);
        Assert.Equal(2.0, Assert.Single(model.Levels));
        Assert.Null(Assert.Single(model.Options).Value);
        var owner = Read<LegacyContainer>(profile, Ordinary.Registry, """<children><item xsi:nil="true"/></children>""");
        Assert.Null(Assert.Single(owner.Children));
        Assert.Throws<XmlException>(() => Read<CollectionProfile>(profile, Values.Registry, """<counts><item xsi:nil="true"/></counts>"""));
        Assert.Throws<XmlException>(() => Read<DefaultSettings>(profile, Values.Registry, """<entries><item xsi:nil="true"/></entries>"""));
        Assert.Throws<XmlException>(() => Read<CollectionProfile>(profile, Values.Registry, """<levels><member xsi:nil="true"/></levels>"""));
        Assert.Throws<XmlException>(() => Read<CollectionProfile>(profile, Values.Registry, """<options><entry xsi:nil="true"/></options>"""));
    }

    [Theory]
    [InlineData("messages", "<item>data</item>")]
    [InlineData("messages", "data")]
    [InlineData("levels", "<member><value>2</value></member>")]
    [InlineData("options", "<entry><key>ready</key><value>true</value></entry>")]
    public void NilWrappersCannotDiscardCollectionContent(string name, string content)
    {
        foreach (string literal in new[] { "true", "1" })
        {
            var xml = $"<{name} xsi:nil=\"{literal}\">{content}</{name}>";
            Assert.Contains("An xsi:nil element must be empty", Assert.Throws<XmlException>(() =>
                Read<CollectionProfile>("plain", Values.Registry, xml)).Message);
        }
    }

    private static void VerifyDefaultsPresence(SchemaLiteralSamples model, bool isSet)
    {
        var reflect = CollectionDefaults.Reflection.Reflect(model);
        foreach (string field in new[] { "values", "codes", "choices" })
            Assert.Equal(isSet, reflect.FindProperty(field)!.IsSet);
    }

    private static T Read<T>(string profile, SnapshotRegistry registry, string fields) where T : class
    {
        const string xsi = "http://www.w3.org/2001/XMLSchema-instance";
        string root = $"{typeof(T).Namespace!.ToLowerInvariant()}.{typeof(T).Name}";
        string metadata = profile == "plain" ? "" : $"<vmf-type>{root}</vmf-type>";
        if (profile == "references") metadata += "<vmf-id>1</vmf-id>";
        string xml = $"<{root} xmlns:xsi=\"{xsi}\">{metadata}{fields}</{root}>";
        return profile switch
        {
            "plain" => SnapshotXml.ReadPlain<T>(xml, registry),
            "typed" => SnapshotXml.ReadWithoutReferences<T>(xml, registry),
            "references" => SnapshotXml.Read<T>(xml, registry),
            _ => throw new ArgumentOutOfRangeException(nameof(profile)),
        };
    }
}
