// The model cases of the Java lane (../java/perf/ModelScenarios.java), written against the C# code generated
// from the same model interface: the same graphs, operation counts and checks of the final state.
using Eu.Mihosoft.Vmftest.Performance;
using Vmf.Runtime;

namespace Vmf.Performance;

static class ModelScenarios
{
    const int Scalars = 64;
    const int Siblings = 32;
    const int Depth = 6;

    public static List<Scenario> Cases() =>
    [
        new("typed-scalar-get-set", 65_536, () => ScalarAccess(false)),
        new("reflective-scalar-get-set", 16_384, () => ScalarAccess(true)),
        new("scalar-set-listener-elsewhere", 65_536, ScalarAccessBesideListener),
        new("containment-reparent", 511, () => Reparent(false)),
        new("containment-reparent-observed", 255, () => Reparent(true)),
        new("containment-swap", 1_023, Swap),
        new("containment-append-1024", 1_024, AppendChildren),
        new("containment-sort", 255, Sort),
        new("opposite-reference-replace", 8_191, ReplaceReference),
        new("root-scalar-event", 8_192, () => ScalarEvents(false)),
        new("deep-scalar-event", 8_192, () => ScalarEvents(true)),
        new("deep-list-event", 4_095, ListEvents),
        new("recursive-listener-lifecycle", 64, ListenerLifecycle),
        new("native-record-publish", 127, Record),
        new("native-undo-redo", 512, UndoRedo),
        new("clone-linked-graph", 64, CloneGraph),
    ];

    private static Batch ScalarAccess(bool reflect)
    {
        TreeNode[] nodes = Enumerable.Range(0, Scalars).Select(i => Node("scalar-" + i, i)).ToArray();
        return new(iterations =>
        {
            if (reflect)
            {
                for (int i = 0; i < iterations; i++)
                {
                    var property = ModelSnapshot.Reflection.Reflect(nodes[i & (Scalars - 1)]).FindProperty("value")!;
                    property.Set((int)property.Get()! + 1);
                }
            }
            else
            {
                for (int i = 0; i < iterations; i++)
                {
                    TreeNode node = nodes[i & (Scalars - 1)];
                    node.Value = node.Value + 1;
                }
            }
        }, iterations =>
        {
            for (int i = 0; i < nodes.Length; i++)
            {
                int writes = (iterations + Scalars - 1 - i) / Scalars;
                Require(nodes[i].Value == i + writes, "Scalar state differs");
            }
        });
    }

    // The objects of the setter case, while a listener observes another tree: a listener anywhere must not make
    // every setter pay. (Not in the Java lane.)
    private static Batch ScalarAccessBesideListener()
    {
        TreeNode other = Node("observed", 0);
        Chain(other);
        int events = 0;
        IDisposable subscription = other.VmfChanges.AddListener(_ => events++, ModelChangeScope.Recursive);
        Batch batch = ScalarAccess(false);
        return new(batch.Run, iterations =>
        {
            batch.Verify(iterations);
            other.Value = 1;
            Require(events == 1, "The listener of the other tree must work, and only for its tree");
            subscription.Dispose();
        });
    }

    // Appends to a growing list: adding a child must not get slower with the size of the list.
    // (Not in the Java lane.)
    private static Batch AppendChildren()
    {
        TreeNode parent = Node("parent", 0);
        TreeNode[] children = Enumerable.Range(0, 1_024).Select(i => Node("child-" + i, i)).ToArray();
        return new(iterations =>
        {
            for (int i = 0; i < iterations; i++) parent.Children.Add(children[i]);
        }, iterations =>
        {
            Require(parent.Children.Count == iterations, "Unexpected child count");
            for (int i = 0; i < iterations; i++)
            {
                Require(ReferenceEquals(parent.Children[i], children[i]) && ReferenceEquals(children[i].Parent, parent),
                    "Appended children differ");
            }
        });
    }

