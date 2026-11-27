package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.ExternalType;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

interface SecondaryDispatchPrimary {
    @DelegateTo(className = "example.PrimaryBinding") int primary();
}

interface SecondaryDispatchRole {
    int getWeight();
    @DelegateTo(className = "example.RoleBinding") int secondary();
}

interface SecondaryDispatchInheritedRole extends SecondaryDispatchRole { }

interface SecondaryDispatchInheritedBase extends SecondaryDispatchPrimary, SecondaryDispatchInheritedRole { }

@InterfaceOnly
interface SecondaryDispatchAbstractRoot {
    @DelegateTo(className = "example.RoleBinding") int secondary();
}

@InterfaceOnly
interface SecondaryDispatchDecoratedTrait extends SecondaryDispatchAbstractRoot {
    @DelegateTo(className = "example.DecoratedBinding") int decorate();
}

interface SecondaryDispatchDecorated extends SecondaryDispatchPrimary, SecondaryDispatchDecoratedTrait { }

interface SecondaryDispatchAmbiguousRole {
    @DelegateTo(className = "example.RoleBinding") int primary();
}

interface SecondaryDispatchThirdRole {
    @DelegateTo(className = "example.ThirdBinding") int primary();
}

interface SecondaryDispatchJoined extends SecondaryDispatchPrimary, SecondaryDispatchAmbiguousRole { }

interface SecondaryDispatchNested extends SecondaryDispatchJoined, SecondaryDispatchThirdRole { }

interface SecondaryDispatchRepeated extends SecondaryDispatchJoined, SecondaryDispatchAmbiguousRole { }

@DelegateTo(className = "example.RootBinding")
interface LinearBehaviorRoot { int root(); }

@DelegateTo(className = "example.SharedSiblingBinding")
interface LinearBehaviorLeft extends LinearBehaviorRoot { int left(); }

@DelegateTo(className = "example.SharedSiblingBinding")
interface LinearBehaviorRight extends LinearBehaviorRoot { int right(); }

interface LinearBehaviorJoin extends LinearBehaviorLeft, LinearBehaviorRight { }

@ExternalType(pkgName = "java.net")
@Annotation(key = "vmf:csharp:external-value-type", value = "System.Guid")
interface URI { }

interface SharedClrPrimary {
    @DelegateTo(className = "example.GuidBinding") int classify(java.util.UUID value);
}

interface SharedClrRole {
    @DelegateTo(className = "example.GuidBinding") int classify(URI value);
}

interface SharedClrJoin extends SharedClrPrimary, SharedClrRole { }

interface NestedClrRole {
    @DelegateTo(className = "example.NestedBinding") int classify(URI value);
}

interface NestedClrJoin extends SharedClrPrimary, SecondaryDispatchRole { }

interface NestedClrCollision extends NestedClrJoin, NestedClrRole { }

interface SharedReturnPrimary {
    @DelegateTo(className = "example.ReturnBinding") SecondaryDispatchNarrowValue choose();
}

interface SharedReturnRole {
    @DelegateTo(className = "example.ReturnBinding") SecondaryDispatchValue choose();
}

interface SharedReturnJoin extends SharedReturnPrimary, SharedReturnRole { }

interface SharedReturnWideFirst extends SharedReturnRole, SharedReturnPrimary { }

interface SecondaryDispatchSplitRole {
    @DelegateTo(className = "example.FirstRoleBinding") int first();
    @DelegateTo(className = "example.SecondRoleBinding") int second();
}

interface SecondaryDispatchSplit extends SecondaryDispatchPrimary, SecondaryDispatchSplitRole { }

interface SecondaryDispatchValue { String getName(); }

interface SecondaryDispatchNarrowValue extends SecondaryDispatchValue { }

interface SecondaryDispatchReturnsRole {
    @DelegateTo(className = "example.RoleBinding") SecondaryDispatchValue choose();
}

interface SecondaryDispatchNarrowReturn extends SecondaryDispatchPrimary, SecondaryDispatchReturnsRole {
    @Override @DelegateTo(className = "example.OverrideBinding") SecondaryDispatchNarrowValue choose();
}

@InterfaceOnly
interface PrimaryBehaviorDataA { String getTitle(); }

@InterfaceOnly
interface PrimaryBehaviorDataB { int getCount(); }

interface PrimaryBehaviorSplit extends PrimaryBehaviorDataA, PrimaryBehaviorDataB {
    @DelegateTo(className = "example.FirstBinding") int first();
    @DelegateTo(className = "example.SecondBinding") int second();
}

interface PrimaryBehaviorShared extends PrimaryBehaviorDataA, PrimaryBehaviorDataB {
    @DelegateTo(className = "example.SharedBinding") int first();
}

