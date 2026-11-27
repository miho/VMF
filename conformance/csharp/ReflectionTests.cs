using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ReflectionTests
{
    private static ModelReflectionRegistry Reflection => ModelSnapshot.Reflection;

    [Fact]
    public void InheritedLogicalPropertiesUseJavaOrderAndRemainLiveInReadOnlyViews()
    {
        var settings = new DefaultSettings();
        var model = Reflection.Reflect(settings);
        var liveView = Reflection.Reflect(settings.AsReadOnly());
        Assert.Equal("eu.mihosoft.vmf.conformance.values.DefaultSettings", model.TypeName);
        Assert.Equal(typeof(DefaultSettings), model.RuntimeType);
        Assert.False(model.IsReadOnly);
        Assert.True(liveView.IsReadOnly);
        Assert.False(liveView.IsImmutable);
        Assert.Equal(["baseline", "entries", "flag", "limit", "mode", "name", "note", "ratio", "sequence", "stages", "unsetValue"],
            model.Properties.Select(property => property.Name));
        Assert.Null(model.FindProperty("Name"));
        Assert.Null(model.FindProperty("display_name"));

        var name = Required(model, "name");
        var readOnlyName = Required(liveView, "name");
        Assert.Equal(typeof(string), name.ValueType);
        Assert.Equal("display_name", name.WireName);
        Assert.Equal("eu.mihosoft.vmf.conformance.values.DefaultBase", name.DeclaringType);
        Assert.Equal("idle\nready", name.Get());
        Assert.True(name.CanWrite);
        Assert.False(readOnlyName.CanWrite);
        var changed = new List<ModelChange>();
        using var watch = settings.VmfChanges.AddPropertyListener("name", changed.Add);
        name.Set("updated");
        Assert.Equal("updated", readOnlyName.Get());
        Assert.Equal("updated", settings.Name);
        Assert.Equal("updated", Assert.Single(changed).NewValue);
        Assert.Throws<InvalidOperationException>(() => readOnlyName.Set("invalid"));
    }

    [Fact]
    public void MetadataDescribesManagedCollectionsAndKeepsTheirReadonlyAccessors()
    {
        var profile = new CollectionProfile();
        var limits = Required(Reflection.Reflect(profile), "limits");
        Assert.Equal(SnapshotPropertyKind.ScalarMap, limits.Kind);
        Assert.Equal(typeof(string), limits.KeyType);
        Assert.Equal(typeof(long?), limits.ValueType);
        Assert.Equal(1, limits.LowerBound);
        Assert.Equal(3, limits.UpperBound);
        Assert.True(limits.IsUnique);
        Assert.True(limits.IsCollection);
        Assert.Same(profile.Limits, limits.Get());

        var readOnlyLimits = Required(Reflection.Reflect(profile.AsReadOnly()), "limits");
        var projected = Assert.IsAssignableFrom<IReadOnlyDictionary<string, long?>>(readOnlyLimits.Get());
        Assert.IsNotType<ScalarMap<string, long?>>(projected);
        profile.Limits.Add("current", null);
        Assert.True(projected.ContainsKey("current"));

        var products = Required(Reflection.Reflect(new KeyedInventory()), "products");
        Assert.True(products.IsContainment);
        Assert.True(products.IsOrdered);
        Assert.False(products.IsUnique);
        Assert.Equal(-1, products.UpperBound);
        Assert.Equal("sku", products.KeyPropertyName);
        Assert.Equal("items", products.WireName);
        Assert.Equal("eu.mihosoft.vmf.conformance.values.KeyedInventoryBase", products.DeclaringType);
    }

    [Fact]
    public void InverseFieldsReflectBothViewsButAreNeverTraversalEdges()
    {
        var root = Named("root");
        var child = Named("child");
        root.Children.Add(child);
        var parent = Required(Reflection.Reflect(child), "parent");
        Assert.True(parent.IsContainer);
        Assert.False(parent.IsContainment);
        Assert.Null(parent.WireName);
        Assert.Same(root, parent.Get());
        Assert.Same(root.AsReadOnly(), Required(Reflection.Reflect(child.AsReadOnly()), "parent").Get());
        Assert.Same(child, Assert.Single(Reflection.Traverse(child, ModelTraversalStrategy.UniqueNode)));
        parent.Set(null);
        Assert.Empty(root.Children);
        Assert.Null(child.Parent);
    }

    [Fact]
    public void TraversalMatchesJavaOrderAndDeduplicatesTheReadOnlyRootOnBackEdges()
    {
        var root = Named("root");
        var branch = Named("branch");
        var shared = Named("shared");
        var inset = Named("inset");
        var outside = Named("outside");
        root.Children.Add(branch);
        branch.Children.Add(shared);
        root.Inset = inset;
        root.Anchor = outside;
        inset.Anchor = branch;
        root.Trail.AddRange([inset, null, shared]);

        var view = root.AsReadOnly();
        foreach (object input in new object[] { root, view })
        {
            Assert.Equal(["root", "branch", "shared", "inset"], Names(input, ModelTraversalStrategy.ContainmentTree));
            Assert.Equal(["root", "outside", "branch", "shared", "inset"], Names(input, ModelTraversalStrategy.UniqueNode));
            Assert.Equal(["root", "outside", "branch", "shared", "inset", "branch", "inset", "shared"],
                Names(input, ModelTraversalStrategy.UniqueProperty));
        }
        Assert.All(Reflection.Traverse(view), node => Assert.IsAssignableFrom<IReadOnlyArchiveNode>(node));
        Assert.DoesNotContain(Reflection.Traverse(view), node => node is ArchiveNode);

        outside.Anchor = root;
        Assert.Equal(["root", "outside", "branch", "shared", "inset"], Names(view, ModelTraversalStrategy.UniqueNode));
        Assert.Same(view, Reflection.Traverse(view, ModelTraversalStrategy.UniqueNode).First());
        foreach (object input in new object[] { root, view })
        {
            var events = new List<string>();
            Reflection.Visit(input, model => AddVisit("+", model), model => AddVisit("-", model),
                ModelTraversalStrategy.UniqueProperty);
            Assert.Equal(["+root", "+outside", "-outside", "+branch", "+shared", "-shared",
                "-branch", "+inset", "-inset", "-root"], events);

            void AddVisit(string prefix, object model)
            {
                if (ReferenceEquals(input, view)) Assert.IsAssignableFrom<IReadOnlyArchiveNode>(model);
                events.Add(prefix + Required(Reflection.Reflect(model), "name").Get());
            }
        }
    }

    [Fact]
    public void UniqueNodeTraversalKeepsByValueImmutableOccurrences()
    {
        var value = new ValueLabel("same");
        var root = new ValueSelection { Current = value };
        root.Selected.Add(value);
        var visited = Reflection.Traverse(root, ModelTraversalStrategy.UniqueNode).ToArray();
        Assert.Equal(3, visited.Length);
        Assert.Same(root, visited[0]);
        Assert.Same(value, visited[1]);
        Assert.Same(value, visited[2]);

        var events = new List<(bool Enter, object Model)>();
        Reflection.Visit(root, model => events.Add((true, model)), model => events.Add((false, model)));
        Assert.Collection(events,
            item => CheckVisit(item, true, root), item => CheckVisit(item, true, value),
            item => CheckVisit(item, false, value), item => CheckVisit(item, true, value),
            item => CheckVisit(item, false, value), item => CheckVisit(item, false, root));

        static void CheckVisit((bool Enter, object Model) item, bool enter, object model)
        {
            Assert.Equal(enter, item.Enter);
            Assert.Same(model, item.Model);
        }

        var metadata = Reflection.Reflect(value);
        Assert.True(metadata.IsImmutable);
        Assert.True(metadata.IsReadOnly);
        var text = Required(metadata, "text");
        Assert.Equal("same", text.Get());
        Assert.False(text.CanWrite);
    }

    private static ArchiveNode Named(string name) => ArchiveNode.NewBuilder().WithName(name).Build();

    private static ModelProperty Required(ModelReflection model, string name) =>
        Assert.IsType<ModelProperty>(model.FindProperty(name));

    private static IEnumerable<string?> Names(object root, ModelTraversalStrategy strategy) =>
        Reflection.Traverse(root, strategy).Select(node => (string?)Required(Reflection.Reflect(node), "name").Get());
}