    private static Batch Reparent(bool observed)
    {
        TreeNode root = Node("root", 0);
        TreeNode left = Node("left", 0);
        TreeNode right = Node("right", 0);
        root.Children.Add(left);
        root.Children.Add(right);
        var leftChildren = Children(left);
        var rightChildren = Children(right);
        var moved = Node("moved", 0);
        var leaf = Chain(moved);
        int middle = Siblings / 2;
        left.Children.Insert(middle, moved);
        int listEvents = 0;
        int leafEvents = 0;
        IDisposable? subscription = observed
            ? root.VmfChanges.AddListener(change =>
            {
                if (change.Kind == ModelChangeKind.List
                        && (ReferenceEquals(change.Source, left) || ReferenceEquals(change.Source, right))) listEvents++;
                if (ReferenceEquals(change.Source, leaf)) leafEvents++;
            }, ModelChangeScope.Recursive)
            : null;

        return new(iterations =>
        {
            for (int i = 0; i < iterations; i++)
            {
                TreeNode destination = (i & 1) == 0 ? right : left;
                destination.Children.Insert(middle, moved);
            }
        }, iterations =>
        {
            bool back = (iterations & 1) == 0;
            (back ? leftChildren : rightChildren).Insert(middle, moved);
            VerifyChildren(left, leftChildren);
            VerifyChildren(right, rightChildren);
            var descendant = moved;
            for (int depth = 0; depth < Depth; depth++)
            {
                Require(descendant.Children.Count == 1, "Moved subtree lost a descendant");
                var child = descendant.Children[0]!;
                Require(ReferenceEquals(child.Parent, descendant), "Moved descendant lost its parent");
                descendant = child;
            }
            Require(ReferenceEquals(descendant, leaf), "Moved subtree lost its original leaf");
            if (subscription is not null)
            {
                Require(listEvents == 2 * iterations,
                    $"Expected two containment notifications per operation, got {listEvents} for {iterations}");
                leaf.Value = 1;
                Require(leafEvents == 1, "Moved descendant must remain observed exactly once");
                subscription.Dispose();
                leaf.Value = 2;
                Require(leafEvents == 1, "Unsubscribed listener still observed changes");
            }
        });
    }

    private static Batch Swap()
    {
        TreeNode parent = Node("parent", 0);
        var initial = Children(parent);
        return new(iterations =>
        {
            for (int i = 0; i < iterations; i++) SwapChildren(parent, 4, 27);
        }, iterations =>
        {
            if ((iterations & 1) != 0) (initial[4], initial[27]) = (initial[27], initial[4]);
            VerifyChildren(parent, initial);
        });
    }

    // Java's Collections.swap on a containment list; the C# list offers Move.
    private static void SwapChildren(TreeNode parent, int low, int high)
    {
        parent.Children.Move(low, high);
        parent.Children.Move(high - 1, low);
    }

    private static Batch Sort()
    {
        TreeNode parent = Node("parent", 0);
        var initial = Children(parent);
        Comparison<TreeNode?> ascending = (a, b) => a!.Value.CompareTo(b!.Value);
        Comparison<TreeNode?> descending = (a, b) => b!.Value.CompareTo(a!.Value);
        return new(iterations =>
        {
            for (int i = 0; i < iterations; i++) parent.Children.Sort((i & 1) == 0 ? descending : ascending);
        }, iterations =>
        {
            if ((iterations & 1) != 0) initial.Reverse();
            VerifyChildren(parent, initial);
        });
    }

    private static Batch ReplaceReference()
    {
        var source = Node("source", 0);
        var first = Node("first", 0);
        var second = Node("second", 0);
        source.Link = first;
        return new(iterations =>
        {
            for (int i = 0; i < iterations; i++) source.Link = (i & 1) == 0 ? second : first;
        }, iterations =>
        {
            bool back = (iterations & 1) == 0;
            var linked = back ? first : second;
            var released = back ? second : first;
            Require(ReferenceEquals(source.Link, linked) && ReferenceEquals(linked.LinkedFrom, source)
                && released.LinkedFrom == null, "Opposite reference differs");
        });
    }

