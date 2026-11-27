using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ReflectionAnnotationTests
{
    [Fact]
    public void RepeatedAnnotationsPreserveOrderThroughInheritanceAndProjection()
    {
        var model = new DefaultSettings();
        foreach (object view in new object[] { model, model.AsReadOnly() })
        {
            var reflection = ModelSnapshot.Reflection.Reflect(view);
            Assert.Equal(new[] { "purpose", "tag", "tag" }, reflection.Annotations.Select(item => item.Key));
            Assert.Equal("runtime configuration", reflection.AnnotationByKey("purpose")?.Value);
            Assert.Equal(new[] { "first", "second" }, reflection.AnnotationsByKey("tag").Select(item => item.Value));
            Assert.Null(reflection.AnnotationByKey("Tag"));
            var name = reflection.FindProperty("name")!;
            Assert.Equal(new[] { "role", "role", "vmf:jackson:rename", "vmf:property:containment-info" },
                name.Annotations.Select(item => item.Key));
            Assert.Equal("label", name.AnnotationByKey("role")?.Value);
            Assert.Equal(new[] { "label", "text" }, name.AnnotationsByKey("role").Select(item => item.Value));
            Assert.Throws<NotSupportedException>(() => ((IList<ModelAnnotation>)name.Annotations)
                .Add(new ModelAnnotation("role", "invalid")));
        }
    }

    [Fact]
    public void GeneratedAnnotationsReflectRelationshipAndImmutableKinds()
    {
        var archive = ModelSnapshot.Reflection.Reflect(new ArchiveNode());
        Assert.Equal("container:children", archive.FindProperty("parent")!
            .AnnotationByKey("vmf:property:containment-info")?.Value);
        Assert.Equal("", archive.FindProperty("anchor")!.AnnotationByKey("vmf:property:reference-opposite")?.Value);
        var inventory = ModelSnapshot.Reflection.Reflect(new KeyedInventory());
        Assert.Equal("sku", inventory.FindProperty("products")!.AnnotationByKey("vmf:collection:key")?.Value);
        var label = ModelSnapshot.Reflection.Reflect(ValueLabel.NewBuilder().Build());
        Assert.NotNull(label.AnnotationByKey("vmf:type:immutable"));
    }
}