interface PrimaryBehaviorSharedChild extends PrimaryBehaviorShared {
    @DelegateTo(className = "example.SharedBinding") int second();
}

public class SecondaryBehaviorCapabilitiesTest {
    @Test
    public void keepsBindingLifetimeGuardsWhenOnlyTheSecondaryBasesAreData() {
        rejects("single local binding class", PrimaryBehaviorDataA.class, PrimaryBehaviorDataB.class,
                PrimaryBehaviorSplit.class);
        rejects("distinct Java binding classes", PrimaryBehaviorDataA.class, PrimaryBehaviorDataB.class,
                PrimaryBehaviorShared.class, PrimaryBehaviorSharedChild.class);
    }

    @Test
    public void rejectsUnsupportedSecondaryInheritanceAndSignatureConflicts() {
        rejects("needs a mutable data-only secondary base", SecondaryDispatchPrimary.class,
                SecondaryDispatchAbstractRoot.class, SecondaryDispatchDecoratedTrait.class, SecondaryDispatchDecorated.class);
        rejects("different result type", SecondaryDispatchPrimary.class, SecondaryDispatchReturnsRole.class,
                SecondaryDispatchValue.class, SecondaryDispatchNarrowValue.class, SecondaryDispatchNarrowReturn.class);
    }

    @Test
    public void inheritsTheProviderOfAnUndecoratedSecondaryRole() throws Exception {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, SecondaryDispatchPrimary.class, SecondaryDispatchRole.class,
                SecondaryDispatchInheritedRole.class, SecondaryDispatchInheritedBase.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("class SecondaryDispatchInheritedBase : SecondaryDispatchPrimary, ISecondaryDispatchInheritedRole"));
        assertTrue(source.contains("SecondaryDispatchRole.__vmfGetDefaultBehavior(this).Secondary(this)"));
    }

    @Test
    public void coalescesTheLocalBindingOfLinearSiblingRoles() throws Exception {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, LinearBehaviorRoot.class, LinearBehaviorLeft.class,
                LinearBehaviorRight.class, LinearBehaviorJoin.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public interface SharedSiblingBinding : LinearBehaviorLeft.DefaultBehavior, "
                + "LinearBehaviorRight.DefaultBehavior"));
        assertTrue(source.contains("LinearBehaviorRoot.Behavior.Root(ILinearBehaviorRoot caller)"));
    }

    @Test
    public void lowersNestedCompetingBoundRolesWithoutCollapsingDistinctJavaSignatures() throws Exception {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, SecondaryDispatchPrimary.class, SecondaryDispatchAmbiguousRole.class,
                SecondaryDispatchJoined.class, SecondaryDispatchThirdRole.class, SecondaryDispatchNested.class,
                SecondaryDispatchRepeated.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("SecondaryDispatchJoined.Behavior.Primary(SecondaryDispatchJoined caller)"));
        rejects("distinct Java callables map to the same CLR signature", URI.class, SharedClrPrimary.class,
                SecondaryDispatchRole.class, NestedClrRole.class, NestedClrJoin.class, NestedClrCollision.class);
    }

    @Test
    public void rejectsBindingLifetimesWithSplitLocalBindings() {
        rejects("single local binding class", SecondaryDispatchPrimary.class, SecondaryDispatchSplitRole.class,
                SecondaryDispatchSplit.class);
    }

    @Test
    public void checksSourceSignaturesAndSelectedResultTypesInSharedBindings() throws Exception {
        rejects("distinct Java callables map to the same CLR signature", URI.class, SharedClrPrimary.class, SharedClrRole.class,
                SharedClrJoin.class);
        rejects("different result type", SharedReturnPrimary.class, SharedReturnRole.class, SharedReturnWideFirst.class,
                SecondaryDispatchValue.class, SecondaryDispatchNarrowValue.class);

        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, SharedReturnPrimary.class, SharedReturnRole.class, SharedReturnJoin.class,
                SecondaryDispatchValue.class, SecondaryDispatchNarrowValue.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("SecondaryDispatchNarrowValue? Choose(object caller)"));
        assertTrue(source.contains("SecondaryDispatchValue? ISharedReturnRole.Choose()"));
    }

    private static void rejects(String diagnostic, Class<?>... declarations) {
        var output = new MemoryResourceSet();
        var failure = assertThrows(IllegalArgumentException.class, () -> VMF.generate(GenerationTarget.CSHARP, output, declarations));
        assertTrue(failure.getMessage(), failure.getMessage().contains(diagnostic));
        assertTrue(output.getMemSet().isEmpty());
    }
}
