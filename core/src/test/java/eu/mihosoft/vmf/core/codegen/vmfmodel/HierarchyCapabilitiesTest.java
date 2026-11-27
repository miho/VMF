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

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Refers;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import org.junit.Test;

import static org.junit.Assert.*;

@InterfaceOnly
interface HierarchyCommonLabel {
    @Annotation(key = "vmf:jackson:rename", value = "label") String getName();
}

@InterfaceOnly
interface HierarchyCommonRank {
    @Annotation(key = "vmf:jackson:rename", value = "label") String getName();
    int getRank();
}

@InterfaceOnly
interface HierarchyOtherAlias {
    @Annotation(key = "vmf:jackson:rename", value = "other") String getName();
}

interface HierarchyMerged extends HierarchyCommonLabel, HierarchyCommonRank { }

interface HierarchyConflicting extends HierarchyCommonLabel, HierarchyOtherAlias { }

interface HierarchyConflictReversed extends HierarchyOtherAlias, HierarchyCommonLabel { }

interface HierarchyRedefined extends HierarchyCommonLabel, HierarchyCommonRank {
    @Override String getName();
}

interface HierarchyReaffirmed extends HierarchyCommonLabel, HierarchyCommonRank {
    @Override @Annotation(key = "vmf:jackson:rename", value = "label") String getName();
}

@InterfaceOnly
interface HierarchyLegacyMarkers {
    @Contains String[] getMarkers();
}

interface HierarchyOrdinaryMarkers extends HierarchyLegacyMarkers, HierarchyCommonLabel {
    @Override String[] getMarkers();
}

interface HierarchyInheritedMarkers extends HierarchyLegacyMarkers { }

interface HierarchyConcreteBase { int getNumber(); }

interface HierarchyScalarRedeclaration extends HierarchyConcreteBase {
    @Override @DefaultValue("4") int getNumber();
}

@InterfaceOnly
interface HierarchyMixedGetterOnly {
    @GetterOnly String getName();
    int getRank();
}

interface HierarchyMixedGetterOnlyImplementation extends HierarchyMixedGetterOnly { }

interface HierarchySenders {
    @Refers(opposite = "sender") HierarchyMessage[] getMessages();
}

interface HierarchyMessage {
    @Refers(opposite = "messages") HierarchySenders getSender();
}

interface HierarchyPreservedMessages extends HierarchySenders {
    @Override @Refers(opposite = "sender") HierarchyMessage[] getMessages();
}

interface HierarchyDroppedMessages extends HierarchySenders {
    @Override HierarchyMessage[] getMessages();
}

interface HierarchyBasicEntry { }

interface HierarchyNarrowEntry extends HierarchyBasicEntry { }

interface HierarchyBasicCollection { HierarchyBasicEntry[] getEntries(); }

interface HierarchyNarrowCollection extends HierarchyBasicCollection {
    @Override HierarchyNarrowEntry[] getEntries();
}

@InterfaceOnly
interface HierarchyEntryView {
    @GetterOnly HierarchyBasicEntry getCurrent();
}

interface HierarchyNarrowSelection extends HierarchyEntryView {
    @Override HierarchyNarrowEntry getCurrent();
}

interface HierarchyInheritedSelection extends HierarchyNarrowSelection { }

interface HierarchyExplicitSelection extends HierarchyNarrowSelection {
    @Override HierarchyNarrowEntry getCurrent();
}

interface HierarchyWritableSelection { HierarchyBasicEntry getCurrent(); }

interface HierarchyUnsafeSelection extends HierarchyWritableSelection {
    @Override HierarchyNarrowEntry getCurrent();
}

@InterfaceOnly
interface HierarchySelectionBranch {
    @GetterOnly HierarchyBasicEntry getCurrent();
}

@InterfaceOnly
interface HierarchySelectionDiamond extends HierarchyEntryView, HierarchySelectionBranch { }

@InterfaceOnly
interface HierarchyCovariantSelectionDiamond extends HierarchySelectionDiamond {
    @Override @GetterOnly HierarchyNarrowEntry getCurrent();
}

