using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Immutablebehavior;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ImmutableBehaviorTests
{
    [Fact]
    public void DelegateClassesWithTheSameSimpleNameKeepIndependentState()
    {
        var value = new IndependentValue("same name");
        var calls = (IIndependentValueCalls)value;
        Assert.Equal(101, calls.First());
        Assert.Equal(201, calls.Second());
        Assert.Equal(102, calls.FirstAgain());
        Assert.Equal(202, calls.Second());

        string document = ModelSnapshot.WritePlain(value);
        var restored = ModelSnapshot.ReadPlain<IndependentValue>(document);
        Assert.Equal("same name", restored.Text);
        Assert.Equal(101, ((IIndependentValueCalls)restored).First());
        Assert.Equal(201, ((IIndependentValueCalls)restored).Second());
    }

    [Fact]
    public void ImmutableCallsStayOffModelAndReadOnlySurfaces()
    {
        int beforeHook = HookValue.BindingCount;
        int beforeTrait = HookTraitValue.BindingCount;
        var method = new MethodValue("alpha");
        var hook = HookValue.NewBuilder().WithText("four").Build();
        var inherited = new HookTraitValue(4, "hook");
        var only = HookOnlyValue.NewBuilder().WithCount(8).Build();
        Assert.Equal(8, only.Count);
        Assert.DoesNotContain(typeof(HookOnlyValue).GetInterfaces(), type => type.Name.EndsWith("Calls", StringComparison.Ordinal));
        Assert.Null(typeof(MethodValue).GetMethod("Render", [typeof(string)]));
        Assert.Null(typeof(HookValue).GetMethod("Score"));
        Assert.Null(typeof(HookValue).GetMethod("ReplaceBehavior"));
        Assert.Null(typeof(HookValue).GetProperty("BehaviorOverride"));
        Assert.Null(typeof(IReadOnlyHookTrait).GetMethod("Measure"));
        Assert.Null(typeof(IReadOnlyHookBranch).GetMethod("Measure"));
        Assert.Null(typeof(HookTraitValue).GetMethod("Measure"));

        Assert.Equal(beforeHook, HookValue.BindingCount);
        Assert.Equal(beforeTrait, HookTraitValue.BindingCount);
        Assert.Equal(7, ((IHookValueCalls)hook).Score(3));
        Assert.Equal(8, ((IHookTraitValueCalls)inherited).Measure(2));
        Assert.Equal(beforeHook + 1, HookValue.BindingCount);
        Assert.Equal(beforeTrait + 1, HookTraitValue.BindingCount);
        Assert.Same(method, method.AsReadOnly());
        Assert.Same(inherited, inherited.AsReadOnly());
    }

    [Fact]
    public void EachOwnerLazilyReusesOneTypedBindingAcrossOverloadsAndAliases()
    {
        int before = MethodValue.BindingCount;
        var first = new MethodValue("alpha");
        var second = new MethodValue("beta");
        var calls = (IMethodValueCalls)first;
        Assert.Equal(before, MethodValue.BindingCount);
        var results = new string?[32];
        Parallel.For(0, results.Length, index => results[index] = calls.Render("!"));
        Assert.All(results, text => Assert.Equal("!alpha", text));
        Assert.Equal("alp", calls.Render(3));
        Assert.Equal(10, calls.Sum(2, 3, 5));
        Assert.Equal(0, calls.Sum());
        Assert.Equal(before + 1, MethodValue.BindingCount);
        Assert.Equal(results.Length + 1, ((IMethodValueCalls)first.AsReadOnly()).Calls());
        Assert.Equal(0, ((IMethodValueCalls)second).Calls());
        Assert.Equal(before + 2, MethodValue.BindingCount);

        string wire = ModelSnapshot.WritePlain(first);
        using (var snapshot = JsonDocument.Parse(wire))
        {
            var property = Assert.Single(snapshot.RootElement.EnumerateObject());
            Assert.Equal("text", property.Name);
            Assert.Equal("alpha", property.Value.GetString());
        }
        var restored = ModelSnapshot.ReadPlain<MethodValue>(wire);
        Assert.Equal(before + 2, MethodValue.BindingCount);
        Assert.Equal(0, ((IMethodValueCalls)restored).Calls());
        Assert.Equal(before + 3, MethodValue.BindingCount);
    }

    [Fact]
    public void InheritedAndLocalBindingsInitializeIndependently()
    {
        int traits = TraitValue.TraitBindings;
        int local = TraitValue.LocalBindings;
        var value = new TraitValue(3, "trait");
        var calls = (ITraitValueCalls)value;
        Assert.Null(typeof(TraitValue).GetMethod("Metric"));
        Assert.Null(typeof(IReadOnlyMetricTrait).GetMethod("Metric"));
        Assert.Equal(traits, TraitValue.TraitBindings);
        Assert.Equal(local, TraitValue.LocalBindings);
        Assert.Equal(5, calls.Metric(2));
        Assert.Equal(traits + 1, TraitValue.TraitBindings);
        Assert.Equal(local, TraitValue.LocalBindings);
        Assert.Equal("trait!", calls.Describe("!"));
        Assert.Equal(6, calls.Metric(3));
        Assert.Equal(traits + 1, TraitValue.TraitBindings);
        Assert.Equal(local + 1, TraitValue.LocalBindings);

        var mutable = MutableTraitValue.NewBuilder().WithBase(9).Build();
        Assert.Equal(11, mutable.Metric(2));
        Assert.Equal(traits + 1, TraitValue.TraitBindings);
    }
}
