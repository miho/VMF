using Eu.Mihosoft.Vmftest.Setteridentity;
using Vmf.Runtime;
using Xunit;
using JournalDocument = Eu.Mihosoft.Vmf.Jackson.Test.Journal.Document;
using JournalDocumentSnapshot = Eu.Mihosoft.Vmf.Jackson.Test.Journal.ModelSnapshot;
using JournalItem = Eu.Mihosoft.Vmf.Jackson.Test.Journal.Item;

namespace Vmf.CSharp.Conformance;

public sealed class StockOwningSortTests
{
    [Fact]
    public void SortingDistinctEqualChildrenPublishesSparseChangesOnBothViewsAndUndoesByIdentity()
    {
        var parent = new BinaryExpression();
        var first = new NumberExpression { Value = 1 };
        var second = new NumberExpression { Value = 1 };
        var third = new NumberExpression { Value = 2 };
        var stationary = new NumberExpression { Value = 3 };
        Assert.Equal(first, second);
        Assert.NotSame(first, second);
        parent.Arguments.AddRange([first, stationary, second, third]);

        var projectedList = parent.AsReadOnly().Arguments;
        var mutableEvents = new List<ModelListChange<Expression?>>();
        var projectedEvents = new List<ModelListChange<IReadOnlyExpression?>>();
        using var mutable = parent.Arguments.AddListener(mutableEvents.Add);
        using var projected = projectedList.AddListener(projectedEvents.Add);
        var ranks = new Dictionary<Expression, int>(ReferenceEqualityComparer.Instance)
        {
            [second] = 0, [stationary] = 1, [third] = 2, [first] = 3,
        };

        parent.VmfChanges.Start();
        parent.Arguments.Sort((left, right) => ranks[left!].CompareTo(ranks[right!]));
        var transaction = parent.VmfChanges.PublishTransaction();
        parent.VmfChanges.Stop();

        AssertOrder(second, stationary, third, first);
        var edit = Assert.Single(mutableEvents);
        Assert.Same(parent.Arguments, edit.Source);
        Assert.Equal([0, 2, 3], edit.Removed.Select(slot => slot.Index));
        Assert.Equal([0, 2, 3], edit.Added.Select(slot => slot.Index));
        Assert.Same(first, edit.Removed[0].Value);
        Assert.Same(second, edit.Added[0].Value);
        var viewEdit = Assert.Single(projectedEvents);
        Assert.Same(projectedList, viewEdit.Source);
        Assert.Equal([0, 2, 3], viewEdit.Added.Select(slot => slot.Index));
        Assert.Same(first.AsReadOnly(), viewEdit.Removed[0].Value);
        Assert.Same(second.AsReadOnly(), viewEdit.Added[0].Value);
        var change = Assert.IsType<ModelCollectionChange>(Assert.Single(transaction!.Changes));
        Assert.Equal([0, 2, 3], change.Added.Select(slot => slot.Index!.Value));

        var newStationary = new NumberExpression { Value = 42 };
        parent.Arguments[1] = newStationary;
        Assert.True(transaction.Undo());
        AssertOrder(first, newStationary, second, third);
        change.Apply(parent);
        AssertOrder(second, newStationary, third, first);

        void AssertOrder(params NumberExpression[] expected)
        {
            Assert.Equal(expected.Length, parent.Arguments.Count);
            for (int index = 0; index < expected.Length; index++)
            {
                Assert.Same(expected[index], parent.Arguments[index]);
                Assert.Same(parent, expected[index].Parent);
            }
        }
    }

    [Fact]
    public void StableSortingKeepsTiesAndCompletesNotificationsAfterAnObserverThrows()
    {
        var parent = new BinaryExpression();
        var first = new NumberExpression { Value = 2 };
        var middle = new NumberExpression { Value = 1 };
        var last = new NumberExpression { Value = 2 };
        parent.Arguments.AddRange([first, middle, last]);

        var failure = new InvalidOperationException("sort observer");
        using var throwing = parent.Arguments.AddListener(_ => throw failure);
        var projected = new List<ModelListChange<IReadOnlyExpression?>>();
        using var later = parent.AsReadOnly().Arguments.AddListener(projected.Add);
        bool triedNestedEdit = false;
        Assert.Same(failure, Assert.Throws<InvalidOperationException>(() =>
            parent.Arguments.Sort((left, right) =>
            {
                if (!triedNestedEdit)
                {
                    triedNestedEdit = true;
                    Assert.Throws<ArgumentException>(() => parent.Arguments.RemoveAt(0));
                }
                return ((NumberExpression)left!).Value.CompareTo(((NumberExpression)right!).Value);
            })));
        Assert.True(triedNestedEdit);
        Assert.Same(middle, parent.Arguments[0]);
        Assert.Same(first, parent.Arguments[1]);
        Assert.Same(last, parent.Arguments[2]);
        Assert.Equal([0, 1], Assert.Single(projected).Added.Select(slot => slot.Index));
    }

    [Fact]
    public void StockDocumentSortRecordsOnePortableSparseReplacement()
    {
        var checkpoint = new JournalDocument { Id = "document", Title = "a" };
        var left = new JournalItem { Id = "left", Label = "original label" };
        checkpoint.Items.AddRange([left,
            new JournalItem { Id = "middle", Label = "middle label" },
            new JournalItem { Id = "right", Label = "right label" }]);
        checkpoint.SelectedItem = left;
        var registry = JournalDocumentSnapshot.Registry;
        var working = JournalDocumentSnapshot.Reflection.DeepCopy(checkpoint);
        var binding = new JournalBinding("test.document", 1, model => model switch
        {
            JournalDocument document => document.Id!,
            JournalItem item => item.Id!,
            _ => throw new ArgumentException("Unknown stock model object.", nameof(model))
        });
        var ranks = new Dictionary<string, int> { ["right"] = 0, ["middle"] = 1, ["left"] = 2 };
        var journal = JournalRecorder<JournalDocument>.RecordFrame(working, registry, binding, "document-at-40", 40,
            source => source.Items.Sort((a, b) => ranks[a!.Id!].CompareTo(ranks[b!.Id!])));
        var edit = Assert.IsType<JournalListEdit>(Assert.Single(Assert.Single(journal.Transactions).Operations));
        Assert.Equal(1, journal.WireVersion);
        Assert.Equal(JournalListEditKind.Set, edit.Kind);
        Assert.Equal([0, 2], edit.Indices);
        Assert.Equal(["left", "right"], edit.Before.Select(value => value.Text));
        Assert.Equal(["right", "left"], edit.After.Select(value => value.Text));

        foreach (var decoded in new[]
        {
            JournalJson.Read(JournalJson.Write(journal)),
            JournalYaml.Read(JournalYaml.Write(journal)),
            JournalXml.Read(JournalXml.Write(journal)),
        })
        {
            var result = JournalReplayer.Replay(checkpoint, binding, "document-at-40", decoded, registry);
            Assert.Equal(["right", "middle", "left"], result.Items.Select(item => item!.Id));
            Assert.Same(result.Items[2], result.SelectedItem);
            Assert.Same(result, result.SelectedItem!.SelectedBy);
            Assert.Same(left, checkpoint.Items[0]);
        }
    }
}
