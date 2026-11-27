using Eu.Mihosoft.Vmftest.Setteridentity;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockSharedParentBuilderTests
{
    [Fact]
    public void CopyingAnAttachedChildImportsItsWritableValueAndKeepsTheOwners()
    {
        var sourceOwner = new BinaryExpression();
        var targetOwner = new BinaryExpression();
        var source = NumberExpression.NewBuilder().WithValue(12.5).Build();
        var target = NumberExpression.NewBuilder().WithValue(7.25).Build();
        sourceOwner.Left = source;
        targetOwner.Arguments.Add(target);
        var sourceView = source.AsReadOnly();
        var targetView = target.AsReadOnly();
        var events = new List<string>();
        using var sourceChanges = sourceOwner.VmfChanges.AddLocalListener(change => events.Add("source." + change.PropertyName));
        using var targetChanges = targetOwner.VmfChanges.AddLocalListener(change => events.Add("target." + change.PropertyName));
        using var memberChanges = target.VmfChanges.AddLocalListener(change => events.Add("member." + change.PropertyName));

        var staged = NumberExpression.NewBuilder().ApplyFrom(source);
        Assert.Empty(events);
        staged.ApplyTo(target);
        Assert.Equal(["member.value"], events);
        Assert.Equal(12.5, target.Value);
        Assert.Equal(12.5, targetView.Value);
        Assert.Same(source, sourceOwner.Left);
        Assert.Same(target, targetOwner.Arguments[0]);
        Assert.Same(sourceOwner, source.Parent);
        Assert.Same(targetOwner, target.Parent);
        Assert.Same(sourceOwner.AsReadOnly(), sourceView.Parent);
        Assert.Same(targetOwner.AsReadOnly(), targetView.Parent);

        var built = staged.Build();
        Assert.Equal(12.5, built.Value);
        Assert.Null(built.Parent);
        Assert.Null(built.AsReadOnly().Parent);
        Assert.Equal(["member.value"], events);
    }

    [Fact]
    public void AncestorImportsDoNotStageAnOwnerOrReplaceAlreadyStagedValues()
    {
        var owner = new BinaryExpression();
        var child = NumberExpression.NewBuilder().WithValue(3).Build();
        owner.Right = child;
        Expression expression = child;
        Element element = child;
        var built = NumberExpression.NewBuilder().WithValue(17)
            .ApplyFrom(expression).ApplyFrom(element).Build();
        Assert.Equal(17, built.Value);
        Assert.Null(built.Parent);
        Assert.Same(owner, child.Parent);
        Assert.Same(child, owner.Right);
        Assert.Null(typeof(NumberExpression).GetProperty(nameof(NumberExpression.Parent))!.SetMethod);
        Assert.Null(typeof(NumberExpression.Builder).GetMethod("WithParent"));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void AbstractOwnedChildrenUseTheirSharedInverseWhenImported(bool append)
    {
        var number = new NumberExpression { Value = 1 };
        var nested = new BinaryExpression { Operator = "nested" };
        var left = new NumberExpression();
        var right = new BinaryExpression();
        var targetLeft = new NumberExpression();
        var targetRight = new BinaryExpression();
        var previous = new NumberExpression();
        var source = BinaryExpression.NewBuilder().WithOperator("source").WithLeft(left).WithRight(right)
            .WithArguments([number, null, nested, number]).Build();
        var target = BinaryExpression.NewBuilder().WithOperator("target").WithLeft(targetLeft).WithRight(targetRight)
            .WithArguments([previous]).Build();
        var view = target.AsReadOnly();
        var events = new List<string>();
        using var observe = target.VmfChanges.AddLocalListener(change => events.Add(change.PropertyName));
        Assert.Equal([null, nested, number], source.Arguments);

        var staged = BinaryExpression.NewBuilder().AppendCollections(append).ApplyFrom(source);
        Assert.Empty(events);
        Assert.Same(source, number.Parent);
        Assert.Same(source, nested.Parent);
        staged.ApplyTo(target);
        Assert.Same(targetLeft, target.Left);
        Assert.Same(targetRight, target.Right);
        Assert.Same(left, source.Left);
        Assert.Same(right, source.Right);
        Assert.Equal(append ? [previous, null, nested, number] : [null, nested, number], target.Arguments);
        Assert.Equal([null], source.Arguments);
        Assert.Same(target, number.Parent);
        Assert.Same(target, nested.Parent);
        Assert.Same(append ? target : null, previous.Parent);
        Assert.Same(number.AsReadOnly(), view.Arguments[append ? 3 : 2]);
        Assert.Equal("source", target.Operator);
        Assert.Equal(append ? ["arguments", "operator"] : ["arguments", "arguments", "operator"], events);

        var built = staged.Build();
        Assert.Same(left, built.Left);
        Assert.Same(right, built.Right);
        Assert.Null(source.Left);
        Assert.Null(source.Right);
        Assert.Equal([null, nested, number], built.Arguments);
        Assert.Same(built, number.Parent);
        Assert.Same(built, nested.Parent);
        Assert.Equal(append ? [previous, null] : [null], target.Arguments);
    }

    [Fact]
    public void AbstractOwningBuilderChecksCyclesBeforeChangingAnyTargetProperty()
    {
        var source = new BinaryExpression();
        var ancestor = new BinaryExpression();
        var previous = new NumberExpression();
        var target = BinaryExpression.NewBuilder().WithOperator("unchanged").WithArguments([previous]).Build();
        ancestor.Left = target;
        source.Arguments.Add(ancestor);
        var events = new List<string>();
        using var observe = target.VmfChanges.AddLocalListener(change => events.Add(change.PropertyName));

        var staged = BinaryExpression.NewBuilder().AppendCollections(false).ApplyFrom(source).WithOperator("forbidden");
        Assert.Throws<ArgumentException>(() => staged.ApplyTo(target));
        Assert.Equal("unchanged", target.Operator);
        Assert.Same(previous, Assert.Single(target.Arguments));
        Assert.Same(target, previous.Parent);
        Assert.Same(target, ancestor.Left);
        Assert.Same(ancestor, target.Parent);
        Assert.Same(source, ancestor.Parent);
        Assert.Empty(events);
    }
}
