using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behavior;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class BehaviorTests
{
    [Fact]
    public void NativeSignaturesAndConstructorHookUseTheTypedModel()
    {
        int firstHook = MutableGauge.HookValues.Count;
        var defaulted = new MutableGauge();
        var built = MutableGauge.NewBuilder().WithBase(13).Build();
        Assert.Equal(new (int, string?)[] { (0, null), (0, null) }, MutableGauge.HookValues.Skip(firstHook));
        Assert.Equal(8, defaulted.Base);
        Assert.Equal("ready", defaulted.State);
        Assert.Equal(13, built.Base);
        Assert.Equal("ready", built.State);
        Assert.Null(defaulted.BehaviorOverride);
        Assert.Null(built.BehaviorOverride);
        Assert.Equal(15, built.Adjust(2));
        Assert.Equal(11, built.Adjust((int?)2));
        Assert.Null(built.Adjust((int?)null));
        Assert.Equal(10, built.Sum(2, 3, 5));
        Assert.Equal("ready", built.Diagnose(true));
        Assert.Equal("ok", built.Diagnose(false));
        var input = CallResult.NewBuilder().WithText("input").Build();
        Assert.Equal("input!", built.Compose(input, "!")?.Text);
        Assert.Null(built.Compose(null, "!"));
        Assert.DoesNotContain(typeof(IReadOnlyMutableGauge).GetMethods(), method => method.Name == nameof(built.Adjust));
    }

    [Fact]
    public void LiveJournalCopiesRunOneFreshHookAndReplaceItsSeededManagedStorage()
    {
        int baseline = HookedJournalCheckpoint.HookCount;
        var original = new HookedJournalCheckpoint();
        var field = ModelSnapshot.Reflection.Reflect(original).FindProperty("slots")!;
        Assert.Equal([1], original.Slots);
        field.SetDefault(new[] { 2 });
        field.Reset();
        Assert.Equal(1, original.Invocation());
        Assert.Equal(2, original.Invocation());
        var binding = new JournalBinding("hook-checkpoint", 1, _ => "root");
        var empty = new ChangeJournal("hook-checkpoint", 1, "root", "base", 0, []);
        var copy = JournalReplayer.Replay(original, binding, "base", empty, ModelSnapshot.Registry);
        Assert.Equal(baseline + 2, HookedJournalCheckpoint.HookCount);
        Assert.Equal([2], copy.AsReadOnly().Slots);
        Assert.Equal(1, copy.Invocation());
        Assert.Equal(3, original.Invocation());

        using var recorder = JournalRecorder<HookedJournalCheckpoint>.Start(original, ModelSnapshot.Registry, binding, "base", 0);
        Assert.Equal(baseline + 3, HookedJournalCheckpoint.HookCount);
        original.Slots.Add(3);
        var journal = recorder.Finish();
        Assert.Equal(1, journal.WireVersion);
        Assert.Single(journal.Transactions);
        var restored = JournalReplayer.Replay(copy, binding, "base", journal, ModelSnapshot.Registry);
        Assert.Equal(baseline + 4, HookedJournalCheckpoint.HookCount);
        Assert.Equal([2, 3], restored.Slots);
        Assert.Equal([2], copy.Slots);
        Assert.Equal(1, restored.Invocation());
        var restoredField = ModelSnapshot.Reflection.Reflect(restored).FindProperty("slots")!;
        Assert.True(restoredField.IsSet);
        restoredField.Reset();
        Assert.Equal([2], restored.Slots);
        Assert.Equal([2, 3], original.Slots);
    }

    [Fact]
    public void MethodOnlyBindingIsLazyAndKeepsStatePerInstance()
    {
        int before = SignalGauge.BindingCount;
        var first = SignalGauge.NewBuilder().WithLevel(10).Build();
        var second = new SignalGauge();
        Assert.Equal(before, SignalGauge.BindingCount);
        Assert.Equal(14, first.Signal(3));
        Assert.Equal(15, first.Signal(3));
        Assert.Equal(before + 1, SignalGauge.BindingCount);
        Assert.Equal(8, second.Signal(3));
        Assert.Equal(before + 2, SignalGauge.BindingCount);
    }

    [Fact]
    public void PerInstanceReplacementAffectsNativeEditsAndResetsOnCopyAndInterchange()
    {
        var gauge = MutableGauge.NewBuilder().WithBase(13).Build();
        var independent = new MutableGauge();
        var updates = new List<object?>();
        using var listener = gauge.VmfChanges.AddPropertyListener("state", change => updates.Add(change.NewValue));
        gauge.Submit("submitted");
        Assert.Equal("submitted", gauge.Diagnose(true));

        var replacement = new LiveBehavior();
        gauge.ReplaceBehavior(replacement);
        Assert.Same(replacement, gauge.BehaviorOverride);
        Assert.Equal(200, gauge.Adjust(2));
        Assert.Equal(99, gauge.Adjust((int?)null));
        Assert.Equal("runtime", gauge.Diagnose(true));
        Assert.Equal(10, independent.Adjust(2));
        Assert.Equal(3, gauge.Sum(8, 8, 8));
        var input = CallResult.NewBuilder().WithText("input").Build();
        Assert.Same(input, gauge.Compose(input, "unused"));
        gauge.Submit("x");
        Assert.Equal(new[] { "submitted", "runtime:x" }, updates);
        Assert.All(replacement.Callers, caller => Assert.Same(gauge, caller));
        Assert.Throws<ArgumentNullException>(() => gauge.ReplaceBehavior(null!));

        var copied = ModelSnapshot.Reflection.DeepCopy(gauge);
        Assert.NotSame(gauge, copied);
        VerifyRestored(copied);
        Assert.Equal(200, gauge.Adjust(2));
        Assert.Same(replacement, gauge.BehaviorOverride);

        string wire = ModelSnapshot.WritePlain(gauge);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse("""{"base":13,"state":"runtime:x"}"""), JsonNode.Parse(wire)));
        VerifyRestored(ModelSnapshot.ReadPlain<MutableGauge>(wire));
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR");
        if (exchange is not null)
        {
            string javaWire = File.ReadAllText(Path.Combine(exchange, "java-gauge.json"));
            VerifyRestored(ModelSnapshot.ReadPlain<MutableGauge>(javaWire));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaWire), JsonNode.Parse(wire)));
            File.WriteAllText(Path.Combine(exchange, "csharp-gauge.json"), wire);
        }
    }

    private static void VerifyRestored(MutableGauge gauge)
    {
        Assert.Equal(13, gauge.Base);
        Assert.Equal("runtime:x", gauge.State);
        Assert.Equal(15, gauge.Adjust(2));
        Assert.Equal("runtime:x", gauge.Diagnose(true));
        Assert.Null(gauge.BehaviorOverride);
    }

    private sealed class LiveBehavior : MutableGauge.Behavior
    {
        internal List<MutableGauge> Callers { get; } = [];

        public void OnMutableGaugeInstantiated(MutableGauge caller) => throw new InvalidOperationException("A replacement is not constructed as a model.");

        public int Adjust(MutableGauge caller, int amount)
        {
            Callers.Add(caller);
            return 100 * amount;
        }

        public int? Adjust(MutableGauge caller, int? amount) => amount.HasValue ? 100 * amount : 99;

        public CallResult? Compose(MutableGauge caller, CallResult? input, string? suffix) => input;

        public string? Diagnose(MutableGauge caller, bool verbose) => "runtime";

        public int Sum(MutableGauge caller, int[] values) => values.Length;

        public void Submit(MutableGauge caller, string? text) => caller.State = "runtime:" + text;
    }
}
