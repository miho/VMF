/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.VMFEquals;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.*;

@Immutable
@VMFEquals(VMFEquals.EqualsType.ALL)
interface ImmutableCollectionBase {
    @DefaultValue("-0.0d") Double getBias();
    String[] getNames();
    @Multiplicity(upper = 2) List<Integer> getCounts();
    Set<Double> getLevels();
    VMap<Double, Integer> getFlags();
}

@Immutable
interface ImmutableCollectionDerived extends ImmutableCollectionBase {
    ImmutableCollectionTag[] getHistory();
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) ImmutableCollectionTag[] getOrder();
    Set<ImmutableCollectionTag> getSelection();
}

@Immutable
interface ImmutableCollectionTag { String getText(); }

@Immutable
interface InvalidImmutableContainment { @Contains ImmutableCollectionTag getTag(); }

interface MutableCollectionTag { String getText(); }

@Immutable
interface InvalidImmutableReference { MutableCollectionTag[] getTags(); }

@InterfaceOnly
interface ImmutableCollectionTrait { @GetterOnly String getTitle(); }

@Immutable
interface ImmutableTraitValue extends ImmutableCollectionTrait { }

@InterfaceOnly
interface InheritedImmutableCollectionTrait extends ImmutableCollectionTrait { }

@InterfaceOnly
interface ImmutableSourceTrait { @GetterOnly @DefaultValue("\"local\"") String getSource(); }

@Immutable
interface ImmutableBaseAndTraits extends ImmutableCollectionBase, InheritedImmutableCollectionTrait, ImmutableSourceTrait { }

interface MutableTraitValue extends InheritedImmutableCollectionTrait, ImmutableSourceTrait { }

@Immutable
interface ImmutableSecondConcrete extends ImmutableCollectionBase, ImmutableCollectionTag { }

@Immutable
interface ImmutableBehaviorSecondary {
    @DelegateTo(className = "example.CounterBinding") int counter();
}

@Immutable
interface ImmutableBehaviorComposition extends ImmutableCollectionBase, ImmutableBehaviorSecondary { }

@Immutable
interface ImmutableBehaviorPrimary {
    @DelegateTo(className = "example.PrimaryBinding") int initial();
}

@Immutable
interface ImmutableMultipleBehaviorBases extends ImmutableBehaviorPrimary, ImmutableBehaviorSecondary { }

@Immutable
interface ImmutableLeftDefault { @DefaultValue("5") int getRank(); }

@Immutable
interface ImmutableRightDefault { @DefaultValue("11") int getRank(); }

@Immutable
interface ImmutableAmbiguousDefault extends ImmutableLeftDefault, ImmutableRightDefault { }

@InterfaceOnly
interface GenericTraitUser<T extends ImmutableCollectionTrait> { T getTagged(); }

interface ClosedGenericTraitUser extends GenericTraitUser<ImmutableTraitValue> { }

@InterfaceOnly
interface GenericImmutableUser<T extends ImmutableCollectionTag> { T getTagged(); }

interface ClosedGenericImmutableUser extends GenericImmutableUser<ImmutableCollectionTag> { }

@Immutable
interface ImmutableCollectionOverride extends ImmutableCollectionBase {
    @Override String[] getNames();
}