interface HierarchySelectedDiamond extends HierarchyCovariantSelectionDiamond {
    @Override HierarchyNarrowEntry getCurrent();
}

@InterfaceOnly
interface HierarchyAliasedSelectionDiamond extends HierarchyEntryView, HierarchySelectionBranch {
    @Override @GetterOnly @Annotation(key = "vmf:jackson:rename", value = "other")
    HierarchyNarrowEntry getCurrent();
}

@InterfaceOnly
interface HierarchyConcreteBranch extends HierarchyConcreteBase { }

interface HierarchyMixed extends HierarchyCommonLabel, HierarchyConcreteBranch { }

interface HierarchyConcreteAndTrait extends HierarchyConcreteBase, HierarchyCommonLabel { }

interface HierarchyConcretePair extends HierarchyConcreteBase, HierarchyConcreteSecondary { }

interface HierarchyConcreteSecondary { @DefaultValue("13") int getWeight(); }

@DelegateTo(className = "example.SecondaryBehavior")
interface HierarchySecondaryHook { String getCaption(); }

interface HierarchySecondaryCall {
    @DelegateTo(className = "example.SecondaryBehavior") String caption();
}

interface HierarchyWithSecondaryHook extends HierarchyConcreteBase, HierarchySecondaryHook { }

interface HierarchyWithSecondaryCall extends HierarchyConcreteBase, HierarchySecondaryCall { }

@InterfaceOnly
interface HierarchyDefaultA { @DefaultValue("23") int getRate(); }

@InterfaceOnly
interface HierarchyDefaultB { @DefaultValue("42") int getRate(); }

interface HierarchyDefaultConflict extends HierarchyDefaultA, HierarchyDefaultB { }

@InterfaceOnly
interface HierarchyAliasedDefault {
    @Annotation(key = "vmf:jackson:rename", value = "secondary_rate")
    @DefaultValue("42") int getRate();
}

interface HierarchyAliasedDefaultConflict extends HierarchyDefaultA, HierarchyAliasedDefault { }

@InterfaceOnly
interface HierarchyItems<T extends HierarchyCommonLabel> {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) T[] getItems();
    T getCurrent();
}

@InterfaceOnly
interface HierarchyItemsLeft<L extends HierarchyCommonLabel> extends HierarchyItems<L> { }

@InterfaceOnly
interface HierarchyItemsRight<R extends HierarchyCommonLabel> extends HierarchyItems<R> { }

interface HierarchyEntry extends HierarchyCommonLabel { }

interface HierarchyDiamond extends HierarchyItemsLeft<HierarchyEntry>, HierarchyItemsRight<HierarchyEntry> { }

@InterfaceOnly
interface HierarchyOpenDiamond<T extends HierarchyCommonLabel> extends HierarchyItemsLeft<T>, HierarchyItemsRight<T> { }

interface HierarchyNamedOpenDiamond extends HierarchyOpenDiamond<HierarchyEntry> { }

@InterfaceOnly
interface HierarchyUnrelatedCurrent<T extends HierarchyCommonLabel> { T getCurrent(); }

@InterfaceOnly
interface HierarchyOpenOverlap<T extends HierarchyCommonLabel> extends HierarchyItemsLeft<T>, HierarchyUnrelatedCurrent<T> { }

interface HierarchyVersionedChoice { int getVersion(); }

@InterfaceOnly
interface HierarchySecondaryChoice<T extends HierarchyBasicEntry> { T getCurrent(); }

@InterfaceOnly
interface HierarchyOpenChoice<T extends HierarchyBasicEntry> extends HierarchyVersionedChoice, HierarchySecondaryChoice<T> { }

interface HierarchyClosedChoice extends HierarchyOpenChoice<HierarchyNarrowEntry> { }

@InterfaceOnly
interface HierarchyBackupChoice<U extends HierarchyBasicEntry> { U getBackup(); }

@InterfaceOnly
interface HierarchyOpenPair<T extends HierarchyBasicEntry, U extends HierarchyBasicEntry>
        extends HierarchySecondaryChoice<T>, HierarchyBackupChoice<U> { }

