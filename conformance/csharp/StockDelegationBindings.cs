namespace Eu.Mihosoft.Vmftest.Delegationinherit;

public partial class Device
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new DeviceBinding();

    private sealed class DeviceBinding : DefaultBehavior
    {
        private int calls;

        internal DeviceBinding() => StockTrace.DeviceBindings++;

        public void Consume(Device caller) => StockTrace.Record("device:consume", ++calls, caller);
        public void Process(Device caller) => StockTrace.Record("device:process", ++calls, caller);
        public void Produce(Device caller) => StockTrace.Record("device:produce", ++calls, caller);
    }
}

public sealed partial class CircuitDevice
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new CircuitBinding();

    private sealed class CircuitBinding : DefaultBehavior
    {
        private int calls;

        internal CircuitBinding() => StockTrace.CircuitBindings++;

        public void OnCircuitDeviceInstantiated(CircuitDevice caller) => StockTrace.Record("circuit:hook", ++calls, caller);
        public void Consume(CircuitDevice caller) => StockTrace.Record("circuit:consume", ++calls, caller);
        public void Process(CircuitDevice caller) => StockTrace.Record("circuit:process", ++calls, caller);
        public void Produce(CircuitDevice caller) => StockTrace.Record("circuit:produce", ++calls, caller);
    }
}

internal static class StockTrace
{
    internal static int DeviceBindings;
    internal static int CircuitBindings;
    internal static List<(string Operation, object Caller)> Trace { get; } = [];
    internal static void Record(string operation, int calls, object caller) => Trace.Add(($"{operation}:{calls}", caller));
}