public class ImmutableCollectionsTest {
    @Test
    public void nativeImmutableShapesAndMixedEqualityUseExistingSharedKinds() {
        var model = model(ImmutableCollectionBase.class, ImmutableCollectionDerived.class, ImmutableCollectionTag.class);
        CSharpCapabilities.validate(model);
        var base = model.findType("ImmutableCollectionBase").orElseThrow();
        var derived = model.findType("ImmutableCollectionDerived").orElseThrow();
        assertTrue(base.isStructuralEquality());
        assertFalse(derived.isStructuralEquality());
        assertEquals(-0.0d, property(derived, "bias").getScalarDefault().orElseThrow().getValue());
        assertTrue(property(derived, "names").allowsNullElements());
        assertFalse(property(derived, "counts").allowsNullElements());
        assertEquals(Property.Kind.MAP, property(derived, "flags").getKind());
        assertTrue(property(derived, "history").allowsNullElements());
        assertTrue(property(derived, "order").isUnique());
        assertTrue(property(derived, "order").isOrdered());
        assertTrue(property(derived, "selection").isUnique());
        assertFalse(property(derived, "selection").isOrdered());
        var typescript = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(model, "TypeScript"));
        assertTrue(typescript.getMessage(), typescript.getMessage().contains("ImmutableCollectionBase uses an immutable type"));
    }

    @Test
    public void javaRejectsImmutableContainmentAndMutableReferences() {
        var containment = assertThrows(RuntimeException.class,
                () -> model(InvalidImmutableContainment.class, ImmutableCollectionTag.class));
        assertTrue(containment.getMessage(), containment.getMessage().contains("cannot be container"));
        var mutableTarget = assertThrows(RuntimeException.class,
                () -> model(InvalidImmutableReference.class, MutableCollectionTag.class));
        assertTrue(mutableTarget.getMessage(), mutableTarget.getMessage().contains("mutable element type"));
    }

    @Test
    public void csharpAcceptsGetterOnlyTraitsWithMutableAndImmutableImplementations() {
        var model = model(ImmutableCollectionBase.class, ImmutableCollectionTrait.class, InheritedImmutableCollectionTrait.class,
                ImmutableSourceTrait.class, ImmutableTraitValue.class, ImmutableBaseAndTraits.class, MutableTraitValue.class);
        CSharpCapabilities.validate(model);
        assertTrue(model.findType("ImmutableCollectionTrait").orElseThrow().isGetterOnlyTrait());
        assertTrue(model.findType("InheritedImmutableCollectionTrait").orElseThrow().isGetterOnlyTrait());
        assertFalse(model.findType("ImmutableTraitValue").orElseThrow().isGetterOnlyTrait());
        assertFalse(model.findType("MutableTraitValue").orElseThrow().isGetterOnlyTrait());

        var composed = model.findType("ImmutableBaseAndTraits").orElseThrow();
        assertEquals("ImmutableCollectionBase", composed.getSuperTypes().get(0));
        assertTrue(property(composed, "title").isGetterOnly());
        assertEquals("local", property(composed, "source").getScalarDefault().orElseThrow().getValue());
        var typescript = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(model, "TypeScript"));
        assertTrue(typescript.getMessage(), typescript.getMessage().contains("ImmutableTraitValue uses an immutable type"));
        assertTrue(typescript.getMessage(), typescript.getMessage().contains("ImmutableCollectionTrait.title uses read-only properties"));
    }

    @Test
    public void csharpAcceptsGetterOnlyTraitAsGenericBound() {
        var model = model(ImmutableCollectionTrait.class, ImmutableTraitValue.class, GenericTraitUser.class,
                ClosedGenericTraitUser.class);
        CSharpCapabilities.validate(model);
        var closed = model.findType("ClosedGenericTraitUser").orElseThrow();
        assertEquals("ImmutableTraitValue", property(closed, "tagged").getValueType().getModelType());
    }

    @Test
    public void csharpAcceptsConcreteImmutableGenericBounds() {
        var model = model(ImmutableCollectionTag.class, GenericImmutableUser.class, ClosedGenericImmutableUser.class);
        CSharpCapabilities.validate(model);
        var closed = model.findType("ClosedGenericImmutableUser").orElseThrow();
        assertEquals("ImmutableCollectionTag", property(closed, "tagged").getValueType().getModelType());
    }

    @Test
    public void csharpComposesImmutableDataWithOneBehaviorBase() {
        var data = model(ImmutableCollectionBase.class, ImmutableCollectionTag.class, ImmutableSecondConcrete.class);
        CSharpCapabilities.validate(data);
        assertEquals(List.of("ImmutableCollectionBase", "ImmutableCollectionTag"),
                data.findType("ImmutableSecondConcrete").orElseThrow().getSuperTypes());
        var behavior = model(ImmutableCollectionBase.class, ImmutableBehaviorSecondary.class, ImmutableBehaviorComposition.class);
        CSharpCapabilities.validate(behavior);
        var inherited = behavior.findType("ImmutableBehaviorComposition").orElseThrow().getEffectiveMethods();
        assertEquals(1, inherited.size());
        assertEquals("ImmutableBehaviorSecondary", inherited.get(0).getDeclaringType());
    }

    @Test
    public void csharpComposesMultipleConcreteImmutableBehaviorBranches() {
        var multiple = model(ImmutableBehaviorPrimary.class, ImmutableBehaviorSecondary.class,
                ImmutableMultipleBehaviorBases.class);
        CSharpCapabilities.validate(multiple);
        var calls = multiple.findType("ImmutableMultipleBehaviorBases").orElseThrow().getEffectiveMethods();
        assertEquals(2, calls.size());
        assertEquals(Set.of("ImmutableBehaviorPrimary", "ImmutableBehaviorSecondary"),
                calls.stream().map(method -> method.getDeclaringType()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    public void csharpSelectsFirstImmutableParentLiteralDefault() {
        var resolved = model(ImmutableLeftDefault.class, ImmutableRightDefault.class, ImmutableAmbiguousDefault.class);
        var owner = resolved.findType("ImmutableAmbiguousDefault").orElseThrow();
        assertEquals(5, property(owner, "rank").getScalarDefault().orElseThrow().getValue());
        CSharpCapabilities.validate(resolved);
    }

    private static Property property(ResolvedModel.Type owner, String name) {
        return owner.getEffectiveProperties().stream().filter(property -> property.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    public void immutableCollectionRedeclarationKeepsOneEffectiveSlot() {
        var model = model(ImmutableCollectionBase.class, ImmutableCollectionOverride.class);
        var overridden = model.findType("ImmutableCollectionOverride").orElseThrow();
        assertEquals(1L, overridden.getEffectiveProperties().stream().filter(p -> p.getName().equals("names")).count());
        assertTrue(property(overridden, "names").allowsNullElements());
        CSharpCapabilities.validate(model);
        var typescript = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(model, "TypeScript"));
        assertTrue(typescript.getMessage(), typescript.getMessage().contains(".names redeclares an inherited property"));
    }

    private static ResolvedModel model(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }
}
