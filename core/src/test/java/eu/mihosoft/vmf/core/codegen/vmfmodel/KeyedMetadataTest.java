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

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType.Kind;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

interface KeyedMetadataCatalog extends KeyedMetadataInventory {
    @Contains @Keyed(by = "target") KeyedMetadataAssignment[] getAssignments();

    @Contains @Keyed(by = "number") @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    KeyedMetadataNumber[] getNumbered();
}

interface KeyedPortableCatalog extends KeyedMetadataInventory {
    @Contains @Keyed(by = "target") @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    KeyedMetadataAssignment[] getAssignments();
}

@InterfaceOnly
interface KeyedMetadataOpenCatalog<E extends KeyedMetadataItem> {
    @Contains @Keyed(by = "sku") E[] getItems();
}

@InterfaceOnly
interface KeyedMetadataSelection<E extends KeyedMetadataItemBase> {
    @Contains @Keyed(by = "sku")
    @Annotation(key = "vmf:jackson:rename", value = "selected_items")
    E[] getItems();
}

interface KeyedMetadataClosedSelection extends KeyedMetadataSelection<KeyedMetadataItem> { }

@InterfaceOnly
@Annotation(key = "vmf:wire:type", value = "storage")
interface KeyedMetadataInventory {
    @Contains @Keyed(by = "sku")
    @Annotation(key = "vmf:jackson:rename", value = "stored_items")
    KeyedMetadataItem[] getItems();
}

interface KeyedMetadataItem extends KeyedMetadataItemBase { }

@InterfaceOnly
interface KeyedMetadataItemBase {
    @Annotation(key = "vmf:jackson:rename", value = "stock_identifier")
    String getSku();
    String getDescription();
}

interface KeyedMetadataAssignment extends KeyedMetadataAssignmentBase<KeyedMetadataSpecificTarget> { }

@InterfaceOnly
interface KeyedMetadataIdentityEntry extends KeyedMetadataAssignmentBase<KeyedMetadataSpecificTarget> { }

interface KeyedMetadataIdentityItem extends KeyedMetadataIdentityEntry { }

@InterfaceOnly interface KeyedMetadataIdentitySelection<E extends KeyedMetadataIdentityEntry> {
    @Contains @Keyed(by = "target") E[] getSelected();
}

interface KeyedMetadataIdentityCatalog extends KeyedMetadataIdentitySelection<KeyedMetadataIdentityItem> { }

@InterfaceOnly
interface KeyedMetadataAssignmentBase<T extends KeyedMetadataTarget> {
    @Annotation(key = "vmf:jackson:rename", value = "linked_object")
    T getTarget();
}

interface KeyedMetadataTarget { }

interface KeyedMetadataSpecificTarget extends KeyedMetadataTarget { }

interface KeyedMetadataNumber extends KeyedMetadataNumberBase<Long> { }

@InterfaceOnly
interface KeyedMetadataNumberBase<N> { N getNumber(); }

public class KeyedMetadataTest {
    @Test
    public void inheritedKeyedRelationshipsUseResolvedLogicalNames() {
        var model = model();
        var inventory = model.findType("KeyedMetadataInventory").orElseThrow();
        var declared = property(model, "KeyedMetadataInventory", "items");
        var inherited = property(model, "KeyedMetadataCatalog", "items");
        var key = inherited.getEntryKey().orElseThrow();

        assertEquals("stored_items", inherited.getWireName());
        assertEquals("storage", inventory.getWireTypeName());
        assertEquals(inventory.getQualifiedName() + ".items", key.getRelationshipId());
        assertEquals(declared.getEntryKey().orElseThrow().getRelationshipId(), key.getRelationshipId());
        assertEquals("sku", key.getPropertyName());
        assertEquals(Kind.STRING, key.getValueType().getKind());
        assertFalse(key.usesIdentity());
        assertNull(inherited.getKeyType());

        assertTrue(inherited.isCollection());
        assertTrue(inherited.isOrdered());
        assertFalse(inherited.isUnique());
        assertFalse(inherited.allowsNullElements());
        var sku = property(model, "KeyedMetadataItemBase", "sku");
        assertEquals("stock_identifier", sku.getWireName());
        assertTrue(sku.isUsedAsEntryKey());
        assertTrue(property(model, "KeyedMetadataItem", "sku").isUsedAsEntryKey());
        assertFalse(property(model, "KeyedMetadataItemBase", "description").isUsedAsEntryKey());
        assertTrue(sku.getEntryKey().isEmpty());
    }

    @Test
    public void referenceAndScalarSpecializationsRetainTheirResolvedKeyTypes() {
        var model = model();
        var assignments = property(model, "KeyedMetadataCatalog", "assignments");
        var target = assignments.getEntryKey().orElseThrow();
        assertEquals("target", target.getPropertyName());
        assertEquals(Kind.MODEL, target.getValueType().getKind());
        assertEquals("KeyedMetadataSpecificTarget", target.getValueType().getModelType());
        assertTrue(target.usesIdentity());
        assertTrue(property(model, "KeyedMetadataAssignmentBase", "target").isUsedAsEntryKey());

        var numbers = property(model, "KeyedMetadataCatalog", "numbered");
        var number = numbers.getEntryKey().orElseThrow();
        assertTrue(numbers.isUnique());
        assertEquals("number", number.getPropertyName());
        assertEquals(Kind.INT64, number.getValueType().getKind());
        assertFalse(number.getValueType().isPrimitive());
        assertFalse(number.usesIdentity());
        assertTrue(property(model, "KeyedMetadataNumberBase", "number").isUsedAsEntryKey());
    }

