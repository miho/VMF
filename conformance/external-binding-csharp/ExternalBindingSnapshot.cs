using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Xml;
using Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host;
using Vmf.Runtime;

namespace Eu.Mihosoft.Vmf.Conformance.Externalbinding;

public static partial class ModelSnapshot
{
    private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() => ExternalSnapshotCodecs.Empty
        .WithEnum<ThreadPhase>("java.lang.Thread.State")
        .WithEnum<DispatchMode>("eu.mihosoft.vmf.conformance.externalbinding.host.DispatchMode")
        .WithValue<StringBuilder>("java.lang.StringBuilder",
            static value => JsonSerializer.SerializeToElement(value.ToString()),
            static value => new StringBuilder(value.GetString()!),
            Parse("""{"type":"string"}"""), ExternalSnapshotXmlCodec.Text)
        .WithImmutable<LexicalUri>("java.net.URI",
            static value => JsonSerializer.SerializeToElement(value.Text),
            static value => new LexicalUri(value.GetString()!),
            Parse("""{"type":"string","format":"uri-reference"}"""), ExternalSnapshotXmlCodec.Text)
        .WithImmutable<Distance>("eu.mihosoft.vmf.conformance.externalbinding.host.Distance",
            static value => JsonSerializer.SerializeToElement(new { millimetres = value.Millimetres, note = value.Note, estimated = value.Estimated }),
            static value => new Distance(value.GetProperty("millimetres").GetInt64(), value.GetProperty("note").GetString()!,
                value.GetProperty("estimated").GetBoolean()),
            Parse("""
                {"type":"object","required":["millimetres","note","estimated"],"properties":{
                "millimetres":{"type":"integer"},"note":{"type":"string"},"estimated":{"type":"boolean"}}}
                """),
            new ExternalSnapshotXmlCodec(
                static (xml, value) =>
                {
                    xml.WriteElementString("millimetres", XmlConvert.ToString(value.GetProperty("millimetres").GetInt64()));
                    xml.WriteElementString("note", value.GetProperty("note").GetString());
                    xml.WriteElementString("estimated", XmlConvert.ToString(value.GetProperty("estimated").GetBoolean()));
                },
                static xml => JsonSerializer.SerializeToElement(new
                {
                    millimetres = XmlConvert.ToInt64(xml.Element("millimetres")?.Value ?? throw new XmlException("Missing distance magnitude.")),
                    note = xml.Element("note")?.Value ?? throw new XmlException("Missing distance note."),
                    estimated = XmlConvert.ToBoolean(xml.Element("estimated")?.Value ?? throw new XmlException("Missing estimation flag.")),
                })));

    private static JsonElement Parse(string json)
    {
        using var document = JsonDocument.Parse(json);
        return document.RootElement.Clone();
    }
}

public sealed partial class ModeProfile
{
    private static partial DispatchMode? CreateDefaultPreferred() => DispatchMode.READY;
}

public sealed partial class PresetEndpoint
{
    private static partial LexicalUri? CreateDefaultLocation() => new("https://example.invalid/default");

    private static partial Guid? CreateDefaultKey() => Guid.Parse("980e13c6-84a0-49d6-821d-737269635aef");
}

internal static class EndpointJournalBinding
{
    internal static JournalBinding Instance { get; } = new("external-binding", 1,
        static model => ((Endpoint)model).Id!, null, JournalValueCodecs.Standard
            .WithImmutable<LexicalUri>("probe.uri/v1", static uri => uri.Text, static text => new LexicalUri(text))
            .WithImmutable<Distance>("probe.distance/v1", EncodeDistance, DecodeDistance));

    private static string EncodeDistance(Distance value)
    {
        if (value.Note.Contains('|')) throw new FormatException("The distance note contains the journal delimiter.");
        return value.Millimetres.ToString(CultureInfo.InvariantCulture) + "|" + value.Note + "|"
            + (value.Estimated ? "true" : "false");
    }

    private static Distance DecodeDistance(string text)
    {
        var parts = text.Split('|');
        if (parts.Length != 3) throw new FormatException("Expected three distance journal components.");
        return new(long.Parse(parts[0], CultureInfo.InvariantCulture), parts[1], bool.Parse(parts[2]));
    }
}
