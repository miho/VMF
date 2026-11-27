using System.Text;
using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host;
using Vmf.Runtime;

namespace Vmf.CSharp.Conformance
{
    internal static class StockExternalSnapshotBindings
    {
        internal static ExternalSnapshotCodecs TextBuffers() => ExternalSnapshotCodecs.Empty.WithValue<StringBuilder>(
            "java.lang.StringBuilder",
            static value => JsonSerializer.SerializeToElement(value.ToString()),
            static value => new StringBuilder(value.GetString()!),
            JsonSerializer.SerializeToElement(new { type = "string" }),
            ExternalSnapshotXmlCodec.Text);
    }
}

namespace Eu.Mihosoft.Vmftest.Readonlyexternallist
{
    public static partial class ModelSnapshot
    {
        private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() =>
            global::Vmf.CSharp.Conformance.StockExternalSnapshotBindings.TextBuffers();
    }
}

namespace Eu.Mihosoft.Vmftest.Genericseries
{
    public static partial class ModelSnapshot
    {
        private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() =>
            ExternalSnapshotCodecs.Empty.WithEnum<ThreadPhase>("java.lang.Thread.State");
    }
}

namespace Eu.Mihosoft.Vmftest.Journalvalues
{
    public readonly record struct DocumentRevision(int Number);

    public static partial class ModelSnapshot
    {
        private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() =>
            global::Vmf.CSharp.Conformance.StockExternalSnapshotBindings.TextBuffers()
                // The recorder copies its shadow via this test-local snapshot codec.
                // Java and C# exchange DocumentRevision through its registered journal text codec.
                .WithImmutable<DocumentRevision>("eu.mihosoft.vmftest.journalvalues.DocumentRevision",
                    static revision => JsonSerializer.SerializeToElement(revision.Number),
                    static number => new DocumentRevision(number.GetInt32()),
                    JsonSerializer.SerializeToElement(new { type = "integer" }));
    }
}
