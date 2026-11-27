using System.Collections;
using System.Text.Json;
using Eu.Mihosoft.Vmftest.Externaltypes;
using Eu.Mihosoft.Vmftest.Externaltypes.Host;
using Xunit;

namespace Vmf.Conformance;

public sealed class StockRawExternalTypesTests
{
    [Fact]
    public void CallbackAndReadOnlyExternalAliasesStayLive()
    {
        var model = new Model();
        var raw = new ArrayList { "initial" };
        model.List = raw;
        model.RunAction(caller =>
        {
            Assert.Same(model, caller);
            caller.Lists.Add(raw);
        });

        var view = model.AsReadOnly();
        Assert.Same(raw, view.List);
        Assert.Same(raw, view.Lists[0]);
        raw.Add("live");
        Assert.Equal("live", view.List![1]);
        Assert.IsNotAssignableFrom<IList<ArrayList?>>(view.Lists);

        var image = new BufferedImage(2, 1);
        model.BImage = image;
        model.Image = image;
        model.BImages.Add(image);
        model.Images.Add(image);
        Assert.Same(image, view.BImage);
        Assert.Same(image, view.Image);
        Assert.Same(image, view.BImages[0]);
        Assert.Same(image, view.Images[0]);
        Assert.Throws<JsonException>(() => ModelSnapshot.Write(model));
    }

    [Fact]
    public void StockRawListsRoundTripInBothJsonAndYamlProfiles()
    {
        var model = new Model { Name = "stock" };
        var raw = new ArrayList { "001", 7, null, true, new ArrayList { "nested" },
            new Dictionary<string, object?> { ["enabled"] = false }, "live" };
        model.List = raw;
        model.Lists.AddRange(new ArrayList?[] { raw, null });
        var expected = JsonSerializer.Serialize(raw);
        var exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");

        CheckProfile("true.json", ModelSnapshot.Write(model), ModelSnapshot.Read<Model>);
        CheckProfile("false.json", ModelSnapshot.WriteWithoutReferences(model), ModelSnapshot.ReadWithoutReferences<Model>);
        CheckProfile("true.yaml", ModelSnapshot.WriteYaml(model), ModelSnapshot.ReadYaml<Model>);
        CheckProfile("false.yaml", ModelSnapshot.WriteYamlWithoutReferences(model), ModelSnapshot.ReadYamlWithoutReferences<Model>);

        void CheckProfile(string suffix, string document, Func<string, Model> read)
        {
            Verify(read(document));
            if (exchange is null)
                return;
            File.WriteAllText(Path.Combine(exchange, "csharp-stock-raw-lists-" + suffix), document);
            Verify(read(File.ReadAllText(Path.Combine(exchange, "java-stock-raw-lists-" + suffix))));
        }

        void Verify(Model restored)
        {
            Assert.Equal("stock", restored.Name);
            Assert.NotNull(restored.List);
            Assert.Equal(expected, JsonSerializer.Serialize(restored.List));
            Assert.Collection(restored.Lists, first =>
            {
                Assert.NotNull(first);
                Assert.Equal(expected, JsonSerializer.Serialize(first));
                Assert.NotSame(restored.List, first);
            }, Assert.Null);
        }
    }

    [Theory]
    [InlineData("{\"list\":{}}")]
    [InlineData("{\"lists\":{}}")]
    [InlineData("{\"lists\":[{}]}")]
    [InlineData("{\"lists\":[7]}")]
    public void InvalidRawListShapesAreRejected(string document)
    {
        Assert.Throws<JsonException>(() => ModelSnapshot.Read<Model>(document));
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadWithoutReferences<Model>(document));
    }
}
