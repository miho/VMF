package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.SyncWith;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableType;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

@DelegateTo(className = "example.GaugeBehavior")
interface CallableGauge {
    long scale(long units);
    Long scale(Long units);
    CallableResult combine(CallableResult input, String suffix);
    void update(String message);
    int sum(int... values);

    @DelegateTo(className = "example.DiagnosticBehavior") String diagnose(boolean verbose);
}

interface CallableResult { String getText(); }

@InterfaceOnly
interface CallableActionContract { int execute(int steps); }

@DelegateTo(className = "example.ActionBehavior")
interface CallableAction extends CallableActionContract { @Override int execute(int steps); }

interface CallableMissingBinding extends CallableActionContract { }

@InterfaceOnly
interface CallableProducerRole {
    void produce();
}

@InterfaceOnly
interface CallableConsumerRole {
    void consume();
}

@InterfaceOnly
interface CallableComposedRoles extends CallableProducerRole, CallableConsumerRole {
    void process();
}

interface CallableBoundRoles extends CallableComposedRoles {
    @Override @DelegateTo(className = "example.RoleBinding") void produce();
    @Override @DelegateTo(className = "example.RoleBinding") void consume();
    @Override @DelegateTo(className = "example.RoleBinding") void process();
}

interface CallableSeparateRoleBindings extends CallableComposedRoles {
    @Override @DelegateTo(className = "example.FirstBinding") void produce();
    @Override @DelegateTo(className = "example.SecondBinding") void consume();
    @Override @DelegateTo(className = "example.FirstBinding") void process();
}

@InterfaceOnly
interface CallableCompetingProducerRole {
    void produce();
}

@InterfaceOnly
interface CallableOverlappingRoles extends CallableProducerRole, CallableCompetingProducerRole { }

interface CallableChecked {
    @DelegateTo(className = "example.CheckedBehavior") void read() throws IOException;
}

interface CallableStatic {
    @DelegateTo(className = "example.StaticBehavior") static int count(int delta) { return delta; }
}

interface CallableExternal {
    @DelegateTo(className = "example.ExternalBehavior") java.util.Date receive(java.util.Date date);
}

interface CallableJagged {
    @DelegateTo(className = "example.JaggedBehavior") int mark(int[][] rows);
    @DelegateTo(className = "example.JaggedBehavior") CallableResult first(CallableResult[][] rows);
    @DelegateTo(className = "example.JaggedBehavior") int countRows(int[]... rows);
}

interface CallableJaggedResult {
    @DelegateTo(className = "example.JaggedBehavior") int[][] read();
}

interface CallableJaggedExternal {
    @DelegateTo(className = "example.JaggedBehavior") int mark(java.util.Date[][] rows);
}

interface CallableJaggedObject {
    @DelegateTo(className = "example.JaggedBehavior") int mark(Object[][] rows);
}

@InterfaceOnly
interface CallableSeries<T> {
    T getValue();
}

interface CallableCounts extends CallableSeries<Integer> { }

@Immutable
interface CallableApplied {
    @DelegateTo(className = "example.AppliedBehavior")
    CallableSeries<Integer> forward(CallableSeries<Integer> input);

    @DelegateTo(className = "example.AppliedBehavior")
    CallableSeries<Integer>[] batch(CallableSeries<Integer>[] inputs);
}

@Immutable
interface CallableAppliedChild extends CallableApplied { }

interface CallableRaw {
    @DelegateTo(className = "example.AppliedBehavior") void take(CallableSeries input);
}

interface CallableWildcard {
    @DelegateTo(className = "example.AppliedBehavior") void take(CallableSeries<?> input);
}

interface CallableNested {
    @DelegateTo(className = "example.AppliedBehavior") void take(CallableSeries<CallableSeries<Integer>> input);
}

@Immutable
interface CallableCollectionResult {
    @DelegateTo(className = "example.AppliedBehavior") java.util.List<CallableResult> find();
}

