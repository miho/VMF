namespace Vmf.Runtime;

/// <summary>Reads and writes VMF model snapshots as Jackson-compatible YAML documents.</summary>
public static class SnapshotYaml
{
    public static string Write(object root, SnapshotRegistry registry) =>
        YamlWire.Encode(() => SnapshotJson.Write(root, registry));

    public static string Write(object root, Type rootType, SnapshotRegistry registry) =>
        YamlWire.Encode(() => SnapshotJson.Write(root, rootType, registry));

    public static T Read<T>(string yaml, SnapshotRegistry registry) where T : class =>
        YamlWire.Decode(yaml, json => SnapshotJson.Read<T>(json, registry));

    public static string WriteWithoutReferences(object root, SnapshotRegistry registry) =>
        YamlWire.Encode(() => SnapshotJson.WriteWithoutReferences(root, registry));

    public static string WriteWithoutReferences(object root, Type rootType, SnapshotRegistry registry) =>
        YamlWire.Encode(() => SnapshotJson.WriteWithoutReferences(root, rootType, registry));

    public static T ReadWithoutReferences<T>(string yaml, SnapshotRegistry registry) where T : class =>
        YamlWire.Decode(yaml, json => SnapshotJson.ReadWithoutReferences<T>(json, registry));

    public static string WritePlain<T>(T root, SnapshotRegistry registry) where T : class =>
        YamlWire.Encode(() => SnapshotJson.WritePlain(root, registry));

    public static string WritePlain(object root, Type rootType, SnapshotRegistry registry) =>
        YamlWire.Encode(() => SnapshotJson.WritePlain(root, rootType, registry));

    public static T ReadPlain<T>(string yaml, SnapshotRegistry registry) where T : class =>
        YamlWire.Decode(yaml, json => SnapshotJson.ReadPlain<T>(json, registry));
}
