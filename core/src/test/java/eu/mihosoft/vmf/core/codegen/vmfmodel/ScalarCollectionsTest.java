/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Refers;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.*;

interface ScalarCollectionBase {
    @Annotation(key = "vmf:jackson:rename", value = "historic_samples")
    @Multiplicity(lower = 1)
    List<Integer> getSamples();

    @Multiplicity(lower = 1, upper = 2)
    List<Short> getBins();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    long[] getActions();

    Set<Float> getLevels();

    @Multiplicity(upper = 3)
    VMap<String, Double> getSettings();
}

interface ScalarCollectionChild extends ScalarCollectionBase {
    VMap<Long, Character> getStatusById();
}

@InterfaceOnly
interface GenericMapOwner<K, V> {
    VMap<K, V> getEntries();
}

interface SpecializedMapOwner extends GenericMapOwner<Integer, Long> {}

@Immutable
interface ImmutableCollections {
    List<Integer> getSamples();
    VMap<String, Integer> getSettings();
}

interface CollectionDefaults {
    @DefaultValue("java.util.List.of(\"initialized\")")
    @Multiplicity(upper = 3)
    List<String> getNames();
}

interface UnboundExpressionCollectionDefault {
    @DefaultValue("java.util.List.of(String.valueOf(1))")
    @Multiplicity(upper = 3)
    List<String> getNames();
}

interface BoundManagedCollectionDefault {
    @DefaultValue("java.util.List.of(1)") @Multiplicity(upper = 3)
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getNumbers();
}

interface BoundRawCollectionDefault {
    @DefaultValue("eu.mihosoft.vcollections.VList.newInstance(java.util.List.of(1))")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getNumbers();
}

interface BoundNullCollectionDefault {
    @DefaultValue("null") @Multiplicity(upper = 3)
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getNumbers();
}

interface BoundReferenceDefaultEntry { }

interface BoundReferenceCollectionDefault {
    @DefaultValue("java.util.List.of()") @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Set<BoundReferenceDefaultEntry> getEntries();
}

interface BoundReferenceSequenceDefault {
    @DefaultValue("java.util.List.of()") @Annotation(key = "vmf:csharp:bind-default", value = "true")
    BoundReferenceDefaultEntry[] getEntries();
}

interface BoundReciprocalDefault {
    @Refers(opposite = "owners")
    @DefaultValue("java.util.List.of()") @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Set<BoundReciprocalDefaultEntry> getEntries();
}

interface BoundReciprocalDefaultEntry {
    @Refers(opposite = "entries")
    Set<BoundReciprocalDefault> getOwners();
}

interface MixedExecutableCollectionDefaults {
    @DefaultValue("java.util.List.of()") @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Set<BoundReferenceDefaultEntry> getEntries();

    @DefaultValue("Integer.valueOf(1)") @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Integer getCapacity();
}

public class ScalarCollectionsTest {
    @Test
    public void csharpSupportsInheritedTypedCollectionsAndMaps() {
        ResolvedModel model = model(ScalarCollectionBase.class, ScalarCollectionChild.class);
        CSharpCapabilities.validate(model);

        var samples = property(model, "ScalarCollectionChild", "samples");
        assertScalarCollection(samples, ValueType.Kind.INT32, true, false, true);
        assertEquals("ScalarCollectionBase", samples.getDeclaringType());
        assertEquals("historic_samples", samples.getWireName());
        assertEquals(1, samples.getLowerBound());
        assertEquals(-1, samples.getUpperBound());

        var bins = property(model, "ScalarCollectionChild", "bins");
        assertScalarCollection(bins, ValueType.Kind.INT16, true, false, false);
        assertEquals(1, bins.getLowerBound());
        assertEquals(2, bins.getUpperBound());

        var actions = property(model, "ScalarCollectionChild", "actions");
        assertScalarCollection(actions, ValueType.Kind.INT64, true, true, false);
        assertFalse(actions.getValueType().isPrimitive());

        var levels = property(model, "ScalarCollectionChild", "levels");
        assertScalarCollection(levels, ValueType.Kind.FLOAT32, false, true, false);

        var settings = property(model, "ScalarCollectionChild", "settings");
        assertMap(settings, ValueType.Kind.STRING, ValueType.Kind.FLOAT64);
        assertEquals("ScalarCollectionBase", settings.getDeclaringType());
        assertEquals(3, settings.getUpperBound());

        var statusById = property(model, "ScalarCollectionChild", "statusById");
        assertMap(statusById, ValueType.Kind.INT64, ValueType.Kind.CHAR);
        assertEquals(-1, statusById.getUpperBound());
    }

