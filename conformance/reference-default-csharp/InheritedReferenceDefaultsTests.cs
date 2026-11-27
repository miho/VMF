using System.Collections;
using Eu.Mihosoft.Vmf.Conformance.Referencedefaults;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class InheritedReferenceDefaultsTests
{
    [Fact]
    public void InheritedProvidersPreserveConcreteCopiesBuilderCachesAndSnapshotPresence()
    {
        InheritedReferenceDefaultBindings.Events.Clear();
        var basis = new InheritedSelection();
        Calls("queue", "saved");
        var middle = new CuratedSelection();
        Calls("queue", "saved");
        var source = new PublishedSelection();
        Calls("queue", "saved");
        var queue = Field(source, "queue");
        var cache = queue.DefaultValue;
        var original = source.Queue[0];
        var saved = Assert.Single(source.Saved);
        Assert.Same(original, Members(queue)[0]);
        Assert.Same(source.Queue[1], Members(queue)[1]);
        Assert.NotSame(original, Members(Field(basis, "queue"))[0]);
        Assert.NotSame(original, Members(Field(middle, "queue"))[0]);
        Assert.Same(saved, Assert.Single(Members(Field(source, "saved"))));
        Assert.False(queue.IsSet);
        original.Link = saved;
        Assert.Same(original.AsReadOnly(), Members(Field(source.AsReadOnly(), "queue"))[0]);

        var deep = ModelSnapshot.Reflection.DeepCopy(source);
        var shallow = ModelSnapshot.Reflection.ShallowCopy(source);
        Calls();
        Assert.NotSame(original, deep.Queue[0]);
        Assert.Same(deep.Queue[0], Members(Field(deep, "queue"))[0]);
        Assert.Same(Assert.Single(deep.Saved), deep.Queue[0].Link);
        Assert.Same(cache, Field(shallow, "queue").DefaultValue);
        Assert.Same(original, shallow.Queue[0]);
        Assert.NotSame(source.Queue, shallow.Queue);
        Assert.Equal("curated", deep.Label);

        var builder = PublishedSelection.NewBuilder().WithQueue([]);
        Calls("queue", "saved");
        var first = builder.Build();
        var second = builder.Build();
        Calls();
        Assert.Empty(first.Queue);
        Assert.Empty(second.Queue);
        Assert.Same(Field(first, "queue").DefaultValue, Field(second, "queue").DefaultValue);

        builder.AppendCollections(false).ApplyFrom(basis).ApplyTo(source);
        Calls();
        Assert.Same(basis.Queue[0], source.Queue[0]);
        Assert.Same(basis.Queue[1], source.Queue[1]);
        Assert.Same(Assert.Single(basis.Saved), Assert.Single(source.Saved));
        Assert.True(queue.IsSet);
        Assert.Same(cache, queue.DefaultValue);
        Assert.Equal("curated", source.Label);
        queue.Reset();
        Assert.Same(original, source.Queue[0]);
        Calls();

        var absent = ModelSnapshot.Read<PublishedSelection>("{}");
        Calls("queue", "saved");
        Assert.Equal(2, absent.Queue.Count);
        Assert.Single(absent.Saved);
        Assert.False(Field(absent, "queue").IsSet);
        var empty = ModelSnapshot.Read<PublishedSelection>("""{"queue":[],"saved":[]}""");
        Calls("queue", "saved");
        Assert.Empty(empty.Queue);
        Assert.Empty(empty.Saved);
        Assert.True(Field(empty, "queue").IsSet);
        Assert.Equal(2, Members(Field(empty, "queue")).Length);
    }

    private static ModelProperty Field(object owner, string name) =>
        ModelSnapshot.Reflection.Reflect(owner).FindProperty(name)!;

    private static object[] Members(ModelProperty field) => ((IEnumerable)field.DefaultValue!).Cast<object>().ToArray();

    private static void Calls(params string[] expected)
    {
        Assert.Equal(expected, InheritedReferenceDefaultBindings.Events);
        InheritedReferenceDefaultBindings.Events.Clear();
    }
}