    private static Batch ScalarEvents(bool deep)
    {
        var root = Node("root", 0);
        var leaf = Chain(root);
        var target = deep ? leaf : root;
        var received = new Count();
        var subscription = root.VmfChanges.AddListener(
            change => received.Observe(change, target, ModelChangeKind.Property),
            deep ? ModelChangeScope.Recursive : ModelChangeScope.Local);
        return new(iterations =>
        {
            for (int i = 1; i <= iterations; i++) target.Value = i;
        }, iterations =>
        {
            Require(target.Value == iterations, "Incorrect scalar state");
            received.Verify(iterations);
            subscription.Dispose();
        });
    }

    private static Batch ListEvents()
    {
        var root = Node("root", 0);
        var leaf = Chain(root);
        leaf.Tags.Add("first");
        var received = new Count();
        var subscription = root.VmfChanges.AddListener(
            change => received.Observe(change, leaf, ModelChangeKind.List), ModelChangeScope.Recursive);
        return new(iterations =>
        {
            for (int i = 0; i < iterations; i++) leaf.Tags[0] = (i & 1) == 0 ? "second" : "first";
        }, iterations =>
        {
            Require(leaf.Tags.Count == 1 && leaf.Tags[0] == ((iterations & 1) == 0 ? "first" : "second"),
                "Incorrect list state");
            received.Verify(iterations);
            subscription.Dispose();
        });
    }

    private static Batch ListenerLifecycle()
    {
        var root = Node("root", 0);
        var leaves = new List<TreeNode>();
        Tree(root, 3, leaves);
        var leaf = leaves[0];
        var received = new Count();
        return new(iterations =>
        {
            for (int i = 1; i <= iterations; i++)
            {
                var listener = root.VmfChanges.AddListener(
                    change => received.Observe(change, leaf, ModelChangeKind.Property), ModelChangeScope.Recursive);
                leaf.Value = i;
                listener.Dispose();
            }
        }, iterations =>
        {
            leaf.Value = iterations + 1;
            received.Verify(iterations);
        });
    }

    private static Batch Record()
    {
        var root = Node("root", 0);
        var leaf = Chain(root);
        leaf.Tags.Add("first");
        var changes = root.VmfChanges;
        return new(iterations =>
        {
            for (int i = 1; i <= iterations; i++)
            {
                changes.Start();
                changes.StartTransaction();
                leaf.Value = i;
                leaf.Tags[0] = (i & 1) == 0 ? "first" : "second";
                changes.PublishTransaction();
                changes.Stop();
            }
        }, iterations =>
        {
            Require(changes.Transactions.Count == 1 && changes.History.Count == 2,
                $"Expected one transaction with two edits, got {changes.Transactions.Count} and {changes.History.Count}");
            var transaction = changes.Transactions[0];
            Require(transaction.Changes.Count == 2 && leaf.Value == iterations
                && leaf.Tags[0] == ((iterations & 1) == 0 ? "first" : "second"),
                "Native transaction did not retain the scalar and list edits");
        });
    }

    private static Batch UndoRedo()
    {
        var root = Node("root", 0);
        var initial = Children(root);
        var changes = root.VmfChanges;
        changes.Start();
        changes.StartTransaction();
        root.Value = 3;
        SwapChildren(root, 4, 27);
        changes.PublishTransaction();
        changes.Stop();
        var transaction = changes.Transactions[0];
        Require(transaction.Changes.Count == 3, $"Undo/redo requires a scalar and two list edits, got {transaction.Changes.Count}");
        int undoResults = 0;
        return new(iterations =>
        {
            for (int i = 0; i < iterations; i++)
            {
                transaction.Undo();
                if (root.Value == 0 && ReferenceEquals(root.Children[4], initial[4])) undoResults++;
                foreach (ModelChange change in transaction.Changes) change.Apply();
            }
        }, iterations =>
        {
            Require(undoResults == iterations && root.Value == 3, $"Undo or redo has the wrong state: {undoResults} of {iterations}");
            (initial[4], initial[27]) = (initial[27], initial[4]);
            VerifyChildren(root, initial);
        });
    }

