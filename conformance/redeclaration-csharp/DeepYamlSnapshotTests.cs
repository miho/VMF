using System.Collections;
using Eu.Mihosoft.Vmf.Conformance.Redeclarations;
using Xunit;
using YamlDotNet.Core;

namespace Vmf.CSharp.Conformance;

public sealed class DeepYamlSnapshotTests
{
    // The generated model contributes one mapping to the document's 1,000-container limit.
    private const int MaxValueNesting = 999;

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void SnapshotAtContainerDepthLimitRoundTrips(bool references)
    {
        object value = 17;
        for (int depth = 0; depth < MaxValueNesting; depth++)
        {
            value = new List<object> { value };
        }
        var model = new NullOption { Value = value };

        string yaml = references ? ModelSnapshot.WriteYaml(model) : ModelSnapshot.WriteYamlWithoutReferences(model);
        object? restored = Read(yaml, references).Value;
        for (int depth = 0; depth < MaxValueNesting; depth++)
        {
            restored = Assert.Single(Assert.IsAssignableFrom<IList>(restored));
        }
        Assert.Equal(17, Assert.IsType<int>(restored));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void SnapshotRejectsAnExtraContainerEvenWhenEmpty(bool references)
    {
        string arrays = "{\"value\":" + new string('[', MaxValueNesting) + "[]" + new string(']', MaxValueNesting) + "}";
        string maps = "{\"value\":" + string.Concat(Enumerable.Repeat("{\"key\":", MaxValueNesting)) + "{}"
            + new string('}', MaxValueNesting) + "}";
        foreach (string yaml in new[] { arrays, maps })
        {
            var failure = Assert.Throws<YamlException>(() => Read(yaml, references));
            Assert.Contains("nesting depth", failure.Message);
        }
    }

    private static NullOption Read(string yaml, bool references) =>
        references ? ModelSnapshot.ReadYaml<NullOption>(yaml) : ModelSnapshot.ReadYamlWithoutReferences<NullOption>(yaml);
}
