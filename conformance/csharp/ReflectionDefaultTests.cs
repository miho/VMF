using Eu.Mihosoft.Vmf.Conformance.Values;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ReflectionDefaultTests
{
    [Fact]
    public void PrimitiveDefaultsHaveTheDeclaredTypeAndResetTheLiveValue()
    {
        var sample = new ScalarSample();
        var reflection = ModelSnapshot.Reflection.Reflect(sample);
        var view = ModelSnapshot.Reflection.Reflect(sample.AsReadOnly());
        var cases = new (string Name, object Default, object Value)[]
        {
            ("active", false, true), ("byteValue", (sbyte)0, (sbyte)-8),
            ("shortValue", (short)0, (short)13), ("intValue", 0, 17),
            ("longValue", 0L, 23L), ("charValue", '\0', 'x'),
            ("floatValue", 0.0f, 1.5f), ("doubleValue", 0.0d, 2.5d),
        };
        foreach (var (name, expected, changed) in cases)
        {
            var field = reflection.FindProperty(name)!;
            var projected = view.FindProperty(name)!;
            Assert.Equal(expected, field.DefaultValue);
            Assert.Equal(expected, projected.DefaultValue);
            Assert.False(field.IsSet);
            Assert.False(projected.IsSet);
            field.Set(changed);
            Assert.True(field.IsSet);
            Assert.True(projected.IsSet);
            field.Reset();
            Assert.Equal(expected, field.Get());
            Assert.False(field.IsSet);
            Assert.False(projected.IsSet);
        }
    }

    [Fact]
    public void DeclaredDefaultsRestoreClearedAndInheritedValues()
    {
        var model = DefaultSettings.NewBuilder().WithName(null).WithLimit(null).Build();
        var reflection = ModelSnapshot.Reflection.Reflect(model);
        var name = reflection.FindProperty("name")!;
        var limit = reflection.FindProperty("limit")!;
        Assert.Equal("idle\nready", name.DefaultValue);
        Assert.Equal(7, limit.DefaultValue);
        Assert.True(name.IsSet);
        Assert.True(limit.IsSet);
        var edits = new List<string>();
        using var subscription = model.VmfChanges.AddLocalListener(edit => edits.Add(edit.PropertyName));
        name.Reset();
        limit.Reset();
        Assert.Equal("idle\nready", model.Name);
        Assert.Equal(7, model.Limit);
        Assert.False(name.IsSet);
        Assert.False(limit.IsSet);
        Assert.Equal(["name", "limit"], edits);
        Assert.Equal(long.MinValue,
            BitConverter.DoubleToInt64Bits(Assert.IsType<double>(reflection.FindProperty("baseline")!.DefaultValue)));
    }

    [Fact]
    public void ResetManagedFieldsUsesTheirLiveStorage()
    {
        var model = new CollectionProfile();
        model.Options.Add("empty", null);
        model.Levels.Add(-0.0);
        model.Counts.Add(2);
        var fields = ModelSnapshot.Reflection.Reflect(model);
        var map = fields.FindProperty("options")!;
        var set = fields.FindProperty("levels")!;
        var list = fields.FindProperty("counts")!;
        Assert.Empty(Assert.IsAssignableFrom<IReadOnlyDictionary<object, object?>>(map.DefaultValue));
        foreach (var field in new[] { map, set, list })
        {
            Assert.True(field.IsSet);
            field.Reset();
            field.Reset();
            Assert.False(field.IsSet);
        }
        Assert.Empty(model.Options);
        Assert.Empty(model.Levels);
        Assert.Empty(model.Counts);
    }

    [Fact]
    public void ReadOnlyDefaultsAreInspectableAndInverseResetDetaches()
    {
        var owner = new ArchiveOwner();
        var child = new ArchiveOwnedLeaf { Label = "part", Owner = owner };
        var field = ModelSnapshot.Reflection.Reflect(child.AsReadOnly()).FindProperty("owner")!;
        Assert.True(field.IsSet);
        Assert.Null(field.DefaultValue);
        Assert.Throws<InvalidOperationException>(field.Reset);

        ModelSnapshot.Reflection.Reflect(child).FindProperty("owner")!.Reset();
        Assert.Empty(owner.Nodes);
        Assert.Null(child.Owner);
        Assert.False(field.IsSet);
    }
}
