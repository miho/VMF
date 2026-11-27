using System.Collections;
using Eu.Mihosoft.Vmftest.Complex.Vmf_text.Generated.Miniclang;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockMiniClangTests
{
    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void OriginalTypedLiteralAndWritablePayloadUseTheirDeclaredStorage(string format)
    {
        var expression = new IntExpression
        {
            Value = 7,
            Payload = new Dictionary<string, object?> { ["origin"] = "fixture" },
        };
        var (write, read) = Codecs(format);
        var restored = read(write(expression));
        IReadOnlyConstExpression constant = restored.AsReadOnly();
        Assert.Equal(7, Assert.IsType<int>(constant.Value));
        Assert.Equal("fixture", Assert.IsAssignableFrom<IDictionary>(restored.Payload)["origin"]);
    }

    [Fact]
    public void OriginalArrayRoleContractsShareTheListAndCanCallInheritedBehavior()
    {
        var array = new ArrayDeclStatement();
        var declaration = Assert.IsAssignableFrom<IDeclStatement>(array);
        var sizes = Assert.IsAssignableFrom<IWithArraySizes>(array);
        Assert.Same(declaration.ArraySizes, sizes.ArraySizes);
        declaration.ArraySizes.AddRange(["4", null, "4"]);
        Assert.Equal(new string?[] { "4", null, "4" }, sizes.ArraySizes);
        Assert.Null(new BlockStatement().ParentScopes());
    }

    private static (Func<IntExpression, string> Write, Func<string, IntExpression> Read) Codecs(string format) => format switch
    {
        "xml" => (ModelSnapshot.WritePlainXml, ModelSnapshot.ReadPlainXml<IntExpression>),
        "yaml" => (ModelSnapshot.WritePlainYaml, ModelSnapshot.ReadPlainYaml<IntExpression>),
        _ => (ModelSnapshot.WritePlain, ModelSnapshot.ReadPlain<IntExpression>),
    };
}
