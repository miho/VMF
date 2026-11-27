using System.Collections;
using System.Text.Json;
using Eu.Mihosoft.Vmftest.Referenceends;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class StockReferenceDefaultTests
{
    [Fact]
    public void FreshModelAndBuilderCaptureReferenceIdentities()
    {
        var network = new DefaultNetwork();
        var seed = Assert.Single(network.Nearby);
        var field = Field(network, "nearby");
        Assert.Same(seed, DefaultMember(field));
        Assert.False(field.IsSet);
        Assert.Same(seed.AsReadOnly(), DefaultMember(Field(network.AsReadOnly(), "nearby")));

        var builder = DefaultNetwork.NewBuilder();
        var first = builder.Build();
        var second = builder.Build();
        Assert.Same(first.Nearby.Single(), second.Nearby.Single());
        Assert.NotSame(seed, first.Nearby.Single());

        network.Nearby.Clear();
        Assert.True(field.IsSet);
        field.Reset();
        Assert.False(field.IsSet);
        Assert.Same(seed, Assert.Single(network.Nearby));

        var copy = ModelSnapshot.Reflection.DeepCopy(network);
        var copiedSeed = Assert.Single(copy.Nearby);
        Assert.NotSame(seed, copiedSeed);
        Assert.Same(copiedSeed, DefaultMember(Field(copy, "nearby")));

        var book = LabelBook.NewBuilder().Build();
        var label = Assert.Single(book.Labels);
        var labels = Field(book, "labels");
        Assert.Equal("initial", label.Name);
        Assert.Same(label, DefaultMember(labels));
        Assert.False(labels.IsSet);
        Assert.False(labels.CanSetDefault);
    }

    [Fact]
    public void OmittedReferenceSetKeepsItsLocalDefault()
    {
        var network = ModelSnapshot.Read<DefaultNetwork>("{}");
        var field = Field(network, "nearby");
        Assert.Same(Assert.Single(network.Nearby), DefaultMember(field));
        Assert.False(field.IsSet);
        Assert.Throws<JsonException>(() => ModelSnapshot.Write(network));
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void StockDocumentsPreserveExplicitMembersAndEmptyReferenceSets(string format)
    {
        var seed = LabelBook.NewBuilder().Build();
        var configured = LabelBook.NewBuilder().WithLabels([new Label("explicit")]).Build();
        foreach (bool references in new[] { false, true })
        {
            var codec = SnapshotCodec<LabelBook>(format, references);
            Assert.Equal("initial", Assert.Single(codec.Read(codec.Write(seed)).Labels).Name);
            Assert.Equal("explicit", Assert.Single(codec.Read(codec.Write(configured)).Labels).Name);
        }

        var empty = DefaultNetwork.NewBuilder().WithNearby([]).Build();
        var emptyCodec = SnapshotCodec<DefaultNetwork>(format, references: true);
        var restored = emptyCodec.Read(emptyCodec.Write(empty));
        var field = Field(restored, "nearby");
        var resetTarget = DefaultMember(field);
        Assert.Empty(restored.Nearby);
        Assert.True(field.IsSet);
        field.Reset();
        Assert.Same(resetTarget, Assert.Single(restored.Nearby));
        Assert.False(field.IsSet);
    }

    [Fact]
    public void StockReciprocalDefaultsFollowTheSameLinksAsEachCopy()
    {
        var panel = new Panel();
        var link = new StationLink();
        Field(panel, "links").SetDefault(new[] { link });

        var deep = ModelSnapshot.Reflection.DeepCopy(panel);
        var deepLink = Assert.Single(deep.Links);
        Assert.NotSame(link, deepLink);
        Assert.Same(deep, Assert.Single(deepLink.Panels));
        Assert.Same(deepLink, DefaultMember(Field(deep, "links")));
        Assert.False(Field(deep, "links").IsSet);

        var shallow = ModelSnapshot.Reflection.ShallowCopy(panel);
        Assert.Same(link, Assert.Single(shallow.Links));
        Assert.Same(link, DefaultMember(Field(shallow, "links")));
        Assert.False(Field(shallow, "links").IsSet);
        Assert.True(link.Panels.Contains(panel));
        Assert.True(link.Panels.Contains(shallow));
    }

    private static ModelProperty Field(object model, string name) =>
        ModelSnapshot.Reflection.Reflect(model).FindProperty(name)!;

    private static object DefaultMember(ModelProperty field) =>
        Assert.Single(((IEnumerable)field.DefaultValue!).Cast<object>());

    private static (Func<T, string> Write, Func<string, T> Read) SnapshotCodec<T>(string format, bool references) where T : class =>
        (format, references) switch
        {
            ("json", false) => (ModelSnapshot.WritePlain<T>, ModelSnapshot.ReadPlain<T>),
            ("json", true) => (ModelSnapshot.Write, ModelSnapshot.Read<T>),
            ("yaml", false) => (ModelSnapshot.WritePlainYaml<T>, ModelSnapshot.ReadPlainYaml<T>),
            ("yaml", true) => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<T>),
            ("xml", false) => (ModelSnapshot.WritePlainXml<T>, ModelSnapshot.ReadPlainXml<T>),
            ("xml", true) => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<T>),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
}
