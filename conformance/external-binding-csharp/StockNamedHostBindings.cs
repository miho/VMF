using System.Text.Json;
using System.Xml;
using Vmf.Runtime;

namespace Eu.Mihosoft.Vmf.Jackson.Test.External_types01
{
    public enum ExternalEnum { FIRST, SECOND, THIRD }

    public static partial class ModelSnapshot
    {
        private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() =>
            ExternalSnapshotCodecs.Empty
                .WithEnum<ExternalEnum>("eu.mihosoft.vmf.jackson.test.external_types01.ExternalEnum");
    }
}

namespace Eu.Mihosoft.Vmf.Jackson.Test.Type_formats01
{
    public enum PetType { DOG, CAT, BIRD, FISH, UNKNOWN }

    public sealed partial class Pet
    {
        private static partial PetType? CreateDefaultType() => PetType.UNKNOWN;
    }

    public static partial class ModelSnapshot
    {
        private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() =>
            ExternalSnapshotCodecs.Empty.WithEnum<PetType>("eu.mihosoft.vmf.jackson.test.type_formats01.PetType");
    }
}

namespace Eu.Mihosoft.Vmftest.Complex.Supplier
{
    public sealed class JavaDate(long epochMilliseconds) : IEquatable<JavaDate>
    {
        public long EpochMilliseconds { get; set; } = epochMilliseconds;

        public bool Equals(JavaDate? other) => other?.EpochMilliseconds == EpochMilliseconds;

        public override bool Equals(object? other) => other is JavaDate date && Equals(date);

        public override int GetHashCode() => EpochMilliseconds.GetHashCode();
    }

    public static partial class ModelSnapshot
    {
        private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() =>
            ExternalSnapshotCodecs.Empty.WithValue<JavaDate>("java.util.Date",
                static date => JsonSerializer.SerializeToElement(date.EpochMilliseconds),
                static value => new JavaDate(value.GetInt64()),
                JsonSerializer.SerializeToElement(new { type = "integer" }),
                new ExternalSnapshotXmlCodec(
                    static (writer, date) => writer.WriteString(XmlConvert.ToString(date.GetInt64())),
                    static element => JsonSerializer.SerializeToElement(XmlConvert.ToInt64(element.Value))));
    }
}
