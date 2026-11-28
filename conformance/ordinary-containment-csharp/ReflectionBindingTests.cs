using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

/// <summary>A reflection of one object hands out one <see cref="ModelProperty"/> per field, however it is asked.</summary>
public class ReflectionBindingTests
{
    [Fact]
    public void AReflectionBindsEachPropertyOnce() =>
        RequireStableBinding(ModelSnapshot.Reflection, new LegacyContainer { Name = "root" });

    internal static void RequireStableBinding(ModelReflectionRegistry registry, object model)
    {
        var reflection = registry.Reflect(model);
        string name = registry.Reflect(model).Properties[^1].Name;

        // Found by name first, then listed.
        var found = reflection.FindProperty(name);
        Assert.NotNull(found);
        Assert.Same(found, reflection.FindProperty(name));
        var all = reflection.Properties;
        Assert.Same(all, reflection.Properties);
        Assert.Same(found, all[^1]);
        Assert.All(all, property => Assert.Same(property, reflection.FindProperty(property.Name)));
        Assert.Equal(all.Count, all.Select(property => property.Name).Distinct().Count());

        // Listed first, then found by name.
        var listed = registry.Reflect(model);
        var first = listed.Properties[0];
        Assert.Same(first, listed.FindProperty(first.Name));

        Assert.Null(reflection.FindProperty(name.ToUpperInvariant()));
        Assert.Null(reflection.FindProperty("no such property"));
        Assert.Throws<ArgumentNullException>(() => reflection.FindProperty(null!));

        // Every reflection binds properties of its own.
        Assert.NotSame(found, registry.Reflect(model).FindProperty(name));
    }
}
