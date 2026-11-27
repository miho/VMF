/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
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
package eu.mihosoft.vmftest.managedmap;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.TypeDescriptor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ManagedSettingsTest {
    @Test
    @SuppressWarnings("deprecation")
    public void clonePreservesManagedMapDefaultOverrides() {
        var source = ManagedSettings.newInstance();
        var original = source.vmf().reflect().propertyByName("ports").orElseThrow();
        original.setDefault(Map.of("port", 443));
        var copy = source.clone();
        var cloned = copy.vmf().reflect().propertyByName("ports").orElseThrow();
        assertEquals(Map.of("port", 443), cloned.getDefault());
        assertFalse(cloned.isSet());
        original.setDefault(Map.of("port", 8443));
        assertEquals(Map.of("port", 443), copy.getPorts());
    }

    @Test
    public void buildersAndCopiesOwnIndependentMaps() {
        Map<String, Double> input = new LinkedHashMap<>(Map.of("first", 1.0));
        var builder = ManagedSettings.newBuilder().withScores(input);
        input.clear();
        var model = builder.build();
        var sibling = builder.build();
        var scores = model.getScores();
        assertEquals(Map.of("first", 1.0), scores);
        var readOnly = model.asReadOnly().getScores();
        var clone = model.clone();
        var shallow = (ManagedSettings) model.vmf().content().shallowCopy();
        scores.put("second", 2.0);
        assertSame(scores, model.getScores());
        assertEquals(scores, readOnly);
        assertThrows(UnsupportedOperationException.class, () -> readOnly.put("second", 3.0));
        assertThrows(UnsupportedOperationException.class, () -> readOnly.entrySet().iterator().next().setValue(3.0));
        assertEquals(Map.of("first", 1.0), sibling.getScores());
        assertEquals(sibling.getScores(), clone.getScores());
        assertEquals(sibling.getScores(), shallow.getScores());

        var merged = ManagedSettings.newBuilder().withScores(Map.of("third", 3.0)).applyFrom((Scores) model).build();
        assertEquals(Map.of("first", 1.0, "second", 2.0, "third", 3.0), merged.getScores());
        builder.appendCollections(false).applyTo(model);
        assertEquals(Map.of("first", 1.0), scores);
        model.getPorts().clear();
        assertEquals(Map.of("port", 80), sibling.getPorts());

        var frozen = FrozenSettings.newBuilder().withFlags(Map.of("enabled", true)).build();
        assertEquals(Map.of("enabled", true), frozen.getFlags());
        assertThrows(UnsupportedOperationException.class, () -> frozen.getFlags().clear());
    }

    @Test
    public void builderRetainsMapAndPublishesDisplacedKeysBeforeChangedPuts() {
        var model = ManagedSettings.newInstance();
        var scores = model.getScores();
        scores.put("drop-a", 1.0);
        scores.put("drop-b", 2.0);
        scores.put("keep", 3.0);
        Map<String, Double> desired = new LinkedHashMap<>();
        desired.put("keep", 3.0);
        desired.put("nil", null);
        desired.put("tail", 4.0);
        var events = new ArrayList<String>();
        model.vmf().changes().addListener(change -> {
            if (change.propertyName().equals("scores")) {
                var map = change.mapChange().orElseThrow();
                events.add(map.removed().entries().keySet() + " -> " + map.added().entries().keySet());
            }
        });

        var builder = ManagedSettings.newBuilder().withScores(desired);
        builder.appendCollections(false).applyTo(model);
        assertSame(scores, model.getScores());
        assertEquals(List.of("keep", "nil", "tail"), new ArrayList<>(scores.keySet()));
        assertTrue(scores.containsKey("nil"));
        assertNull(scores.get("nil"));
        assertEquals(List.of("[drop-a] -> []", "[drop-b] -> []", "[] -> [nil, tail]"), events);

        events.clear();
        builder.appendCollections(true).applyTo(model);
        assertTrue(events.isEmpty());
    }

    @Test
    public void replacementAndClonePreserveIterationOrderAcrossDefaultKeys() {
        var wanted = new LinkedHashMap<String, Integer>();
        wanted.put("alternate", 8080);
        wanted.put("port", 80);
        var model = ManagedSettings.newBuilder().withPorts(wanted).build();
        var order = List.of("alternate", "port");
        assertEquals(order, new ArrayList<>(model.getPorts().keySet()));
        assertEquals(order, new ArrayList<>(model.clone().getPorts().keySet()));

        var property = model.vmf().reflect().propertyByName("ports").orElseThrow();
        var reverse = new LinkedHashMap<String, Integer>();
        reverse.put("port", 80);
        reverse.put("alternate", 8080);
        property.set(reverse);
        assertEquals(List.of("port", "alternate"), new ArrayList<>(model.getPorts().keySet()));
        property.set(wanted);
        assertEquals(order, new ArrayList<>(model.getPorts().keySet()));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void entryWritesUseTheObservedViewAndInvalidBatchesHaveNoEffect() {
        var model = ManagedSettings.newInstance();
        var readOnly = model.asReadOnly();
        var reflected = readOnly.vmf().reflect().propertyByName("scores").orElseThrow();
        assertSame(readOnly.getScores(), reflected.get());
        var mutableChanges = new ArrayList<Change>();
        var viewChanges = new ArrayList<Change>();
        model.vmf().changes().addListener(mutableChanges::add);
        readOnly.vmf().changes().addListener(viewChanges::add);

        model.getScores().put("sensor", null);
        model.getScores().entrySet().iterator().next().setValue(9.0);
        assertEquals(2, mutableChanges.size());
        assertEquals(2, viewChanges.size());
        assertSame(model.getScores(), mutableChanges.get(1).mapChange().orElseThrow().source());
        var observed = viewChanges.get(1);
        assertEquals(Change.ChangeType.MAP, observed.getType());
        assertSame(readOnly, observed.object());
        assertFalse(observed.isUndoable());
        observed.undo();
        assertEquals(Double.valueOf(9.0), model.getScores().get("sensor"));
        assertSame(reflected.get(), observed.mapChange().orElseThrow().source());
        assertTrue(observed.mapChange().orElseThrow().removed().entries().containsKey("sensor"));
        assertNull(observed.mapChange().orElseThrow().removed().entries().get("sensor"));

        Map raw = model.getScores();
        for (Object invalidKey : new Object[]{null, 1}) {
            Map<Object, Object> invalidKeys = new LinkedHashMap<>();
            invalidKeys.put("valid", 12.0);
            invalidKeys.put(invalidKey, 10.0);
            Class<? extends RuntimeException> expected = invalidKey == null
                    ? NullPointerException.class : ClassCastException.class;
            assertThrows(expected, () -> raw.putAll(invalidKeys));
        }
        Map<String, Object> invalidValues = new LinkedHashMap<>();
        invalidValues.put("valid", 12.0);
        invalidValues.put("wrong type", 12);
        assertThrows(ClassCastException.class, () -> raw.putAll(invalidValues));
        assertEquals(Map.of("sensor", 9.0), model.getScores());
        assertEquals(2, mutableChanges.size());
        assertEquals(2, viewChanges.size());
    }

    @Test
    public void reflectionDescribesAndResetsOwnedEntries() {
        var model = ManagedSettings.newInstance();
        var property = model.vmf().reflect().propertyByName("scores").orElseThrow();
        var expected = TypeDescriptor.of(VMap.class.getName(), TypeDescriptor.of("java.lang.String"),
                TypeDescriptor.of("java.lang.Double"));
        assertEquals(expected, property.getType().getDescriptor());
        assertEquals(expected, model.asReadOnly().vmf().reflect().propertyByName("scores").orElseThrow()
                .getType().getDescriptor());
        assertFalse(property.isSet());
        var scores = model.getScores();
        scores.put("absent reading", null);
        assertTrue(property.isSet());
        property.set(scores);
        assertTrue(scores.containsKey("absent reading"));
        property.set(Map.of("reading", 42.0));
        assertSame(scores, property.get());
        assertEquals(Map.of("reading", 42.0), scores);

        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("valid", 12.0);
        invalid.put("invalid", 12);
        assertThrows(ClassCastException.class, () -> property.set(invalid));
        assertEquals(Map.of("reading", 42.0), scores);
        assertThrows(NullPointerException.class, () -> scores.put(null, 1.0));
        property.unset();
        assertFalse(property.isSet());
        assertTrue(scores.isEmpty());
        var portProp = model.vmf().reflect().propertyByName("ports").orElseThrow();
        assertFalse(portProp.isSet());
        model.getPorts().remove("port");
        portProp.unset();
        assertEquals(Map.of("port", 80), model.getPorts());
        Map<String, Integer> newDefault = new LinkedHashMap<>(Map.of("port", 443));
        portProp.setDefault(newDefault);
        newDefault.clear();
        assertEquals(Map.of("port", 443), model.getPorts());
        assertFalse(model.asReadOnly().vmf().reflect().propertyByName("ports").orElseThrow().isSet());
    }
}