    @Test
    public void concreteKeyedListsAndSetsAreAvailableToCsharpAndTypeScript() throws IOException {
        Class<?>[] declarations = {KeyedPortableCatalog.class, KeyedMetadataInventory.class, KeyedMetadataItem.class,
                KeyedMetadataItemBase.class, KeyedMetadataAssignment.class, KeyedMetadataAssignmentBase.class,
                KeyedMetadataTarget.class, KeyedMetadataSpecificTarget.class};
        var model = model(declarations);
        CSharpCapabilities.validate(model);
        PortableCapabilities.validate(model, "TypeScript");
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.TYPESCRIPT, output, declarations);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source, source.contains("key: { property: \"sku\", of: child => child.sku }"));
        assertTrue(source, source.contains("findItemsBySku(key: string): KeyedMetadataItem | null {"));
        assertTrue(source, source.contains(
                "findAssignmentsByTarget(key: KeyedMetadataSpecificTarget): KeyedMetadataAssignment | null {"));
        assertTrue(source, source.contains("findItemsBySku(key: string): ReadOnlyKeyedMetadataItem | null;"));
        // Readers attach entries keyed by a model reference after they resolved the references.
        assertTrue(source, source.contains("keyedByReference: true"));
    }

    @Test
    public void csharpRetainsGenericEntryKeyMetadataAcrossClosure() {
        var model = model(KeyedMetadataSelection.class, KeyedMetadataClosedSelection.class, KeyedMetadataItem.class,
                KeyedMetadataItemBase.class);
        CSharpCapabilities.validate(model);
        var declared = property(model, "KeyedMetadataSelection", "items");
        var closed = property(model, "KeyedMetadataClosedSelection", "items");
        var key = declared.getEntryKey().orElseThrow();

        assertEquals(Kind.PARAMETER, declared.getValueType().getKind());
        assertEquals("KeyedMetadataItemBase", declared.getValueType().getModelType());
        assertEquals("KeyedMetadataItem", closed.getValueType().getModelType());
        assertEquals("selected_items", closed.getWireName());
        assertEquals("sku", key.getPropertyName());
        assertEquals(Kind.STRING, key.getValueType().getKind());
        assertEquals(key.getRelationshipId(), closed.getEntryKey().orElseThrow().getRelationshipId());
        assertTrue(property(model, "KeyedMetadataItemBase", "sku").isUsedAsEntryKey());
        PortableCapabilities.validate(model, "TypeScript");
    }

    @Test
    public void csharpSupportsAConcreteModelBoundForOpenKeyedEntries() throws IOException {
        Class<?>[] declarations = {KeyedMetadataOpenCatalog.class, KeyedMetadataItem.class, KeyedMetadataItemBase.class};
        var model = model(declarations);
        CSharpCapabilities.validate(model);
        var property = property(model, "KeyedMetadataOpenCatalog", "items");
        assertEquals(Kind.PARAMETER, property.getValueType().getKind());
        assertEquals("KeyedMetadataItem", property.getValueType().getModelType());
        assertEquals(Kind.STRING, property.getEntryKey().orElseThrow().getValueType().getKind());
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, declarations);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public class KeyedMetadataItem "));
        assertTrue(source.contains("where E : KeyedMetadataItem"));
        PortableCapabilities.validate(model, "TypeScript");
    }

    @Test
    public void csharpSupportsAnInheritedSpecializedModelIdentityKey() {
        var model = model(KeyedMetadataIdentitySelection.class, KeyedMetadataIdentityCatalog.class,
                KeyedMetadataIdentityItem.class, KeyedMetadataIdentityEntry.class, KeyedMetadataAssignmentBase.class,
                KeyedMetadataTarget.class, KeyedMetadataSpecificTarget.class);
        CSharpCapabilities.validate(model);

        var open = property(model, "KeyedMetadataIdentitySelection", "selected");
        var closed = property(model, "KeyedMetadataIdentityCatalog", "selected");
        var openKey = open.getEntryKey().orElseThrow();
        assertEquals(Kind.PARAMETER, open.getValueType().getKind());
        assertEquals("KeyedMetadataIdentityItem", closed.getValueType().getModelType());
        assertEquals("target", openKey.getPropertyName());
        assertEquals(Kind.MODEL, openKey.getValueType().getKind());
        assertEquals("KeyedMetadataSpecificTarget", openKey.getValueType().getModelType());
        assertTrue(openKey.usesIdentity());
        assertEquals(openKey.getRelationshipId(), closed.getEntryKey().orElseThrow().getRelationshipId());
        assertTrue(property(model, "KeyedMetadataAssignmentBase", "target").isUsedAsEntryKey());
        PortableCapabilities.validate(model, "TypeScript");
    }

    private static ResolvedModel model() {
        Class<?>[] declarations = {KeyedMetadataCatalog.class, KeyedMetadataInventory.class, KeyedMetadataItem.class,
                KeyedMetadataItemBase.class, KeyedMetadataAssignment.class, KeyedMetadataAssignmentBase.class,
                KeyedMetadataTarget.class, KeyedMetadataSpecificTarget.class, KeyedMetadataNumber.class,
                KeyedMetadataNumberBase.class};
        return model(declarations);
    }

    private static ResolvedModel model(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }

    private static Property property(ResolvedModel model, String type, String name) {
        return model.findType(type).orElseThrow().getEffectiveProperties().stream()
                .filter(property -> property.getName().equals(name)).findFirst().orElseThrow();
    }
}
