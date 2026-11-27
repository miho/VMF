using Eu.Mihosoft.Vmf.Conformance.Equality;
using Vmf.Runtime;
using Xunit;
using External = Eu.Mihosoft.Vmf.Conformance.External;

namespace Vmf.CSharp.Conformance;

public sealed class ModelPatchUndoIdentityTests
{
    [Fact]
    public void SingularModelUndoRejectsEqualContentReplacements()
    {
        var ordinary = new EqualityNode { Name = "root" };
        var ordinaryDesired = new EqualityNode { Name = "root", Next = new EqualityNode { Name = "equal" } };
        CheckLink(ordinary, ordinaryDesired, () => ordinary.Next, value => ordinary.Next = value, "next");

        var owner = new EqualityContainer();
        var ownerDesired = new EqualityContainer { Child = new EqualityNode { Name = "equal" } };
        CheckLink(owner, ownerDesired, () => owner.Child, value => owner.Child = value, "child");
    }

    [Fact]
    public void ScalarAndExternalUndoAcceptEqualValues()
    {
        var text = new EqualityNode { Name = "before" };
        var scalarPatch = ModelSnapshot.Reflection.Diff(text, new EqualityNode { Name = "after" });
        scalarPatch.Apply(text);
        string assigned = text.Name!;
        text.Name = "other";
        text.Name = new string(assigned.ToCharArray());
        Assert.NotSame(assigned, text.Name);
        scalarPatch.Undo(text);
        Assert.Equal("before", text.Name);

        var external = new External.ExternalValueEntry();
        string id = "8df6dcb5-21de-42b4-868e-e4e4032eddc5";
        var externalPatch = External.ModelSnapshot.Reflection.Diff(external, new External.ExternalValueEntry { SourceId = Guid.Parse(id) });
        externalPatch.Apply(external);
        external.SourceId = null;
        external.SourceId = Guid.Parse(id);
        externalPatch.Undo(external);
        Assert.Null(external.SourceId);
    }

    private static void CheckLink(object source, object desired, Func<EqualityNode?> current,
        Action<EqualityNode?> replace, string property)
    {
        var reflection = ModelSnapshot.Reflection;
        var patch = reflection.Diff(source, desired);
        Assert.Equal(property, Assert.Single(patch.Changes).PropertyName);
        patch.Apply(source);
        var applied = Assert.IsType<EqualityNode>(current());
        Assert.Empty(reflection.Diff(source, source).Changes);
        Assert.Empty(reflection.Diff(source, reflection.DeepCopy(source)).Changes);

        var different = reflection.DeepCopy(applied);
        Assert.Equal(applied, different);
        Assert.NotSame(applied, different);
        replace(different);
        Assert.Same(different, current());
        Assert.Throws<InvalidOperationException>(() => patch.Undo(source));
        Assert.Same(different, current());

        replace(applied);
        patch.Undo(source);
        Assert.Null(current());
    }
}
