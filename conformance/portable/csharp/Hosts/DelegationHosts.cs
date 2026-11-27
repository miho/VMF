using Eu.Mihosoft.Vmf.Portable.Delegation;

namespace Vmf.Portable.Hosts
{
    /// <summary>The default behavior of Counter (portable contract module "behavior"); each object has its own.</summary>
    public sealed class CounterDefault : Counter.Behavior
    {
        private static int hooks;
        private int calls;
        private int seenStart = -1;
        private int sequence;

        /// <summary>Runs before the declared defaults: it sees start 0, and the default replaces the 99 it sets.</summary>
        public void OnCounterInstantiated(Counter caller)
        {
            seenStart = caller.Start;
            caller.Note = "hooked";
            caller.Start = 99;
            sequence = ++hooks;
        }

        public int Add(Counter caller, int amount)
        {
            calls++;
            return caller.Start + amount;
        }

        public string? Label(Counter caller, string? prefix) => prefix + ":" + caller.Note;

        public int Sum(Counter caller, int[] values) => values.Sum();

        public Counter? Larger(Counter caller, Counter? other) => other is not null && other.Start > caller.Start ? other : caller;

        public void Reset(Counter caller) => caller.Start = 0;

        public int Calls(Counter caller) => calls;

        public int StartSeenByHook(Counter caller) => seenStart;

        public int HooksSince(Counter caller) => hooks - sequence;
    }

    /// <summary>A replacement behavior for one Counter.</summary>
    public sealed class AlternativeCounter : Counter.Behavior
    {
        public void OnCounterInstantiated(Counter caller) =>
            throw new InvalidOperationException("A replacement behavior never receives the construction hook.");

        public int Add(Counter caller, int amount) => 100 * amount;

        public string? Label(Counter caller, string? prefix) => prefix + "@" + caller.Start;

        public int Sum(Counter caller, int[] values) => values.Length;

        public Counter? Larger(Counter caller, Counter? other) => caller;

        public void Reset(Counter caller) => caller.Start = -1;

        public int Calls(Counter caller) => -1;

        public int StartSeenByHook(Counter caller) => -1;

        public int HooksSince(Counter caller) => -1;
    }

    public sealed class ShapeDefault : Shape.DefaultBehavior
    {
        public string? Describe(Shape caller) => "shape " + caller.Name;
    }

    public sealed class TileDefault : Tile.DefaultBehavior
    {
        public string? Describe(Tile caller) => "tile " + caller.Name;
    }
}

namespace Eu.Mihosoft.Vmf.Portable.Delegation
{
    public sealed partial class Counter
    {
        private static partial Behavior CreateDefaultBehavior() => new global::Vmf.Portable.Hosts.CounterDefault();
    }

    public abstract partial class Shape
    {
        private static partial DefaultBehavior CreateDefaultBehavior() => new global::Vmf.Portable.Hosts.ShapeDefault();
    }

    public sealed partial class Tile
    {
        private static partial DefaultBehavior CreateDefaultBehavior() => new global::Vmf.Portable.Hosts.TileDefault();
    }
}
