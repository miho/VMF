using Vmf.Runtime;

namespace Eu.Mihosoft.Vmf.Conformance.Immutableancestry;

public enum PortMode
{
    NORMAL,
    DIAGNOSTIC,
}

public sealed partial class PortSettings
{
    private static partial string? CreateDefaultLabel() => "115200";

    private static partial PortMode? CreateDefaultMode() => PortMode.NORMAL;
}

public static partial class ModelSnapshot
{
    private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() => ExternalSnapshotCodecs.Empty
        .WithEnum<PortMode>("eu.mihosoft.vmf.conformance.immutableancestry.host.PortMode");
}
