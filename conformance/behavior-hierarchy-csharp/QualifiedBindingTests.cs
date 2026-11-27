using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class QualifiedBindingTests
{
    [Fact]
    public void SameSimpleBindingNameKeepsSeparateLocalAndSharedProviders()
    {
        QualifiedBindingState.LocalColdCreated = QualifiedBindingState.LocalHotCreated = 0;
        QualifiedBindingState.SharedColdCreated = QualifiedBindingState.SharedHotCreated = 0;
        var local = new QualifiedLocal();
        var shared = new QualifiedShared { Name = "qualified" };
        Assert.Equal(7, local.Level);
        Assert.Equal((1, 0, 0, 0), ProviderCounts());

        Assert.Equal(101, local.CoolLocal());
        Assert.Equal(1, local.WarmLocal());
        Assert.Equal(102, local.CoolLocal());
        Assert.Equal(2, local.WarmLocal());
        Assert.Equal(1, shared.Cool());
        Assert.Equal(1, ((IQualifiedHotRole)shared).WarmAgain());
        Assert.Equal(2, ((IQualifiedColdRole)shared).CoolAgain());
        Assert.Equal(2, ((IQualifiedHot)shared).Warm());
        Assert.Equal(3, shared.Cool());
        Assert.Equal((1, 1, 1, 1), ProviderCounts());

        var archive = new QualifiedBindingArchive { Local = local, Shared = shared, Selected = shared };
        var exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { false, true })
        {
            string suffix = $"-qualified-bindings-{(references ? "true" : "false")}.json";
            string wire = references ? ModelSnapshot.Write(archive) : ModelSnapshot.WriteWithoutReferences(archive);
            if (exchange is not null)
            {
                string java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }
            var restored = references
                ? ModelSnapshot.Read<QualifiedBindingArchive>(wire)
                : ModelSnapshot.ReadWithoutReferences<QualifiedBindingArchive>(wire);
            Assert.Equal(101, restored.Local!.CoolLocal());
            Assert.Equal(1, restored.Local.WarmLocal());
            Assert.Equal(1, restored.Shared!.CoolAgain());
            Assert.Equal(1, restored.Shared.Warm());
            if (references) Assert.Same(restored.Shared, restored.Selected);
            else Assert.Null(restored.Selected);
        }
    }

    private static (int, int, int, int) ProviderCounts() => (
        QualifiedBindingState.LocalColdCreated, QualifiedBindingState.LocalHotCreated,
        QualifiedBindingState.SharedColdCreated, QualifiedBindingState.SharedHotCreated);
}
