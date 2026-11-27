using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class JournalCheckpointFactoryTests
{
    [Fact]
    public void ReusedCheckpointChildIsRejectedBeforeSnapshotHydrationCanMoveIt()
    {
        var child = new ArchiveNode { Name = "child" };
        var checkpoint = new ArchiveRoot { Head = child };
        var registry = new SnapshotRegistry([
            new SnapshotType("ArchiveRoot", typeof(ArchiveRoot), static () => new ArchiveRoot(), [
                new SnapshotProperty("head", SnapshotPropertyKind.Containment, typeof(ArchiveNode),
                    static owner => ((ArchiveRoot)owner).Head,
                    set: static (owner, value) => ((ArchiveRoot)owner).Head = (ArchiveNode?)value),
            ]),
            new SnapshotType("ArchiveNode", typeof(ArchiveNode), () => child, [
                new SnapshotProperty("name", SnapshotPropertyKind.Scalar, typeof(string),
                    static owner => ((ArchiveNode)owner).Name,
                    set: static (owner, value) => ((ArchiveNode)owner).Name = (string?)value),
            ]),
        ]);
        var binding = new JournalBinding("conformance.checkpoint-factory", 1,
            value => value is ArchiveNode node ? node.Name! : "root");
        var journal = new ChangeJournal(binding.Model, binding.Version, "root", "checkpoint", 0, []);
        var observed = new List<ModelChange>();
        using var subscription = checkpoint.VmfChanges.AddRecursiveListener(observed.Add);

        var replayFailure = Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(checkpoint, binding, "checkpoint", journal, registry));
        Assert.Contains("reused a live model object", replayFailure.Message);
        Assert.Same(child, checkpoint.Head);
        Assert.Empty(observed);

        var recordingFailure = Assert.Throws<InvalidOperationException>(() =>
        {
            using var unexpected = JournalRecorder<ArchiveRoot>.Start(checkpoint, registry, binding, "checkpoint", 0);
        });
        Assert.Contains("reused a live model object", recordingFailure.Message);
        Assert.Same(child, checkpoint.Head);
        Assert.Empty(observed);
        Assert.False(checkpoint.VmfChanges.IsRecording);
    }
}