interface CallableScalarCollectionResult {
    @DelegateTo(className = "example.AppliedBehavior") java.util.List<Integer> find();
}

interface CallableObjectCollectionResult {
    @DelegateTo(className = "example.AppliedBehavior") java.util.List<Object> payloads();
    @DelegateTo(className = "example.AppliedBehavior") eu.mihosoft.vcollections.VList<Object> directPayloads();
}

interface CallableRawCollectionResult {
    @DelegateTo(className = "example.AppliedBehavior") java.util.List find();
}

interface CallableCollectionParameter {
    @DelegateTo(className = "example.AppliedBehavior") void accept(java.util.List<CallableResult> input);
}

@DelegateTo(className = "example.HookOnlyBehavior")
interface CallableHookOnly { String getCaption(); }

interface CallableSyncInbox {
    @SyncWith(opposite = "CallableSyncInbox.priority") CallableSyncFrame[] getAll();
    @SyncWith(opposite = "CallableSyncInbox.all") CallableSyncPriority[] getPriority();
}

interface CallableSyncFrame { String getLabel(); }

interface CallableSyncPriority extends CallableSyncFrame { }

@Immutable
interface CallableImmutableBase {
    @DelegateTo(className = "example.ImmutableBinding") int evaluate(int input);
}

@Immutable
interface CallableImmutableDerived extends CallableImmutableBase { }

@Immutable
interface CallableValueResult { String getId(); }

@Immutable
interface CallableNarrowValueResult extends CallableValueResult { }

@Immutable
interface CallableImmutableCovariantBase {
    @DelegateTo(className = "example.BaseBinding") CallableValueResult evaluate();
}

@Immutable
interface CallableImmutableCovariantDerived extends CallableImmutableCovariantBase {
    @Override @DelegateTo(className = "example.DerivedBinding") CallableNarrowValueResult evaluate();
}

interface ICallableImmutableBaseCalls { }

@Immutable
interface CallableSameNamedBindings {
    @DelegateTo(className = "example.first.SharedBinding") int one();
    @DelegateTo(className = "example.second.SharedBinding") int two();
}

@Immutable
interface CallableImmutableObjectOverride {
    @Override @DelegateTo(className = "example.StringBinding") String toString();
}

interface CallableMutableObjectOverride {
    @Override @DelegateTo(className = "example.StringBinding") String toString();
}

interface CallableMutableEquality {
    @Override @DelegateTo(className = "example.EqualityBinding") int hashCode();
    @Override @DelegateTo(className = "example.EqualityBinding") boolean equals(Object other);
}

interface CallableDerivedEquality extends CallableMutableEquality { }

@Immutable
interface CallableImmutableEquality {
    @Override @DelegateTo(className = "example.EqualityBinding") int hashCode();
    @Override @DelegateTo(className = "example.EqualityBinding") boolean equals(Object other);
}

@Immutable
interface CallableInheritedImmutableEquality extends CallableImmutableEquality { }

interface CallableRedeclaredEquality extends CallableMutableEquality {
    @Override @DelegateTo(className = "example.EqualityBinding") int hashCode();
    @Override @DelegateTo(className = "example.EqualityBinding") boolean equals(Object other);
}

interface CallableSplitEquality {
    @Override @DelegateTo(className = "example.HashBinding") int hashCode();
    @Override @DelegateTo(className = "example.EqualityBinding") boolean equals(Object other);
}

public class CallableProjectionTest {
    @Test
    public void projectsStructuredOverloadsModelValuesArraysAndConstructionMetadata() {
        var model = model(CallableGauge.class, CallableResult.class);
        var type = model.findType("CallableGauge").orElseThrow();
        assertEquals(6, type.getDeclaredMethods().size());
        assertEquals(6, type.getEffectiveMethods().size());
        var hook = type.getConstructionHook().orElseThrow();
        assertEquals("onCallableGaugeInstantiated", hook.getName());
        assertEquals("CallableGauge", hook.getDeclaringType());
        assertEquals("example.GaugeBehavior", hook.getJavaBinding());
        var scale = type.getEffectiveMethods().stream().filter(method -> method.getName().equals("scale"))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2, scale.size());
        assertTrue(scale.stream().anyMatch(method -> method.getParameters().get(0).getType().getValueType().isPrimitive()));
        assertTrue(scale.stream().anyMatch(method -> !method.getReturnType().getValueType().isPrimitive()));
        assertTrue(scale.stream().allMatch(method -> method.getReturnType().getValueType().getKind() == ValueType.Kind.INT64));

