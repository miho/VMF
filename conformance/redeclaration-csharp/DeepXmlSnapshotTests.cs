using System.Collections;
using System.Xml;
using Eu.Mihosoft.Vmf.Conformance.Redeclarations;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class DeepXmlSnapshotTests
{
    // The model root and its value element contribute two of the 1,000 elements.
    private const int MaxValueNesting = 998;

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void SnapshotAtPhysicalElementDepthLimitRoundTrips(bool references)
    {
        string xml = Write(Create(MaxValueNesting), references);
        var restored = references ? ModelSnapshot.ReadXml<NullOption>(xml)
            : ModelSnapshot.ReadXmlWithoutReferences<NullOption>(xml);

        object? value = restored.Value;
        for (int depth = 0; depth < MaxValueNesting; depth++)
        {
            value = Assert.IsAssignableFrom<IDictionary>(value)["entry"];
        }
        Assert.Equal("leaf", Assert.IsType<string>(value));
        Assert.Equal(xml, Write(restored, references));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void SnapshotWriterRejectsOneMorePhysicalElement(bool references)
    {
        var error = Assert.Throws<XmlException>(() => Write(Create(MaxValueNesting + 1), references));
        Assert.Contains("maximum element depth", error.Message);
    }

    private static NullOption Create(int depth)
    {
        object value = "leaf";
        for (int index = 0; index < depth; index++)
        {
            value = new Dictionary<string, object> { ["entry"] = value };
        }
        return new NullOption { Value = value };
    }

    private static string Write(NullOption model, bool references) =>
        references ? ModelSnapshot.WriteXml(model) : ModelSnapshot.WriteXmlWithoutReferences(model);
}