    private static Batch CloneGraph()
    {
        var root = Node("root", 0);
        var leaves = new List<TreeNode>();
        Tree(root, 3, leaves);
        for (int i = 0; i < leaves.Count; i++) leaves[i].Link = leaves[(i + 17) % leaves.Count];
        TreeNode? last = null;
        return new(iterations =>
        {
            for (int i = 0; i < iterations; i++) last = ModelSnapshot.Reflection.DeepCopy(root);
        }, iterations =>
        {
            var mapped = new Dictionary<TreeNode, TreeNode>(ReferenceEqualityComparer.Instance);
            CollectClones(root, last!, null, mapped);
            Require(mapped.Count == 85, "Expected an 85-node clone");
            foreach (var (source, clone) in mapped)
            {
                Require(ReferenceEquals(clone.Link, source.Link is null ? null : mapped[source.Link])
                    && ReferenceEquals(clone.LinkedFrom, source.LinkedFrom is null ? null : mapped[source.LinkedFrom]),
                    "Clone has a missing or shared reference");
            }
        });
    }

    private static void CollectClones(TreeNode source, TreeNode clone, TreeNode? parent, Dictionary<TreeNode, TreeNode> mapped)
    {
        Require(!ReferenceEquals(clone, source) && source.Id == clone.Id && ReferenceEquals(clone.Parent, parent)
            && source.Children.Count == clone.Children.Count && source.Value == clone.Value, "Clone has a mismatched node");
        mapped[source] = clone;
        for (int i = 0; i < source.Children.Count; i++) CollectClones(source.Children[i]!, clone.Children[i]!, clone, mapped);
    }

    private static TreeNode Chain(TreeNode root)
    {
        var parent = root;
        for (int i = 0; i < Depth; i++)
        {
            var child = Node(parent.Id + "-child", 0);
            parent.Children.Add(child);
            parent = child;
        }
        return parent;
    }

    private static void Tree(TreeNode root, int remainingDepth, List<TreeNode> leaves)
    {
        if (remainingDepth == 0)
        {
            leaves.Add(root);
            return;
        }
        for (int i = 0; i < 4; i++)
        {
            var child = Node(root.Id + "-" + i, i);
            root.Children.Add(child);
            Tree(child, remainingDepth - 1, leaves);
        }
    }

    private static List<TreeNode> Children(TreeNode parent)
    {
        var result = new List<TreeNode>(Siblings);
        for (int i = 0; i < Siblings; i++)
        {
            var child = Node(parent.Id + "-" + i, i);
            result.Add(child);
            parent.Children.Add(child);
        }
        return result;
    }

    private static TreeNode Node(string id, int value) => TreeNode.NewBuilder().WithId(id).WithValue(value).Build();

    private static void VerifyChildren(TreeNode parent, List<TreeNode> expected)
    {
        var actual = parent.Children;
        Require(actual.Count == expected.Count, "Unexpected child count");
        for (int i = 0; i < expected.Count; i++)
        {
            Require(ReferenceEquals(actual[i], expected[i]) && ReferenceEquals(actual[i]!.Parent, parent),
                "Containment identity, order, or parent differs");
        }
    }

    private sealed class Count
    {
        private int value;
        private int unexpected;

        public void Observe(ModelChange change, TreeNode target, ModelChangeKind kind)
        {
            if (!ReferenceEquals(change.Source, target) || change.Kind != kind) unexpected++;
            value++;
        }

        public void Verify(int expected) =>
            Require(value == expected && unexpected == 0, $"Wrong event count, source, or type: {value} of {expected}, {unexpected} unexpected");
    }

    private static void Require(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }
}
