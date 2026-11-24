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
package eu.mihosoft.vmftest.genericseries;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.VObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.StringWriter;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class GenericSeriesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void concreteTypesRetainIndependentScalarAndListBindings() {
        TemperatureSeries temperature = TemperatureSeries.newBuilder().withLatest(12.5).withSamples(1.5, 12.5).build();
        SampleSeries<Double> writable = temperature;
        writable.setLatest(13.0);
        ReadOnlySampleSeries<Double> readOnly = temperature.asReadOnly();
        assertEquals(Double.valueOf(13), readOnly.getLatest());
        assertEquals(List.of(1.5, 12.5), readOnly.getSamples());
        assertThrows(UnsupportedOperationException.class, () -> readOnly.getSamples().add(14.0));
        assertEquals(Double.class.getName(), TemperatureSeries.type().reflect().propertyByName("samples").orElseThrow()
                .getType().getElementTypeName().orElseThrow());

        SampleSeries<String> status = StatusSeries.newBuilder().withLatest("ready").withSamples("ok").build();
        assertEquals("ready", status.clone().getLatest());
        assertEquals(String.class.getName(), StatusSeries.type().reflect().propertyByName("latest").orElseThrow()
                .getType().getName());
        var numeric = TemperatureSeries.type().superTypes().get(0);
        assertEquals(NumericSeries.class.getName(), numeric.getDescriptor().getName());
        assertEquals(Double.class.getName(), numeric.getDescriptor().getArguments().get(0).getName());
        var series = numeric.superTypes().get(0).getDescriptor();
        assertEquals(SampleSeries.class.getName(), series.getName());
        assertEquals(Double.class.getName(), series.getArguments().get(0).getName());

        var variable = NumericSeries.type().superTypes().get(0).getDescriptor().getArguments().get(0);
        assertEquals("N", variable.getName());
        assertEquals(NumericSeries.class.getName(), variable.getDeclaringType().orElseThrow());
        assertEquals(readOnly.getLatest(), TemperatureSeries.newBuilder().applyFrom(writable).build().getLatest());
    }

    @Test
    public void multipleParametersRetainTheirPositionsAndDeclarationScopes() {
        NamedPair pair = NamedPair.newBuilder().withLeft("key").withRight(42).build();
        Pair<String, Integer> base = pair;
        assertEquals("key", base.getLeft());
        assertEquals(Integer.valueOf(42), base.getRight());
        var applied = NamedPair.type().superTypes().get(0).superTypes().get(0).getDescriptor();
        assertEquals(String.class.getName(), applied.getArguments().get(0).getName());
        assertEquals(Integer.class.getName(), applied.getArguments().get(1).getName());
    }

    @Test
    public void managedMapsResolveTheirValueTypeAndOwnTheirEntries() {
        Map<String, Double> supplied = new HashMap<>(Map.of("x", 3.0));
        Lookup<Double> numbers = NumericLookup.newBuilder().withValues(supplied).build();
        supplied.clear();
        var duplicate = NumericLookup.newBuilder().applyFrom(numbers).build();
        numbers.getValues().clear();
        assertEquals(Map.of("x", 3.0), duplicate.getValues());
        assertThrows(UnsupportedOperationException.class, () -> duplicate.asReadOnly().getValues().clear());
        var strings = StatusLookup.newBuilder().withValues(Map.of("x", "3.0")).build();
        assertEquals(String.class.getName(), strings.vmf().reflect().propertyByName("values").orElseThrow()
                .getType().getDescriptor().getArguments().get(1).getName());
    }

    @Test
    public void nestedExternalTypeHasLoadableNamesInReflection() {
        StateSeries states = StateSeries.newBuilder().withLatest(Thread.State.NEW).withSamples(Thread.State.RUNNABLE).build();
        assertEquals(Thread.State.NEW, states.getLatest());
        assertEquals(Thread.State.class.getName(), StateSeries.type().superTypes().get(0).getDescriptor()
                .getArguments().get(0).getName());
        var reflect = states.vmf().reflect();
        assertEquals(Thread.State.class.getName(), reflect.propertyByName("latest").orElseThrow().getType().getName());
        assertEquals(Thread.State.class.getName(), reflect.propertyByName("samples").orElseThrow()
                .getType().getElementTypeName().orElseThrow());
    }

    @Test
    public void primitiveArrayPropertiesUseTheirCorrectBoxedTypes() {
        PrimitiveArrays values = PrimitiveArrays.newBuilder().withInts(1).withLongs(Long.MAX_VALUE).build();
        assertEquals(List.of(1), values.asReadOnly().getInts());
        assertEquals(List.of(Long.MAX_VALUE), values.asReadOnly().getLongs());
    }
    @Test
    public void javacRejectsWritingOrCopyingFromAnIncompatibleBinding() throws Exception {
        String prefix = "TemperatureSeries t = TemperatureSeries.newInstance(); ";
        assertTrue(compile(prefix + "t.setLatest(1.0); t.getSamples().add(1.0);"));
        assertFalse(compile(prefix + "t.setLatest(\"invalid\");"));
        assertFalse(compile(prefix + "t.getSamples().add(\"invalid\");"));
        assertFalse(compile("NumericLookup.newInstance().getValues().put(\"x\", \"wrong\");"));
        assertFalse(compile("TemperatureSeries.newBuilder().applyFrom(StatusSeries.newInstance());"));
    }

    @Test
    public void mutableRelationshipsKeepTheirTypesThroughBothBranchesOfADiamond() throws Exception {
        var station = Station.newBuilder().withName("Inspection").build();
        var selection = StationSelection.newBuilder().withOwned(station).withQueue(station)
                .withFavorites(station).withCurrent(station).build();
        Selection<Station> writable = selection;
        assertSame(station, writable.findOwnedByName("Inspection").orElseThrow());
        ReadOnlySelection<ReadOnlyStation> view = selection.asReadOnly();
        assertSame(station.asReadOnly(), view.getCurrent());
        assertSame(view.getCurrent(), view.findOwnedByName("Inspection").orElseThrow());
        assertSame(view.getCurrent(), view.getQueue().get(0));
        assertSame(view.getCurrent(), view.getFavorites().iterator().next());
        assertSame(view.getCurrent(), writable.asReadOnly().getCurrent());
        assertSame(view.getCurrent(), view.vmf().reflect().propertyByName("current").orElseThrow().get());
        var edits = new java.util.ArrayList<eu.mihosoft.vmf.runtime.core.Change>();
        var subscription = view.vmf().changes().addListener(edits::add);
        writable.setCurrent(null);
        assertSame(view, edits.get(0).object());
        assertSame(station.asReadOnly(), edits.get(0).propertyChange().orElseThrow().oldValue());
        writable.setCurrent(station);
        subscription.unsubscribe();
        assertThrows(UnsupportedOperationException.class, () -> view.getFavorites().clear());
        assertThrows(UnsupportedOperationException.class, () -> view.getOwned().clear());
        var clone = selection.clone();
        assertNotSame(station, clone.getCurrent());
        assertSame(clone.getCurrent(), clone.getOwned().get(0));
        assertTrue(clone.getFavorites().contains(clone.getCurrent()));
        var other = StationSelection.newInstance();
        other.getOwned().add(station);
        assertTrue(selection.getOwned().isEmpty());
        assertSame(station, other.findOwnedByName("Inspection").orElseThrow());
        for (var branch : StationSelection.type().superTypes()) {
            var inherited = branch.superTypes().get(0).getDescriptor();
            assertEquals(Selection.class.getName(), inherited.getName());
            assertEquals(Station.class.getName(), inherited.getArguments().get(0).getName());
        }
        assertFalse(compile("StationSelection.newInstance().asReadOnly().getFavorites().iterator().next().setEnabled(true);"));
        assertFalse(compile("Selection<Station> s = StationSelection.newInstance(); Station t = s.asReadOnly().getCurrent();"));
        assertFalse(compile("StationSelection.newInstance().getFavorites().add(Member.newInstance());"));
    }

    @Test
    public void genericOppositesAcceptChangesFromBothEnds() {
        var member = Member.newInstance();
        var team = Team.newInstance();
        team.getMembers().add(member);
        assertSame(team, member.getTeam());
        member.setTeam(null);
        assertTrue(team.getMembers().isEmpty());
        member.setTeam(team);
        assertSame(member.asReadOnly(), team.asReadOnly().getMembers().iterator().next());
    }

    @Test
    public void dependentBoundsAndImmutableArgumentsUseTheirDeclaredReadOnlyTypes() {
        var station = Station.newInstance();
        var pair = StationPair.newBuilder().withBase(station).withSpecific(station).build();
        ReadOnlyRelated<ReadOnlyNamedTarget, ReadOnlyStation> related = pair.asReadOnly();
        assertSame(related.getBase(), related.getSpecific());
        ReadOnlyFiltered<ReadOnlyStation> filtered = FilteredStations.newBuilder().withTarget(station).build().asReadOnly();
        assertSame(station.asReadOnly(), filtered.getTarget());
        var label = ConstantLabel.newBuilder().withText("Ready").build();
        ReadOnlyLabelsOf<ConstantLabel> constants = Constants.newBuilder().withLabels(label).build().asReadOnly();
        assertSame(label, constants.getLabels().iterator().next());
        ConstantsOf<ConstantLabel> writable = SpecificConstants.newBuilder().withLabels(label).build();
        ReadOnlyConstantsOf<ConstantLabel> stable = writable.asReadOnly();
        assertSame(label, stable.getLabels().iterator().next());
    }

    @Test
    public void appliedGenericTypesRespectTheDefiningLoaderOfTheirArguments() throws Exception {
        try (var first = modelLoader(); var second = modelLoader()) {
            var a = (eu.mihosoft.vmf.runtime.core.Type) first.loadClass(LocalSelection.class.getName()).getMethod("type").invoke(null);
            var b = (eu.mihosoft.vmf.runtime.core.Type) second.loadClass(LocalSelection.class.getName()).getMethod("type").invoke(null);
            var localBase = a.superTypes().get(0).superTypes().get(0);
            var otherBase = b.superTypes().get(0).superTypes().get(0);
            assertEquals(localBase.getDescriptor(), otherBase.getDescriptor());
            assertNotEquals(localBase, otherBase);
            assertTrue(a.isModelSubtypeOf(localBase));
            assertFalse(a.isModelSubtypeOf(otherBase));
        }
    }

    private java.net.URLClassLoader modelLoader() {
        // Share the generic declaration. Load only the application and its argument twice.
        var location = Station.class.getProtectionDomain().getCodeSource().getLocation();
        return new java.net.URLClassLoader(new java.net.URL[]{location}, Station.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(Station.class.getName()) || name.equals(LocalSelection.class.getName())
                        || name.equals(LocalSelection.class.getName().replace("LocalSelection", "__VMF__LocalSelection_IFACE_ONLY_HELPER"))) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    @Test
    public void genericSingleValuedOppositesMaintainContainmentAndReferences() {
        var first = Owner.newInstance();
        var second = Owner.newInstance();
        var target = Owned.newInstance();
        first.setPrimary(target);
        target.setOwner(second);
        assertNull(first.getPrimary());
        assertSame(target, second.getPrimary());
        target.setSelector(first);
        assertSame(target, first.getHighlighted());
        second.setHighlighted(target);
        assertNull(first.getHighlighted());
        assertSame(second, target.getSelector());
    }

    private boolean compile(String body) throws Exception {
        String classpath = Stream.of(TemperatureSeries.class, VObject.class, VList.class)
                .map(c -> c.getProtectionDomain().getCodeSource().getLocation())
                .map(url -> new File(URI.create(url.toExternalForm())).getPath())
                .collect(Collectors.joining(File.pathSeparator));
        String source = "import eu.mihosoft.vmftest.genericseries.*; class Probe { void use() {" + body + "} }";
        SimpleJavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///Probe.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source; }
        };
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            return compiler.getTask(new StringWriter(), files, null, List.of("-proc:none", "-d",
                    temporary.getRoot().getPath(), "-classpath", classpath), null, List.of(unit)).call();
        }
    }
}