    @Test
    public void typeScriptRetainsItsScalarCollectionAndMapGate() {
        ResolvedModel model = model(ScalarCollectionBase.class, ScalarCollectionChild.class);
        var exception = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(model, "TypeScript"));
        String message = exception.getMessage();
        assertFalse(message, message.contains(".samples "));
        assertTrue(message, message.contains(".actions needs an ordered scalar list"));
        assertTrue(message, message.contains(".levels needs an ordered scalar list"));
        assertTrue(message, message.contains(".settings uses map properties"));
    }

    @Test
    public void csharpSupportsLiteralDefaultsAndRequiresBindingsForExecutableCollectionExpressions() {
        ResolvedModel generic = model(GenericMapOwner.class, SpecializedMapOwner.class);
        var open = property(generic, "GenericMapOwner", "entries");
        assertEquals(ValueType.Kind.PARAMETER, open.getKeyType().getKind());
        assertEquals("K", open.getKeyType().getParameterName());
        assertEquals("V", open.getValueType().getParameterName());
        assertMap(property(generic, "SpecializedMapOwner", "entries"), ValueType.Kind.INT32, ValueType.Kind.INT64);
        CSharpCapabilities.validate(generic);

        CSharpCapabilities.validate(model(ImmutableCollections.class));

        var literals = model(CollectionDefaults.class);
        CSharpCapabilities.validate(literals);
        var names = property(literals, "CollectionDefaults", "names").getCollectionDefault().orElseThrow();
        assertEquals("initialized", names.getValues().get(0).getValue());
        var defaults = assertThrows(IllegalArgumentException.class,
                () -> CSharpCapabilities.validate(model(UnboundExpressionCollectionDefault.class)));
        assertTrue(defaults.getMessage().contains(".names uses declared default expressions"));
    }

    @Test
    public void csharpBindsManagedScalarAndUnopposedIdentityReferenceSets() {
        var managed = model(BoundManagedCollectionDefault.class);
        var references = model(BoundReferenceDefaultEntry.class, BoundReferenceCollectionDefault.class);
        CSharpCapabilities.validate(managed);
        CSharpCapabilities.validate(references);
        assertEquals(List.of("true"), property(references, "BoundReferenceCollectionDefault", "entries")
                .getAnnotationValues("vmf:property:reference-default"));
        var baseline = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(managed, "TypeScript"));
        assertTrue(baseline.getMessage(), baseline.getMessage().contains(".numbers uses declared default expressions"));
        var baselineReferences = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(references, "TypeScript"));
        assertTrue(baselineReferences.getMessage(), baselineReferences.getMessage().contains(".entries uses declared default expressions"));

        for (var unsupported : List.of(model(BoundRawCollectionDefault.class), model(BoundNullCollectionDefault.class),
                model(BoundReferenceDefaultEntry.class, BoundReferenceSequenceDefault.class),
                model(BoundReciprocalDefault.class, BoundReciprocalDefaultEntry.class))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(unsupported));
            assertTrue(failure.getMessage(), failure.getMessage().contains("unopposed identity reference-set default"));
        }

        var mixed = model(BoundReferenceDefaultEntry.class, MixedExecutableCollectionDefaults.class);
        CSharpCapabilities.validate(mixed);
        assertTrue(CSharpCapabilities.bindsReferenceSetDefault(property(mixed, "MixedExecutableCollectionDefaults", "entries")));
        assertTrue(CSharpCapabilities.bindsExecutableDefault(
                property(mixed, "MixedExecutableCollectionDefaults", "capacity")));
    }

    private static ResolvedModel model(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }

    private static Property property(ResolvedModel model, String type, String name) {
        return model.findType(type).orElseThrow().getEffectiveProperties().stream()
                .filter(property -> property.getName().equals(name)).findFirst().orElseThrow();
    }

    private static void assertScalarCollection(Property property, ValueType.Kind element, boolean ordered,
                                               boolean unique, boolean allowsNull) {
        assertEquals(Property.Kind.SCALAR, property.getKind());
        assertTrue(property.isCollection());
        assertEquals(element, property.getValueType().getKind());
        assertNull(property.getKeyType());
        assertEquals(ordered, property.isOrdered());
        assertEquals(unique, property.isUnique());
        assertEquals(allowsNull, property.allowsNullElements());
    }

    private static void assertMap(Property property, ValueType.Kind key, ValueType.Kind value) {
        assertEquals(Property.Kind.MAP, property.getKind());
        assertTrue(property.isCollection());
        assertEquals(key, property.getKeyType().getKind());
        assertEquals(value, property.getValueType().getKind());
        assertTrue(property.allowsNullElements());
    }
}
