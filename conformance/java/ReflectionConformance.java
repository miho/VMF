package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;
import eu.mihosoft.vmf.conformance.values.KeyedInventory;
import eu.mihosoft.vmf.conformance.values.ValueLabel;
import eu.mihosoft.vmf.conformance.values.ValueSelection;
import eu.mihosoft.vmf.conformance.numeric.DoubleSeries;
import eu.mihosoft.vmf.conformance.numeric.ForwardedSeries;
import eu.mihosoft.vmf.conformance.numeric.NumericSeries;
import eu.mihosoft.vmf.runtime.core.Annotation;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.TraversalListener;
import eu.mihosoft.vmf.runtime.core.VIterator;
import eu.mihosoft.vmf.runtime.core.VIterator.IterationStrategy;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ReflectionConformance {
    private ReflectionConformance() {}

    public static void main(String[] args) throws java.io.IOException {
        verifyProperties();
        verifyAnnotations();
        verifyStaticTypes();
        verifyTraversal();
        verifyDefaultGraphCursor();
        verifyReferenceCursor();
        verifyReferenceCursorAfterIndexedChanges();
        verifyReferenceCursorDelivery();
        verifyOrderedReferenceSetCursor();
        UnorderedCursorConformance.verify();
        System.out.println("Java verified reflection order, projected property access, and graph traversal.");
    }

    private static void verifyProperties() {
        var defaults = DefaultSettings.newInstance();
        var reflect = defaults.vmf().reflect();
        check(reflect.type().getName().equals("eu.mihosoft.vmf.conformance.values.DefaultSettings"), "effective concrete type");
        check(reflect.properties().stream().map(Property::getName).collect(Collectors.toList()).equals(List.of(
                "baseline", "entries", "flag", "limit", "mode", "name", "note", "ratio", "sequence", "stages", "unsetValue")),
                "effective reflection order includes inherited fields");
        check(reflect.propertyByName("Name").isEmpty() && reflect.propertyByName("display_name").isEmpty(),
                "logical property names use exact case independently of the XML/JSON wire name");
        var name = reflect.propertyByName("name").orElseThrow();
        var readOnlyName = defaults.asReadOnly().vmf().reflect().propertyByName("name").orElseThrow();
        check("idle\nready".equals(name.get()) && name.getType().getName().equals("java.lang.String"),
                "inherited property has a typed value and declared default");
        name.set("updated");
        check("updated".equals(defaults.getName()) && "updated".equals(readOnlyName.get()), "reflection is a live view");
        try {
            readOnlyName.set("invalid");
            throw new AssertionError("Read-only reflection accepted a write");
        } catch (RuntimeException expected) {
            check("updated".equals(defaults.getName()), "read-only reflection preserves the mutable object");
        }

        var collection = CollectionProfile.newInstance().vmf().reflect().propertyByName("limits").orElseThrow();
        check(collection.getType().isMapType() && collection.getMultiplicity().isUnique()
                && collection.getMultiplicity().getLower() == 1 && collection.getMultiplicity().getUpper() == 3,
                "inherited map exposes cardinality and shape");
        var keyed = KeyedInventory.newInstance().vmf().reflect().propertyByName("products").orElseThrow();
        check(keyed.getKeyPropertyName().orElseThrow().equals("sku") && keyed.getMultiplicity().isOrdered()
                && !keyed.getMultiplicity().isUnique(), "unmarked keyed containment has ordered list multiplicity");
    }

    private static void verifyTraversal() {
        var root = node("root");
        var branch = node("branch");
        var shared = node("shared");
        var inset = node("inset");
        var outside = node("outside");
        root.getChildren().add(branch);
        branch.getChildren().add(shared);
        root.setInset(inset);
        root.setAnchor(outside);
        inset.setAnchor(branch);
        root.getTrail().add(inset);
        root.getTrail().add(null);
        root.getTrail().add(shared);

        var view = root.asReadOnly();
        for (VObject entry : List.of(root, view)) {
            check(names(entry, IterationStrategy.CONTAINMENT_TREE).equals(List.of("root", "branch", "shared", "inset")),
                    "Owning traversal skips non-owning references and retains collection/property order");
            check(names(entry, IterationStrategy.UNIQUE_NODE).equals(List.of("root", "outside", "branch", "shared", "inset")),
                    "Unique nodes visit references first when they occur earlier in reflection order");
            check(names(entry, IterationStrategy.UNIQUE_PROPERTY)
                    .equals(List.of("root", "outside", "branch", "shared", "inset", "branch", "inset", "shared")),
                    "Unique property includes multiple references but expands each mutable object only once");
        }
        check(view.vmf().content().stream(IterationStrategy.UNIQUE_NODE).noneMatch(value -> value instanceof ArchiveNode),
                "traversal from a read-only view returns read-only model objects");
        check(branch.vmf().reflect().propertyByName("parent").orElseThrow().get() == root,
                "an inverse container appears in reflection, despite being absent from traversal");
        outside.setAnchor(root);
        for (VObject entry : List.of(root, view)) {
            check(names(entry, IterationStrategy.UNIQUE_NODE).equals(List.of("root", "outside", "branch", "shared", "inset")),
                    "reference cycle terminates without revisiting the mutable or projected root");
            check(visit(entry, model -> {
                check(entry == root || !(model instanceof ArchiveNode), "Read-only callbacks receive projections");
                return (String) model.vmf().reflect().propertyByName("name").orElseThrow().get();
            }).equals(List.of("+root", "+outside", "-outside", "+branch", "+shared", "-shared",
                    "-branch", "+inset", "-inset", "-root")), "Node callbacks surround expansion once");
        }

        var selection = ValueSelection.newInstance();
        var value = ValueLabel.newBuilder().withText("same").build();
        selection.setCurrent(value);
        selection.getSelected().add(value);
        check(selection.vmf().content().stream(IterationStrategy.UNIQUE_NODE).count() == 3,
                "by-value immutable occurrences are visible even under unique mutable identity traversal");
        check(visit(selection, model -> model == value ? "label" : "root")
                        .equals(List.of("+root", "+label", "-label", "+label", "-label", "-root")),
                "Each immutable occurrence gets a complete visit");
    }

    private static void verifyDefaultGraphCursor() {
        var root = ArchiveNode.newBuilder().withName("root").build();
        var alias = ArchiveNode.newBuilder().withName("alias").build();
        root.setAnchor(alias);
        root.getTrail().add(alias);
        var content = root.vmf().content();
        var defaultCursor = content.iterator();
        check(defaultCursor.next() == root && defaultCursor.next() == alias && !defaultCursor.hasNext(),
                "The generated default cursor visits each mutable identity once");
        check(content.stream().count() == 2 && names(root, IterationStrategy.UNIQUE_NODE).equals(List.of("root", "alias")),
                "The generated default stream and explicit unique-node policy match");
        var propertyCursor = content.iterator(IterationStrategy.UNIQUE_PROPERTY);
        check(propertyCursor.next() == root && propertyCursor.next() == alias
                        && propertyCursor.next() == alias && !propertyCursor.hasNext(),
                "Explicit unique-property traversal selects both alias occurrences");
    }

    private static void verifyReferenceCursor() {
        for (var strategy : List.of(IterationStrategy.UNIQUE_PROPERTY, IterationStrategy.UNIQUE_NODE)) {
            var root = ArchiveNode.newInstance();
            var original = ArchiveNode.newInstance();
            var descendant = ArchiveNode.newInstance();
            var tail = ArchiveNode.newInstance();
            var replacement = ArchiveNode.newInstance();
            var inserted = ArchiveNode.newInstance();

            original.setAnchor(descendant);
            root.setAnchor(original);
            root.getTrail().add(original);
            root.getTrail().add(null);
            root.getTrail().add(tail);
            var cursor = root.vmf().content().iterator(strategy);
            check(cursor.next() == root && cursor.next() == original, "Select the singular non-owning edge");
            check(!cursor.isAddSupported(), "A singular edge does not permit insertion");
            cursor.set(replacement);
            check(root.getAnchor() == replacement && root.getTrail().get(0) == original,
                    "Replacing the singular edge preserves the aliased list slot");
            check(cursor.next() == descendant, "The original reference's descendants stay scheduled");

            if (strategy == IterationStrategy.UNIQUE_PROPERTY) {
                check(cursor.next() == original && cursor.isAddSupported(), "Select the distinct aliased list slot");
                cursor.set(tail);
                cursor.add(inserted);
                cursor.add(replacement);
                check(root.getTrail().get(0) == tail && root.getTrail().get(1) == inserted
                        && root.getTrail().get(2) == replacement, "Reference-list edits keep call order and alias identity");
                try {
                    cursor.set(original);
                    throw new AssertionError("A list insertion invalidates the previous replacement position");
                } catch (IllegalStateException expected) {
                    // The ordinary VList cursor advances past the inserted slot.
                }
            }
            check(cursor.next() == tail, "Skip null and newly inserted slots");
            cursor.set(null);
            check(root.getTrail().get(root.getTrail().size() - 1) == null && !cursor.hasNext(),
                    "A nullable tail replacement keeps its position and exhausts the cursor");
        }
    }

    private static void verifyReferenceCursorAfterIndexedChanges() {
        for (var strategy : List.of(IterationStrategy.UNIQUE_PROPERTY, IterationStrategy.UNIQUE_NODE)) {
            var root = ArchiveNode.newInstance();
            var branch = ArchiveNode.newInstance();
            var selected = ArchiveNode.newInstance();
            var temporary = ArchiveNode.newInstance();
            var tail = ArchiveNode.newInstance();
            var updatedTail = ArchiveNode.newInstance();
            root.setAnchor(branch);
            branch.getTrail().add(selected);
            branch.getTrail().add(tail);
            var cursor = root.vmf().content().iterator(strategy);
            check(cursor.next() == root && cursor.next() == branch && cursor.next() == selected && cursor.hasNext(),
                    "Select a list position and look ahead past it");

            root.setAnchor(null);
            branch.getTrail().set(0, temporary);
            check(!cursor.isAddSupported() && cursor.hasNext(),
                    "Replacing the selection invalidates editing it while reading continues");
            expectCursorRejection(ConcurrentModificationException.class, () -> cursor.set(null));

            branch.getTrail().set(0, selected);
            branch.getTrail().set(1, updatedTail);
            check(cursor.isAddSupported() && cursor.hasNext(),
                    "Index assignment can restore the selection in an entered detached frame");
            cursor.set(null);
            check(branch.getTrail().get(0) == null && cursor.next() == updatedTail && !cursor.hasNext(),
                    "The cursor edits the detached frame and reads the live next slot");
        }
    }

    private static void verifyReferenceCursorDelivery() {
        var root = ArchiveNode.newInstance();
        var original = ArchiveNode.newInstance();
        var replacement = ArchiveNode.newInstance();
        root.getTrail().add(original);
        var cursor = root.vmf().content().iterator();
        cursor.next();
        cursor.next();

        var events = new ArrayList<String>();
        var trace = root.vmf().changes().addListener(change -> events.add(change.propertyName()), false);
        var failure = new IllegalStateException("Listener failed.");
        try {
            cursor.set(original);
            var throwing = root.vmf().changes().addListener(change -> { throw failure; }, false);
            try {
                try {
                    cursor.set(replacement);
                    throw new AssertionError("The replacement should report the listener failure");
                } catch (IllegalStateException error) {
                    check(error == failure, "The original replacement listener failure propagates");
                }
                check(root.getTrail().get(0) == replacement && cursor.isAddSupported(),
                        "The committed replacement retains the cursor position");
                try {
                    cursor.add(original);
                    throw new AssertionError("The insertion should report the listener failure");
                } catch (IllegalStateException error) {
                    check(error == failure, "The original insertion listener failure propagates");
                }
            } finally {
                throwing.unsubscribe();
            }
            cursor.add(replacement);
            check(events.equals(List.of("trail", "trail", "trail", "trail")), "Cursor edits emit normal list notifications");
            check(root.getTrail().get(1) == original && root.getTrail().get(2) == replacement && !cursor.hasNext(),
                    "The cursor advances beyond insertions that committed before a listener failed");
        } finally {
            trace.unsubscribe();
        }
    }

    private static void verifyOrderedReferenceSetCursor() {
        var root = ArchiveNode.newInstance();
        var first = ArchiveNode.newInstance();
        var last = ArchiveNode.newInstance();
        var replacement = ArchiveNode.newInstance();
        var inserted = ArchiveNode.newInstance();
        var secondInsertion = ArchiveNode.newInstance();
        var lastReplacement = ArchiveNode.newInstance();
        root.getFeatured().add(first);
        root.getFeatured().add(last);
        var cursor = root.vmf().content().iterator();
        check(cursor.next() == root && cursor.next() == first && cursor.isAddSupported(),
                "The ordered set supplies a writable positional cursor");

        var events = new ArrayList<String>();
        var trace = root.vmf().changes().addLocalListener(change -> events.add(change.propertyName()));
        try {
            cursor.set(first);
            expectCursorRejection(IllegalArgumentException.class, () -> cursor.set(last));
            expectCursorRejection(IllegalArgumentException.class, () -> cursor.add(last));
            expectCursorRejection(NullPointerException.class, () -> cursor.set(null));
            expectCursorRejection(NullPointerException.class, () -> cursor.add(null));
            check(events.isEmpty(), "Rejected edits and same-identity replacements do not publish set changes");
            cursor.set(replacement);
            cursor.add(inserted);
            cursor.add(secondInsertion);
            expectCursorRejection(IllegalStateException.class, () -> cursor.set(first));
            check(cursor.next() == last && !cursor.hasNext() && !cursor.hasNext() && cursor.isAddSupported(),
                    "The cursor skips inserted set members and terminal lookahead retains its selection");
            expectCursorRejection(UnsupportedOperationException.class, cursor::remove);
            cursor.set(lastReplacement);
            var sequence = root.getFeatured().iterator();
            for (var expected : List.of(replacement, inserted, secondInsertion, lastReplacement)) {
                check(sequence.next() == expected, "Ordered cursor mutates the exact set position");
            }
            check(!sequence.hasNext() && events.equals(List.of("featured", "featured", "featured", "featured")),
                    "Each committed ordered-set edit reports one native event");
        } finally {
            trace.unsubscribe();
        }
    }

    private static void expectCursorRejection(Class<? extends RuntimeException> type, Runnable edit) {
        try {
            edit.run();
            throw new AssertionError("Expected " + type.getSimpleName());
        } catch (RuntimeException error) {
            check(type.isInstance(error), "Expected " + type.getSimpleName() + " but got " + error);
        }
    }

    private static List<String> visit(VObject root, Function<VObject, String> label) {
        var events = new ArrayList<String>();
        var iterator = VIterator.of(root, new TraversalListener() {
            @Override public void onEnter(VObject value) { events.add("+" + label.apply(value)); }
            @Override public void onExit(VObject value) {
                check(value != null, "An exhausted traversal emits no sentinel event");
                events.add("-" + label.apply(value));
            }
            @Override public boolean isIgnoreNullObjects() { return false; }
        }, IterationStrategy.UNIQUE_PROPERTY);
        while (iterator.hasNext()) iterator.next();
        check(!iterator.hasNext(), "Repeated terminal lookahead remains exhausted");
        return events;
    }

    private static void verifyAnnotations() {
        var model = DefaultSettings.newInstance();
        for (var reflect : List.of(model.vmf().reflect(), model.asReadOnly().vmf().reflect())) {
            check(reflect.annotations().stream().map(Annotation::getKey).collect(Collectors.toList()).equals(
                    List.of("purpose", "tag", "tag")), "Type metadata is ordered and excludes base annotations");
            check(reflect.annotationByKey("purpose").orElseThrow().getValue().equals("runtime configuration")
                            && reflect.annotationsByKey("tag").stream().map(Annotation::getValue)
                            .collect(Collectors.toList()).equals(List.of("first", "second")),
                    "Repeated type annotations retain source order");
            var name = reflect.propertyByName("name").orElseThrow();
            check(name.annotations().stream().map(Annotation::getKey).collect(Collectors.toList()).equals(
                    List.of("role", "role", "vmf:jackson:rename", "vmf:property:containment-info"))
                            && name.annotationByKey("role").orElseThrow().getValue().equals("label"),
                    "Inherited property metadata retains repeated declared values");
        }
        var archive = ArchiveNode.newInstance().vmf().reflect();
        check(archive.propertyByName("parent").orElseThrow().annotationByKey("vmf:property:containment-info")
                        .orElseThrow().getValue().equals("container:children")
                        && archive.propertyByName("anchor").orElseThrow().annotationByKey("vmf:property:reference-opposite")
                        .orElseThrow().getValue().isEmpty(),
                "Relationship annotations describe reciprocal names when applicable");
        var label = ValueLabel.newBuilder().build().vmf().reflect();
        check(label.annotationByKey("vmf:type:immutable").isPresent(), "An immutable value exposes the immutable marker");
    }

    private static void verifyStaticTypes() {
        var reflected = DefaultSettings.type().reflect();
        var metadata = reflected.propertyMetadata();
        check(metadata.stream().map(field -> field.getName()).collect(Collectors.toList()).equals(
                reflected.properties().stream().map(Property::getName).collect(Collectors.toList())),
                "Static field metadata follows reflection order");
        check(metadata.stream().anyMatch(field -> field.getName().equals("name")
                        && field.annotations().stream().anyMatch(annotation -> annotation.getValue().equals("display_name"))),
                "Static field metadata includes inherited annotation values");
        check(DoubleSeries.type().reflect().allTypes().stream().anyMatch(type ->
                        type.equals(NumericSeries.type()) && type.isInterfaceOnly()),
                "The model registry includes interface-only declarations");
        check(DoubleSeries.type().isModelSubtypeOf(NumericSeries.type())
                        && DoubleSeries.type().superTypes().get(0).getDescriptor().getArguments().get(0)
                        .getName().equals("java.lang.Double")
                        && ForwardedSeries.type().isModelSubtypeOf(NumericSeries.type()),
                "Static type descriptors include applied bounds and preserve proper inheritance");
    }

    private static ArchiveNode node(String name) {
        return ArchiveNode.newBuilder().withName(name).build();
    }

    private static List<String> names(VObject root, IterationStrategy strategy) {
        return root.vmf().content().stream(strategy).map(model ->
                (String) model.vmf().reflect().propertyByName("name").orElseThrow().get()).collect(Collectors.toList());
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
