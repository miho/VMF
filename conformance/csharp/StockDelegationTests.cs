using Eu.Mihosoft.Vmftest.Delegationinherit;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class StockDelegationTests
{
    [Fact]
    public void StockCallableRolesShareConcreteBindingAndKeepItOutOfSnapshots()
    {
        StockTrace.Trace.Clear();
        StockTrace.DeviceBindings = 0;
        StockTrace.CircuitBindings = 0;
        var first = new Device();
        var second = new Device();
        Assert.Equal(0, StockTrace.DeviceBindings);
        Call(first);
        ((IConsumer)second).Consume();
        Assert.Equal(2, StockTrace.DeviceBindings);
        Assert.Equal(["device:produce:1", "device:consume:2", "device:process:3"], Events(first));
        Assert.Equal(["device:consume:1"], Events(second));

        Device circuit = new CircuitDevice();
        Assert.Equal(1, StockTrace.CircuitBindings);
        Assert.Equal(["circuit:hook:1"], Events(circuit));
        Call(circuit);
        Assert.Equal(["circuit:hook:1", "circuit:produce:2", "circuit:consume:3", "circuit:process:4"], Events(circuit));
        Assert.Equal(2, StockTrace.DeviceBindings);
        Assert.Null(typeof(IReadOnlyConsumer).GetMethod("Consume"));

        Assert.Throws<ArgumentException>(() => circuit.ReplaceBehavior(new BaseReplacement()));
        var replacement = new Replacement();
        circuit.ReplaceBehavior(replacement);
        Call(circuit);
        Assert.Same(replacement, circuit.BehaviorOverride);
        Assert.Equal(["replacement:produce:1", "replacement:consume:2", "replacement:process:3"],
            Events(circuit).TakeLast(3));

        string wire = ModelSnapshot.WriteWithoutReferences(circuit);
        Assert.Equal("{}", wire);
        var restored = ModelSnapshot.ReadWithoutReferences<CircuitDevice>(wire);
        Assert.Null(restored.BehaviorOverride);
        Call(restored);
        Assert.Equal(["circuit:hook:1", "circuit:produce:2", "circuit:consume:3", "circuit:process:4"], Events(restored));
    }

    private static void Call(Processor model)
    {
        ((Producer)model).Produce();
        ((IConsumer)model).Consume();
        model.Process();
    }

    private static IEnumerable<string> Events(object model) => StockTrace.Trace
        .Where(change => ReferenceEquals(change.Caller, model)).Select(change => change.Operation);

    private sealed class Replacement : CircuitDevice.Behavior
    {
        private int calls;

        public void Produce(Producer caller) => StockTrace.Record("replacement:produce", ++calls, caller);
        public void Consume(Processor caller) => StockTrace.Record("replacement:consume", ++calls, caller);
        public void Process(Processor caller) => StockTrace.Record("replacement:process", ++calls, caller);
    }

    private sealed class BaseReplacement : Device.Behavior
    {
        public void Produce(Producer caller) { }
        public void Consume(Processor caller) { }
        public void Process(Processor caller) { }
    }
}
