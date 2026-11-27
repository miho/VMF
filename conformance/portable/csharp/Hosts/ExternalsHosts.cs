using System.Text.Json;
using Vmf.Runtime;

namespace Vmf.Portable.Hosts
{
    /// <summary>The C# host enum of the external type Level (portable contract module "externals").</summary>
    public enum Level
    {
        LOW,
        MEDIUM,
        HIGH,
    }

    /// <summary>The C# host value of the external type Distance; documents write it as {"meters": number}.</summary>
    public sealed class Distance(double meters) : IEquatable<Distance>
    {
        public double Meters { get; } = meters;

        public bool Equals(Distance? other) => other is not null && Meters.Equals(other.Meters);

        public override bool Equals(object? other) => Equals(other as Distance);

        public override int GetHashCode() => Meters.GetHashCode();

        public override string ToString() => Meters + " m";
    }
}

namespace Eu.Mihosoft.Vmf.Portable.Externals
{
    public sealed partial class Sensor
    {
        private static partial global::Vmf.Portable.Hosts.Level? CreateDefaultThreshold() =>
            global::Vmf.Portable.Hosts.Level.MEDIUM;
    }

    public static partial class ModelSnapshot
    {
        private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() => ExternalSnapshotCodecs.Empty
            .WithEnum<global::Vmf.Portable.Hosts.Level>("eu.mihosoft.vmf.portable.externals.Level")
            .WithValue<global::Vmf.Portable.Hosts.Distance>("eu.mihosoft.vmf.portable.externals.Distance",
                static distance => JsonSerializer.SerializeToElement(new { meters = distance.Meters }),
                static json => new global::Vmf.Portable.Hosts.Distance(json.GetProperty("meters").GetDouble()),
                JsonSerializer.SerializeToElement(new { type = "object" }));
    }
}