        var combine = method(type, "combine");
        assertEquals("CallableResult", combine.getReturnType().getValueType().getModelType());
        assertEquals("CallableResult", combine.getParameters().get(0).getType().getValueType().getModelType());
        assertEquals(ValueType.Kind.STRING, combine.getParameters().get(1).getType().getValueType().getKind());
        assertEquals(CallableType.Kind.VOID, method(type, "update").getReturnType().getKind());
        assertEquals("example.DiagnosticBehavior", method(type, "diagnose").getJavaBinding());
        var values = method(type, "sum").getParameters().get(0);
        assertTrue(values.isVarArg());
        assertEquals(CallableType.Kind.ARRAY, values.getType().getKind());
        assertEquals(ValueType.Kind.INT32, values.getType().getElementType().getValueType().getKind());
        assertTrue(values.getType().getElementType().getValueType().isPrimitive());
        CSharpCapabilities.validate(model);

        // TypeScript has one method per name: overloads must differ in their number of parameters.
        var typescript = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(model, "TypeScript"));
        assertTrue(typescript.getMessage().contains("CallableGauge.scale needs overloads that differ in their number"));
        assertFalse(typescript.getMessage().contains("uses behavior delegation"));
    }

    @Test
    public void csharpEmitsJaggedParametersWithExistingLeafAndResultGuards() throws IOException {
        var declarations = new Class<?>[]{CallableJagged.class, CallableResult.class};
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, declarations);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("int Mark(int[]?[]?"));
        assertTrue(source.contains("CallableResult? First(CallableResult?[]?[]?"));
        assertTrue(source.contains("int CountRows(params int[]?[]"));

        rejectGeneration("CallableJaggedResult.read needs portable scalars", CallableJaggedResult.class);
        rejectGeneration("CallableJaggedExternal.mark needs portable scalars", CallableJaggedExternal.class);
        rejectGeneration("CallableJaggedObject.mark needs portable scalars", CallableJaggedObject.class);
        var typescript = new MemoryResourceSet();
        VMF.generate(GenerationTarget.TYPESCRIPT, typescript, declarations);
        String script = typescript.getMemSet().elements().nextElement().asString();
        assertTrue(script.contains("countRows(...arg0: (readonly number[] | null)[]): number {"));
        assertTrue(script.contains("mark(caller: CallableJagged, arg0: readonly (readonly number[] | null)[] | null): number;"));
    }

    @Test
    public void preservesExplicitMethodBindingAndDistinguishesCheckedExceptionsFromProperties() {
        var model = model(CallableActionContract.class, CallableAction.class, CallableChecked.class);
        var contract = model.findType("CallableActionContract").orElseThrow();
        assertEquals("", method(contract, "execute").getJavaBinding());
        assertTrue(contract.getConstructionHook().isEmpty());
        var action = model.findType("CallableAction").orElseThrow();
        assertEquals("CallableAction", method(action, "execute").getDeclaringType());
        assertEquals("example.ActionBehavior", method(action, "execute").getJavaBinding());
        var checked = model.findType("CallableChecked").orElseThrow();
        assertTrue(method(checked, "read").hasCheckedExceptions());
        assertTrue(checked.getProperties().isEmpty());
        assertTrue(checked.getConstructionHook().isEmpty());
    }

    @Test
    public void csharpDiagnosesInactiveSyncWithoutOpeningItsGate() {
        var model = model(CallableSyncInbox.class, CallableSyncFrame.class, CallableSyncPriority.class);
        var csharp = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(model));
        assertTrue(csharp.getMessage().contains("CallableSyncInbox.all declares @SyncWith, which is inactive in the Java generator"));
    }

    @Test
    public void csharpRejectsUnsupportedMethodsBeforeCreatingOutput() {
        rejectGeneration("CallableChecked.read needs an instance behavior method without checked exceptions",
                CallableChecked.class);
        rejectGeneration("CallableStatic.count needs an instance behavior method", CallableStatic.class);
        rejectGeneration("CallableMissingBinding.execute needs a source method binding",
                CallableActionContract.class, CallableMissingBinding.class);
        rejectGeneration("CallableExternal.receive needs portable scalars, concrete models, closed model applications",
                CallableExternal.class);
        CSharpCapabilities.validate(model(CallableHookOnly.class));
    }

    @Test
    public void csharpMergesDisjointUnboundRolesIntoOneModelDispatch() throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, CallableProducerRole.class, CallableConsumerRole.class,
                CallableComposedRoles.class, CallableBoundRoles.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public interface ICallableConsumerRole"));
        assertTrue(source.contains("void Consume();"));
        assertTrue(source.contains("class CallableComposedRoles : CallableProducerRole, ICallableConsumerRole"));
        assertTrue(source.contains("public override void Consume()"));
        assertTrue(source.contains("void Consume(CallableBoundRoles caller);"));

        rejectGeneration("needs a single local binding class in a composed behavior hierarchy",
                CallableProducerRole.class, CallableConsumerRole.class, CallableComposedRoles.class,
                CallableSeparateRoleBindings.class);
        rejectGeneration("CallableOverlappingRoles needs single primary inheritance for C# behavior delegation",
                CallableProducerRole.class, CallableCompetingProducerRole.class, CallableOverlappingRoles.class);
    }

    @Test
    public void preservesAppliedModelsInsideCallableSlots() throws IOException {
        Class<?>[] declarations = {CallableSeries.class, CallableCounts.class, CallableApplied.class,
                CallableAppliedChild.class};
        var model = model(declarations);
        var child = model.findType("CallableAppliedChild").orElseThrow();
        var forward = method(child, "forward");
        assertEquals("CallableApplied", forward.getDeclaringType());
        assertEquals("CallableSeries", forward.getReturnType().getValueType().getModelType());
        var resultArgument = forward.getReturnType().getValueType().getArguments().get(0);
        assertEquals(ValueType.Kind.INT32, resultArgument.getKind());
        assertFalse(resultArgument.isPrimitive());
        var batch = method(child, "batch");
        var array = batch.getParameters().get(0).getType();
        assertEquals(CallableType.Kind.ARRAY, array.getKind());
        assertEquals(CallableType.Kind.ARRAY, batch.getReturnType().getKind());
        assertEquals("CallableSeries", array.getElementType().getValueType().getModelType());
        assertEquals(ValueType.Kind.INT32, array.getElementType().getValueType().getArguments().get(0).getKind());

        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, declarations);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("CallableSeries<int?>? Forward(CallableSeries<int?>?"));
        assertTrue(source.contains("CallableSeries<int?>?[]? Batch(CallableSeries<int?>?[]?"));
        assertTrue(source.contains("CallableSeries<int?>? ICallableAppliedCalls.Forward("));
    }

    @Test
    public void csharpKeepsUnsupportedAppliedCallableShapesOutsideTheGate() {
        rejectGeneration("CallableRaw.take needs portable scalars", CallableSeries.class, CallableRaw.class);
        rejectGeneration("CallableWildcard.take needs portable scalars", CallableSeries.class, CallableWildcard.class);
        rejectGeneration("CallableNested.take needs portable scalars", CallableSeries.class, CallableNested.class);
    }

    @Test
    public void csharpPreservesTypedModelAndScalarListsAsWritableReturnSlots() throws IOException {
        Class<?>[] declarations = {CallableCollectionResult.class, CallableResult.class, CallableScalarCollectionResult.class};
        var result = method(model(declarations).findType("CallableCollectionResult").orElseThrow(), "find").getReturnType();
        assertEquals(CallableType.Kind.LIST, result.getKind());
        assertEquals("CallableResult", result.getElementType().getValueType().getModelType());

        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, declarations);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("global::System.Collections.Generic.IList<CallableResult?>? Find()"));
        assertTrue(source.contains("global::System.Collections.Generic.IList<CallableResult?>? ICallableCollectionResultCalls.Find()"));
        assertTrue(source.contains("global::System.Collections.Generic.IList<int?>? Find()"));
    }

    @Test
    public void csharpPreservesExplicitObjectListResultsWithoutWideningTypeScript() throws IOException {
        var model = model(CallableObjectCollectionResult.class);
        var type = model.findType("CallableObjectCollectionResult").orElseThrow();
        assertTrue(type.getEffectiveProperties().isEmpty());
        for (String name : java.util.List.of("payloads", "directPayloads")) {
            var result = method(type, name).getReturnType();
            assertEquals(CallableType.Kind.LIST, result.getKind());
            assertEquals(Object.class.getName(), result.getElementType().getValueType().getExternalType());
        }

        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, CallableObjectCollectionResult.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("global::System.Collections.Generic.IList<object?>? Payloads()"));
        assertTrue(source.contains("global::System.Collections.Generic.IList<object?>? DirectPayloads()"));

        // TypeScript returns untyped values as unknown.
        var typescript = new MemoryResourceSet();
        VMF.generate(GenerationTarget.TYPESCRIPT, typescript, CallableObjectCollectionResult.class);
        String script = typescript.getMemSet().elements().nextElement().asString();
        assertTrue(script.contains("payloads(): unknown[] | null {"));
        assertTrue(script.contains("directPayloads(caller: CallableObjectCollectionResult): unknown[] | null;"));
    }

    @Test
    public void csharpRejectsRawListReturnsAndCollectionParameters() {
        rejectGeneration("CallableRawCollectionResult.find needs portable scalars", CallableRawCollectionResult.class);
        var output = new MemoryResourceSet();
        var error = assertThrows(RuntimeException.class, () -> VMF.generate(GenerationTarget.CSHARP, output,
                CallableCollectionParameter.class, CallableResult.class));
        assertTrue(error.getMessage(), error.getMessage().contains("Collections can currently not be used as params"));
        assertTrue(output.getMemSet().isEmpty());
    }

    @Test
    public void csharpEmitsImmutableCallsThroughTheirExplicitContract() throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, CallableImmutableBase.class, CallableImmutableDerived.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public interface ICallableImmutableBaseCalls"));
        assertTrue(source.contains("public interface ICallableImmutableDerivedCalls : ICallableImmutableBaseCalls"));
        String implementation = "int ICallableImmutableBaseCalls.Evaluate(";
        assertTrue(source.indexOf(implementation) >= 0);
        assertTrue(source.lastIndexOf(implementation) > source.indexOf(implementation));
        assertTrue(source.contains("private static partial Behavior CreateDefaultBehavior();"));
        assertFalse(source.contains("public int Evaluate("));
        assertFalse(source.contains("ReplaceBehavior("));
    }

    @Test
    public void csharpPreservesTheWiderAndNarrowerImmutableCallContracts() throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output,
                CallableImmutableCovariantBase.class, CallableImmutableCovariantDerived.class,
                CallableValueResult.class, CallableNarrowValueResult.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("new CallableNarrowValueResult? Evaluate();"));
        assertTrue(source.contains("CallableValueResult? ICallableImmutableCovariantBaseCalls.Evaluate()"));
        assertTrue(source.contains("((ICallableImmutableCovariantDerivedCalls)this).Evaluate()"));
    }

    @Test
    public void csharpQualifiesImmutableProvidersWithTheSameSimpleName() throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, CallableSameNamedBindings.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("CreateBehavior_SharedBinding__example_pfirst_pSharedBinding()"));
        assertTrue(source.contains("CreateBehavior_SharedBinding__example_psecond_pSharedBinding()"));
    }

    @Test
    public void csharpEmitsImmutableToStringAsANullableObjectOverride() throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, CallableImmutableObjectOverride.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public override string? ToString()"));
        assertTrue(source.contains("string? ToString(CallableImmutableObjectOverride caller);"));
        assertFalse(source.contains("ICallableImmutableObjectOverrideCalls"));
        assertFalse(source.contains("FormatSummary(this)"));
    }

    @Test
    public void csharpEmitsMutableToStringOnTheObjectAndItsReadOnlyView() throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, CallableMutableObjectOverride.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public override string? ToString() =>\n        __vmfGetBehavior().ToString(this);"));
        assertTrue(source.contains("public override string? ToString() => model.ToString();"));
        assertFalse(source.contains("FormatSummary(this)"));
    }

    @Test
    public void csharpEmitsMutableEqualityPairAsObjectOverrides() throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, CallableMutableEquality.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public override bool Equals(object?"));
        assertTrue(source.contains("public override int GetHashCode()"));
        assertTrue(source.contains("int GetHashCode(CallableMutableEquality caller);"));
        assertTrue(source.contains("public override bool Equals(object? other) => model.Equals(other);"));
        assertTrue(source.contains("public override int GetHashCode() => model.GetHashCode();"));
        assertTrue(source.contains("public bool ContentEquals(object? other)"));
        assertFalse(source.contains("public int HashCode()"));
    }

    @Test
    public void csharpEmitsInheritedMutableEqualityOnItsPrimaryBase() throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, CallableMutableEquality.class, CallableDerivedEquality.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public sealed class CallableDerivedEquality : CallableMutableEquality"));
        assertTrue(source.contains("public override bool Equals(object?"));
        assertTrue(source.contains("return __vmfGetDefaultBehavior().GetHashCode(this);"));
        assertTrue(source.contains("public override int GetHashCode() => model.GetHashCode();"));
        assertTrue(source.contains("Behavior : CallableMutableEquality.Behavior"));
    }

    @Test
    public void csharpEmitsImmutableEqualityPairWithoutAnOrdinaryCallContract() throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, CallableImmutableEquality.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public override bool Equals(object?"));
        assertTrue(source.contains("public override int GetHashCode() =>"));
        assertTrue(source.contains("int GetHashCode(CallableImmutableEquality caller);"));
        assertTrue(source.contains("ContentEquals(CallableImmutableEquality? other)"));
        assertFalse(source.contains("ICallableImmutableEqualityCalls"));
        assertFalse(source.contains("RuntimeHelpers.GetHashCode(this)"));
    }

    @Test
    public void csharpRejectsUnsupportedBehaviorOverridesBeforeCreatingOutput() {
        rejectGeneration("ICallableImmutableBaseCalls", CallableImmutableBase.class, ICallableImmutableBaseCalls.class);
        rejectGeneration("CallableRedeclaredEquality.hashCode needs an instance behavior method",
                CallableMutableEquality.class, CallableRedeclaredEquality.class);
        rejectGeneration("CallableSplitEquality.hashCode needs an instance behavior method",
                CallableSplitEquality.class);
        rejectGeneration("CallableInheritedImmutableEquality.hashCode needs an instance behavior method",
                CallableImmutableEquality.class, CallableInheritedImmutableEquality.class);
    }

    private static void rejectGeneration(String diagnostic, Class<?>... declarations) {
        var output = new MemoryResourceSet();
        var error = assertThrows(IllegalArgumentException.class,
                () -> VMF.generate(GenerationTarget.CSHARP, output, declarations));
        assertTrue(error.getMessage(), error.getMessage().contains(diagnostic));
        assertTrue(output.getMemSet().isEmpty());
    }

    private static ResolvedModel model(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }

    private static ResolvedModel.CallableMethod method(ResolvedModel.Type type, String name) {
        return type.getEffectiveMethods().stream().filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
    }
}
