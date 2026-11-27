using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class JournalNativeCheckpointFactoryTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void NativeCheckpointCopyRejectsFactoryReturningAnotherLiveNode(bool record)
    {
        var child = new ArchiveNode { Name = "child" };
        var checkpoint = new ArchiveNode { Name = "root", Inset = child };
        var registry = new SnapshotRegistry([
            new SnapshotType("ArchiveNode", typeof(ArchiveNode), () => child, [
                new SnapshotProperty("name", "name", SnapshotPropertyKind.Scalar, typeof(string),
                    static owner => ((ArchiveNode)owner).Name, "example.name",
                    set: static (owner, value) => ((ArchiveNode)owner).Name = (string?)value),
                new SnapshotProperty("inset", SnapshotPropertyKind.Containment, typeof(ArchiveNode),
                    static owner => ((ArchiveNode)owner).Inset,
                    set: static (owner, value) => ((ArchiveNode)owner).Inset = (ArchiveNode?)value),
            ]),
        ]);
        _ = new ModelReflectionRegistry(registry, [
            new ModelReflectionType(typeof(ArchiveNode), [
                new ModelReflectionField("name", static owner => ((ArchiveNode)owner).Name),
                new ModelReflectionField("inset", static owner => ((ArchiveNode)owner).Inset),
            ]),
        ]);
        var binding = new JournalBinding("conformance.native-checkpoint-factory", 1, value => ((ArchiveNode)value).Name!);
        var empty = new ChangeJournal(binding.Model, binding.Version, "root", "checkpoint", 0, []);
        var observed = new List<ModelChange>();
        using var subscription = checkpoint.VmfChanges.AddRecursiveListener(observed.Add);
        var failure = Assert.Throws<InvalidOperationException>(() =>
        {
            if (record)
            {
                using var unexpected = JournalRecorder<ArchiveNode>.Start(checkpoint, registry, binding, "checkpoint", 0);
            }
            else JournalReplayer.Replay(checkpoint, binding, "checkpoint", empty, registry);
        });

        Assert.Contains("reused a live model object", failure.Message);
        Assert.Equal("child", child.Name);
        Assert.Equal("root", checkpoint.Name);
        Assert.Same(child, checkpoint.Inset);
        Assert.Same(checkpoint, child.InsetOf);
        Assert.Empty(observed);
        Assert.False(checkpoint.VmfChanges.IsRecording);
    }
}