interface HierarchyClosedPair extends HierarchyOpenPair<HierarchyBasicEntry, HierarchyNarrowEntry> { }

@InterfaceOnly
interface HierarchyQueueRole<T extends HierarchyBasicEntry> { T[] getQueue(); }

@InterfaceOnly
interface HierarchyOpenQueue<T extends HierarchyBasicEntry> extends HierarchyVersionedChoice, HierarchyQueueRole<T> { }

interface HierarchyClosedQueue extends HierarchyOpenQueue<HierarchyNarrowEntry> { }

public class HierarchyCapabilitiesTest {
    @Test
    public void compatibleDuplicatePropertiesKeepOneResolvedSlot() {
        var model = model(HierarchyCommonLabel.class, HierarchyCommonRank.class, HierarchyMerged.class);
        var merged = model.findType("HierarchyMerged").orElseThrow();
        assertEquals(2, merged.getEffectiveProperties().size());
        assertEquals("label", merged.getEffectiveProperties().stream().filter(property -> property.getName().equals("name"))
                .findFirst().orElseThrow().getWireName());

        CSharpCapabilities.validate(model);
        // TypeScript extends the first interface-only supertype and implements the second.
        PortableCapabilities.validate(model, "TypeScript");
    }

    @Test
    public void conflictingAliasesAreDiagnosedWithoutChangingJavaPrecedence() {
        var model = model(HierarchyCommonLabel.class, HierarchyOtherAlias.class, HierarchyConflicting.class,
                HierarchyConflictReversed.class);
        assertEquals("label", model.findType("HierarchyConflicting").orElseThrow().getEffectiveProperties().get(0).getWireName());
        assertEquals("other", model.findType("HierarchyConflictReversed").orElseThrow().getEffectiveProperties().get(0).getWireName());
        var csharp = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(model));
        assertTrue(csharp.getMessage(), csharp.getMessage().contains("HierarchyConflicting.name inherits incompatible property metadata"));
        assertTrue(csharp.getMessage(), csharp.getMessage().contains("HierarchyConflictReversed.name inherits incompatible property metadata"));
        var typescript = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(model, "TypeScript"));
        assertFalse(typescript.getMessage(), typescript.getMessage().contains("inherits incompatible property metadata"));
        assertTrue(typescript.getMessage(), typescript.getMessage()
                .contains("HierarchyConflicting.name inherits conflicting declarations from its model supertypes"));
    }

    @Test
    public void forwardedModelTypeParametersAgreeAfterClosingTheDiamond() {
        var model = model(HierarchyCommonLabel.class, HierarchyItems.class, HierarchyItemsLeft.class,
                HierarchyItemsRight.class, HierarchyEntry.class, HierarchyDiamond.class);
        var diamond = model.findType("HierarchyDiamond").orElseThrow();
        assertEquals(2, diamond.getSuperTypeApplications().size());
        assertEquals(2, diamond.getEffectiveProperties().size());
        assertTrue(diamond.getEffectiveProperties().stream()
                .allMatch(property -> "HierarchyEntry".equals(property.getValueType().getModelType())));

        CSharpCapabilities.validate(model);
    }

    @Test
    public void csharpAllowsExactOpenOwningDiamondsAndDefersUnrelatedOverlaps() {
        var open = model(HierarchyCommonLabel.class, HierarchyItems.class, HierarchyItemsLeft.class,
                HierarchyItemsRight.class, HierarchyEntry.class, HierarchyOpenDiamond.class, HierarchyNamedOpenDiamond.class);
        CSharpCapabilities.validate(open);
        var named = open.findType("HierarchyNamedOpenDiamond").orElseThrow();
        assertEquals(2, named.getEffectiveProperties().size());
        assertTrue(named.getEffectiveProperties().stream().allMatch(property -> "HierarchyEntry".equals(property.getValueType().getModelType())));
        var typescript = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(open, "TypeScript"));
        assertTrue(typescript.getMessage(), typescript.getMessage()
                .contains("HierarchyOpenDiamond combines several model supertypes with type parameters"));

        var unrelated = model(HierarchyCommonLabel.class, HierarchyItems.class, HierarchyItemsLeft.class,
                HierarchyUnrelatedCurrent.class, HierarchyOpenOverlap.class);
        var overlapError = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(unrelated));
        assertTrue(overlapError.getMessage(), overlapError.getMessage().contains("HierarchyOpenOverlap needs closed model type arguments"));
    }

    @Test
    public void csharpLetsAnExplicitScalarRedeclarationChooseItsWireName() {
        var redeclared = model(HierarchyCommonLabel.class, HierarchyCommonRank.class, HierarchyRedefined.class);
        CSharpCapabilities.validate(redeclared);
        var owner = redeclared.findType("HierarchyRedefined").orElseThrow();
        var name = owner.getEffectiveProperties().stream().filter(property -> property.getName().equals("name"))
                .findFirst().orElseThrow();
        assertEquals("HierarchyRedefined", name.getDeclaringType());
        assertEquals("name", name.getWireName());
        var redefineError = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(redeclared, "TypeScript"));
        assertTrue(redefineError.getMessage(), redefineError.getMessage().contains(".name redeclares an inherited property"));
    }

    @Test
    public void csharpAllowsAnOpenSecondaryToForwardANamedModelBound() {
        var model = model(HierarchyBasicEntry.class, HierarchyNarrowEntry.class, HierarchyVersionedChoice.class,
                HierarchySecondaryChoice.class, HierarchyOpenChoice.class, HierarchyClosedChoice.class);
        CSharpCapabilities.validate(model);
        var open = model.findType("HierarchyOpenChoice").orElseThrow();
        var closed = model.findType("HierarchyClosedChoice").orElseThrow();
        assertEquals("T", open.getEffectiveProperties().stream().filter(property -> property.getName().equals("current"))
                .findFirst().orElseThrow().getValueType().getParameterName());
        assertEquals("HierarchyNarrowEntry", closed.getEffectiveProperties().stream()
                .filter(property -> property.getName().equals("current")).findFirst().orElseThrow().getValueType().getModelType());
        var typescript = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(model, "TypeScript"));
        assertTrue(typescript.getMessage(), typescript.getMessage()
                .contains("HierarchyOpenChoice combines several model supertypes with type parameters"));
    }

    @Test
    public void csharpKeepsDisjointOpenRolesAndOrdinaryReferenceLists() {
        var model = model(HierarchyBasicEntry.class, HierarchyNarrowEntry.class, HierarchyVersionedChoice.class,
                HierarchySecondaryChoice.class, HierarchyBackupChoice.class, HierarchyOpenPair.class,
                HierarchyClosedPair.class, HierarchyQueueRole.class, HierarchyOpenQueue.class, HierarchyClosedQueue.class);
        CSharpCapabilities.validate(model);
        var properties = model.findType("HierarchyClosedPair").orElseThrow().getEffectiveProperties();
        assertEquals(2, properties.size());
        assertEquals("HierarchyNarrowEntry", properties.stream().filter(property -> property.getName().equals("backup"))
                .findFirst().orElseThrow().getValueType().getModelType());
        assertEquals("HierarchyBasicEntry", properties.stream().filter(property -> property.getName().equals("current"))
                .findFirst().orElseThrow().getValueType().getModelType());
        var queue = model.findType("HierarchyClosedQueue").orElseThrow().getEffectiveProperties().stream()
                .filter(property -> property.getName().equals("queue")).findFirst().orElseThrow();
        assertTrue(queue.isCollection() && queue.isOrdered() && !queue.isUnique() && queue.allowsNullElements());
        assertEquals("HierarchyNarrowEntry", queue.getValueType().getModelType());
    }

    @Test
    public void csharpNarrowsOnlyCompatibleGetterOnlyDiamondSlots() {
        var compatible = model(HierarchyBasicEntry.class, HierarchyNarrowEntry.class, HierarchyEntryView.class,
                HierarchySelectionBranch.class, HierarchySelectionDiamond.class,
                HierarchyCovariantSelectionDiamond.class, HierarchySelectedDiamond.class);
        CSharpCapabilities.validate(compatible);
        var fields = compatible.findType("HierarchySelectedDiamond").orElseThrow().getEffectiveProperties();
        assertEquals(1, fields.size());
        assertEquals("HierarchyNarrowEntry", fields.get(0).getValueType().getModelType());

        var differentWireName = model(HierarchyBasicEntry.class, HierarchyNarrowEntry.class, HierarchyEntryView.class,
                HierarchySelectionBranch.class, HierarchyAliasedSelectionDiamond.class);
        var error = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(differentWireName));
        assertTrue(error.getMessage(), error.getMessage().contains("inherits incompatible property metadata"));
    }

    @Test
    public void repeatedDeclaredMembersNeedTheSameInheritedContract() {
        var exact = model(HierarchyCommonLabel.class, HierarchyCommonRank.class, HierarchyReaffirmed.class);
        CSharpCapabilities.validate(exact);
        var properties = exact.findType("HierarchyReaffirmed").orElseThrow().getEffectiveProperties();
        assertEquals(2, properties.size());
        assertEquals("label", properties.stream().filter(field -> field.getName().equals("name"))
                .findFirst().orElseThrow().getWireName());
    }

    @Test
    public void dormantScalarTraitContainmentRequiresAnExplicitReplacementOnAllConcretePaths() {
        var shadowed = model(HierarchyLegacyMarkers.class, HierarchyCommonLabel.class, HierarchyOrdinaryMarkers.class);
        CSharpCapabilities.validate(shadowed);
        var markers = shadowed.findType("HierarchyLegacyMarkers").orElseThrow().getProperties().get(0);
        assertEquals(ResolvedModel.Property.Kind.SCALAR, markers.getKind());

        var inherited = model(HierarchyLegacyMarkers.class, HierarchyCommonLabel.class, HierarchyOrdinaryMarkers.class,
                HierarchyInheritedMarkers.class);
        var error = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(inherited));
        assertTrue(error.getMessage(), error.getMessage().contains("HierarchyLegacyMarkers.markers needs a single-valued containment"));
    }

    @Test
    public void csharpAllowsConcreteDataAndBoundMethodsOnSecondaryRoots() {
        var mixed = model(HierarchyCommonLabel.class, HierarchyConcreteBase.class, HierarchyConcreteBranch.class,
                HierarchyMixed.class, HierarchyConcreteSecondary.class, HierarchyConcretePair.class);
        CSharpCapabilities.validate(mixed);
        assertEquals(13, mixed.findType("HierarchyConcretePair").orElseThrow().getEffectiveProperties().stream()
                .filter(property -> property.getName().equals("weight")).findFirst().orElseThrow()
                .getScalarDefault().orElseThrow().getValue());
        CSharpCapabilities.validate(model(HierarchyConcreteBase.class, HierarchySecondaryCall.class,
                HierarchyWithSecondaryCall.class));
        var behavior = model(HierarchyConcreteBase.class, HierarchySecondaryHook.class, HierarchyWithSecondaryHook.class);
        var error = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(behavior));
        assertTrue(error.getMessage(), error.getMessage().contains("needs a mutable data-only secondary base"));
        assertTrue(error.getMessage(), error.getMessage().contains("HierarchySecondaryHook"));
    }

    @Test
    public void csharpSelectsFirstInheritedLiteralAndRejectsConflictingWireNames() {
        CSharpCapabilities.validate(model(HierarchyConcreteBase.class, HierarchyCommonLabel.class, HierarchyConcreteAndTrait.class));

        var defaults = model(HierarchyDefaultA.class, HierarchyDefaultB.class, HierarchyDefaultConflict.class);
        var root = defaults.findType("HierarchyDefaultConflict").orElseThrow();
        assertEquals(23, root.getEffectiveProperties().get(0).getScalarDefault().orElseThrow().getValue());
        CSharpCapabilities.validate(defaults);

        var names = model(HierarchyDefaultA.class, HierarchyAliasedDefault.class, HierarchyAliasedDefaultConflict.class);
        var csharp = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(names));
        assertTrue(csharp.getMessage(), csharp.getMessage().contains("HierarchyAliasedDefaultConflict.rate inherits incompatible property metadata"));
    }

    @Test
    public void csharpAcceptsAnExactScalarRedeclarationAndMixedGetterOnlyTrait() {
        var model = model(HierarchyConcreteBase.class, HierarchyScalarRedeclaration.class,
                HierarchyMixedGetterOnly.class, HierarchyMixedGetterOnlyImplementation.class);
        var scalar = model.findType("HierarchyScalarRedeclaration").orElseThrow().getEffectiveProperties();
        assertEquals(1, scalar.size());
        assertEquals(4, scalar.get(0).getScalarDefault().orElseThrow().getValue());
        var trait = model.findType("HierarchyMixedGetterOnly").orElseThrow();
        assertFalse(trait.isGetterOnlyTrait());
        assertTrue(trait.getProperties().stream().filter(property -> property.getName().equals("name"))
                .findFirst().orElseThrow().isGetterOnly());

        CSharpCapabilities.validate(model);
        var typescript = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(model, "TypeScript"));
        assertTrue(typescript.getMessage(), typescript.getMessage().contains(".number redeclares an inherited property"));
        assertTrue(typescript.getMessage(), typescript.getMessage().contains("HierarchyMixedGetterOnly.name uses read-only properties"));
    }

    @Test
    public void csharpRequiresMatchingCollectionElementTypesAndRelationshipContracts() {
        var preserved = model(HierarchyMessage.class, HierarchySenders.class, HierarchyPreservedMessages.class);
        CSharpCapabilities.validate(preserved);
        var typescript = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(preserved, "TypeScript"));
        assertTrue(typescript.getMessage(), typescript.getMessage().contains(".messages redeclares an inherited property"));

        var dropped = model(HierarchyMessage.class, HierarchySenders.class, HierarchyDroppedMessages.class);
        var missingOpposite = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(dropped));
        assertTrue(missingOpposite.getMessage(), missingOpposite.getMessage()
                .contains("HierarchyDroppedMessages.messages redeclares an inherited property"));

        var narrowed = model(HierarchyBasicEntry.class, HierarchyNarrowEntry.class,
                HierarchyBasicCollection.class, HierarchyNarrowCollection.class);
        var covariance = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(narrowed));
        assertTrue(covariance.getMessage(), covariance.getMessage()
                .contains("HierarchyNarrowCollection.entries redeclares an inherited property"));
    }

    @Test
    public void csharpCovarianceRequiresOneEffectiveGetterAndNoBroaderWritableBase() {
        var qualified = model(HierarchyBasicEntry.class, HierarchyNarrowEntry.class, HierarchyEntryView.class,
                HierarchyNarrowSelection.class, HierarchyExplicitSelection.class);
        CSharpCapabilities.validate(qualified);
        var typescript = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(qualified, "TypeScript"));
        assertTrue(typescript.getMessage(), typescript.getMessage()
                .contains("HierarchyNarrowSelection.current redeclares an inherited property"));

        var inherited = model(HierarchyBasicEntry.class, HierarchyNarrowEntry.class, HierarchyEntryView.class,
                HierarchyNarrowSelection.class, HierarchyInheritedSelection.class);
        var conflicting = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(inherited));
        assertTrue(conflicting.getMessage(), conflicting.getMessage().contains(
                "HierarchyInheritedSelection.current inherits different model targets; explicitly redeclare the narrower getter"));

        var writable = model(HierarchyBasicEntry.class, HierarchyNarrowEntry.class,
                HierarchyWritableSelection.class, HierarchyUnsafeSelection.class);
        var unsafe = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(writable));
        assertTrue(unsafe.getMessage(), unsafe.getMessage().contains("HierarchyUnsafeSelection.current redeclares an inherited property"));
    }

    private static ResolvedModel model(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }
}
