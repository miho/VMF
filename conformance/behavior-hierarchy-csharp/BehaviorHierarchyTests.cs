using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class BehaviorHierarchyTests
{
    [Fact]
    public void RuntimeTypeSelectsOneHookBeforeDefaultsAndRetainsLazyInheritedBindings()
    {
        int priorHooks = Engine.HookValues.Count;
        int priorFastHooks = FastEngine.FastHookValues.Count;
        int priorBindings = Engine.DefaultBindingCount;
        var ordinary = Engine.NewBuilder().WithPower(12).Build();
        var inherited = InheritedEngine.NewBuilder().WithPower(13).WithRate(15).Build();
        var fast = FastEngine.NewBuilder().WithPower(14).WithBonus(40).Build();

        Assert.Equal(new (Type, int, int?)[] { (typeof(Engine), 0, null), (typeof(InheritedEngine), 0, 0) },
            Engine.HookValues.Skip(priorHooks));
        Assert.Equal(new[] { (0, 0) }, FastEngine.FastHookValues.Skip(priorFastHooks));
        Assert.Equal(priorBindings + 2, Engine.DefaultBindingCount);
        Assert.Equal(15, inherited.Rate);
        Assert.Equal(40, fast.Bonus);
        Assert.Equal(14, ordinary.Transform(2));
        Assert.Equal(15, inherited.Transform(2));

        Engine baseView = fast;
        Assert.Equal(56, baseView.Transform(2));
        Assert.Equal(priorBindings + 2, Engine.DefaultBindingCount);
        Assert.Equal("base:14", baseView.Label("base"));
        Assert.Equal(priorBindings + 3, Engine.DefaultBindingCount);
        Assert.Equal(priorHooks + 2, Engine.HookValues.Count);
        Assert.Null(baseView.BehaviorOverride);
        Assert.Null(fast.BehaviorOverride);
        Assert.DoesNotContain(typeof(IReadOnlyEngine).GetMethods(), method => method.Name == nameof(ordinary.Transform));
    }

    [Fact]
    public void BaseTypedReplacementRequiresTheConcreteBehaviorContract()
    {
        var fast = FastEngine.NewBuilder().WithPower(14).WithBonus(40).Build();
        Engine baseView = fast;
        var ordinary = Engine.NewBuilder().WithPower(12).Build();
        Assert.Throws<ArgumentException>(() => baseView.ReplaceBehavior(new EngineReplacement()));
        Assert.Null(baseView.BehaviorOverride);
        Assert.Equal(56, baseView.Transform(2));

        var replacement = new FastReplacement();
        baseView.ReplaceBehavior(replacement);
        Assert.Same(replacement, baseView.BehaviorOverride);
        Assert.Same(replacement, fast.BehaviorOverride);
        Assert.Equal(102, baseView.Transform(2));
        Assert.Equal("x:override", baseView.Label("x"));
        Assert.Same(fast, replacement.LastCaller);
        Assert.Equal(14, ordinary.Transform(2));

        var inherited = InheritedEngine.NewBuilder().WithPower(13).Build();
        Engine inheritedBase = inherited;
        Assert.Throws<ArgumentException>(() => inheritedBase.ReplaceBehavior(replacement));
        var inheritedReplacement = new InheritedReplacement();
        inherited.ReplaceBehavior(inheritedReplacement);
        Assert.Same(inheritedReplacement, inheritedBase.BehaviorOverride);
        Assert.Same(inheritedReplacement, inherited.BehaviorOverride);
        Assert.Equal(102, inheritedBase.Transform(2));
    }

    [Fact]
    public void ClosedGenericModelExposesItsCallableWithoutEagerBinding()
    {
        int priorBindings = IntLookup.DefaultBindingCount;
        var lookup = IntLookup.NewBuilder().WithValue(5).Build();
        Lookup<int> typed = lookup;
        Assert.Equal(5, typed.Value);
        Assert.Equal(priorBindings, IntLookup.DefaultBindingCount);
        Assert.Equal(13, typed.Metric(8));
        Assert.Equal(14, typed.Metric(9));
        Assert.Equal(priorBindings + 1, IntLookup.DefaultBindingCount);
        Assert.DoesNotContain(typeof(IReadOnlyLookup<int>).GetMethods(), method => method.Name == nameof(typed.Metric));

        Assert.Throws<ArgumentException>(() => typed.ReplaceBehavior(new LookupReplacement()));
        var replacement = new IntLookupReplacement();
        typed.ReplaceBehavior(replacement);
        Assert.Same(replacement, lookup.BehaviorOverride);
        Assert.Same(replacement, typed.BehaviorOverride);
        Assert.Equal(58, typed.Metric(8));
        Assert.Same(lookup, replacement.LastCaller);
    }

    [Fact]
    public void CopyAndTypedSnapshotRetainSubtypeDataAndRestoreDefaultBindings()
    {
        var fast = FastEngine.NewBuilder().WithPower(14).WithBonus(40).Build();
        var lookup = IntLookup.NewBuilder().WithValue(5).Build();
        var fleet = EngineFleet.NewBuilder().WithEngines([
            Engine.NewBuilder().WithPower(12).Build(),
            InheritedEngine.NewBuilder().WithPower(13).WithRate(15).Build(),
            fast
        ]).WithLookup(lookup).Build();
        var replacement = new FastReplacement();
        fast.ReplaceBehavior(replacement);
        lookup.ReplaceBehavior(new IntLookupReplacement());
        Assert.Equal(102, fast.Transform(2));
        Assert.Equal(58, lookup.Metric(8));

        var copy = ModelSnapshot.Reflection.DeepCopy(fleet);
        VerifyDefaults(copy);
        Assert.NotSame(fleet, copy);
        for (int index = 0; index < fleet.Engines.Count; index++)
        {
            Assert.NotSame(fleet.Engines[index], copy.Engines[index]);
        }
        Assert.NotSame(fleet.Lookup, copy.Lookup);
        Assert.Same(replacement, fast.BehaviorOverride);
        Assert.Equal(102, fast.Transform(2));

        string wire = ModelSnapshot.WriteWithoutReferences(fleet);
        VerifyDefaults(ModelSnapshot.ReadWithoutReferences<EngineFleet>(wire));
        var document = JsonNode.Parse(wire)!;
        var engines = document["engines"]!.AsArray();
        Assert.Equal(new[] { typeof(Engine).Name, typeof(InheritedEngine).Name, typeof(FastEngine).Name },
            engines.Select(engine => engine!["@vmf-type"]!.GetValue<string>().Split('.').Last()));
        Assert.All(engines, engine =>
        {
            Assert.False(engine!.AsObject().ContainsKey("behavior"));
            Assert.False(engine.AsObject().ContainsKey("behaviorOverride"));
            Assert.False(engine.AsObject().ContainsKey("@vmf-id"));
        });

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        if (exchange is null) return;
        string java = File.ReadAllText(Path.Combine(exchange, "java-behavior-hierarchy.json"));
        VerifyDefaults(ModelSnapshot.ReadWithoutReferences<EngineFleet>(java));
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), document));
        File.WriteAllText(Path.Combine(exchange, "csharp-behavior-hierarchy.json"), wire);
    }

    private static void VerifyDefaults(EngineFleet fleet)
    {
        Assert.Equal(3, fleet.Engines.Count);
        var ordinary = Assert.IsType<Engine>(fleet.Engines[0]);
        var inherited = Assert.IsType<InheritedEngine>(fleet.Engines[1]);
        var fast = Assert.IsType<FastEngine>(fleet.Engines[2]);
        Assert.Equal(14, ordinary.Transform(2));
        Assert.Equal(15, inherited.Transform(2));
        Assert.Equal(15, inherited.Rate);
        Assert.Equal(56, fast.Transform(2));
        Assert.Equal("base:14", fast.Label("base"));
        Assert.Equal(40, fast.Bonus);
        Assert.All(fleet.Engines, engine => Assert.Null(engine.BehaviorOverride));
        var lookup = Assert.IsType<IntLookup>(fleet.Lookup);
        Assert.Equal(5, lookup.Value);
        Assert.Equal(13, lookup.Metric(8));
        Assert.Null(lookup.BehaviorOverride);
    }

    private class EngineReplacement : Engine.Behavior
    {
        internal Engine? LastCaller { get; private set; }

        public int Transform(Engine caller, int delta)
        {
            LastCaller = caller;
            return 100 + delta;
        }

        public string? Label(Engine caller, string? prefix)
        {
            LastCaller = caller;
            return prefix + ":override";
        }
    }

    private sealed class FastReplacement : EngineReplacement, FastEngine.Behavior;

    private sealed class InheritedReplacement : EngineReplacement, InheritedEngine.Behavior;

    private class LookupReplacement : Lookup<int>.Behavior
    {
        internal Lookup<int>? LastCaller { get; private set; }

        public int Metric(Lookup<int> caller, int delta)
        {
            LastCaller = caller;
            return 10 * (caller.Value ?? 0) + delta;
        }
    }

    private sealed class IntLookupReplacement : LookupReplacement, IntLookup.Behavior;
}
