namespace Vmf.Runtime;

/// <summary>Reads and writes portable VMF journals in the Jackson YAML representation.</summary>
public static class JournalYaml
{
    public static string Write(ChangeJournal journal) => YamlWire.Encode(() => JournalJson.Write(journal));

    public static ChangeJournal Read(string yaml) => YamlWire.Decode(yaml, JournalJson.Read);
}
