package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.opposites.Endpoint;
import eu.mihosoft.vmf.conformance.ordinarycontainment.LegacyChild;
import eu.mihosoft.vmf.conformance.ordinarycontainment.LegacyContainer;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ReadOnlyArchiveNode;
import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vcollections.VListChange;
import eu.mihosoft.vcollections.VListChangeListener;
import vjavax.observer.collection.CollectionChangeEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class ReferenceListObservationConformance {
    private ReferenceListObservationConformance() { }

    static void verify() {
        verifyReferences();
        verifySparseReferenceRemoval();
        verifySparseContainmentTransfer();
        verifyContainmentObserverFailure();
        verifyIndependentSubscriptionHandles();
    }

    private static void verifyReferences() {
        var owner = ArchiveNode.newInstance();
        var item = ArchiveNode.newBuilder().withName("item").build();
        var replacement = ArchiveNode.newBuilder().withName("replacement").build();
        var list = owner.getTrail();
        var view = owner.asReadOnly().getTrail();
        var mutable = new ArrayList<CollectionChangeEvent<ArchiveNode, VList<ArchiveNode>, VListChange<ArchiveNode>>>();
        var projected = new ArrayList<CollectionChangeEvent<ReadOnlyArchiveNode, VList<ReadOnlyArchiveNode>, VListChange<ReadOnlyArchiveNode>>>();
        var local = list.addChangeListener(mutable::add);
        var readOnly = view.addChangeListener(projected::add);
        list.addAll(Arrays.asList(item, null, item));
        list.set(1, replacement);
        check(mutable.size() == 2 && projected.size() == 2, "Both list scopes observe ordinary additions and replacement");
        check(mutable.get(0).source() == list && projected.get(0).source() == view, "Each observer retains its exact list source");
        check(Arrays.equals(mutable.get(0).added().indices(), new int[]{0, 1, 2})
                        && Arrays.equals(projected.get(0).added().indices(), new int[]{0, 1, 2}),
                "Each list reports original positions, including alias and null slots");
        var values = projected.get(0).added().elements();
        check(values.get(0) == item.asReadOnly() && values.get(1) == null && values.get(2) == values.get(0),
                "Projected values share the exact read-only view identities as the list's getter");
        check(projected.get(1).removed().elements().get(0) == null
                        && projected.get(1).added().elements().get(0) == replacement.asReadOnly()
                        && Arrays.equals(projected.get(1).added().indices(), new int[]{1}),
                "The projected replacement preserves old null, new identity and index");
        readOnly.unsubscribe();
        list.remove(2);
        local.unsubscribe();
        list.clear();
        check(mutable.size() == 3 && projected.size() == 2 && values.get(2) == item.asReadOnly(),
                "Disposing either subscription leaves captured payloads stable");
    }

    private static void verifySparseReferenceRemoval() {
        var owner = Endpoint.newInstance();
        var group = Endpoint.newInstance();
        var list = owner.getGroups();
        var view = owner.asReadOnly().getGroups();
        list.addAll(Arrays.asList(group, null, group));
        verifySparseRemoval(list, view, group, group.asReadOnly(), () -> group.getMembers().remove(owner));
    }

    private static void verifySparseContainmentTransfer() {
        var source = LegacyContainer.newInstance();
        var destination = LegacyContainer.newInstance();
        var child = LegacyChild.newInstance();
        var retained = LegacyChild.newInstance();
        var list = source.getChildren();
        var view = source.asReadOnly().getChildren();
        list.addAll(Arrays.asList(child, retained, null));
        list.set(2, child);
        verifySparseRemoval(list, view, child, child.asReadOnly(), () -> destination.getChildren().add(child));
        check(source.getChildren().get(0) == retained && destination.getChildren().get(0) == child
                        && child.getOwner() == destination,
                "The source and destination keep their ownership following the sparse callback");
    }

    private static void verifyContainmentObserverFailure() {
        var source = LegacyContainer.newInstance();
        var destination = LegacyContainer.newInstance();
        var child = LegacyChild.newInstance();
        var list = source.getChildren();
        list.addAll(Arrays.asList(child, null, null));
        list.set(2, child);
        var view = source.asReadOnly().getChildren();
        var expected = new IllegalStateException("first projected listener");
        var events = new ArrayList<String>();
        var first = view.addChangeListener(change -> { throw expected; });
        var following = view.addChangeListener(change -> events.add("projected"));
        var raw = list.addChangeListener(change -> events.add("source"));
        var target = destination.getChildren().addChangeListener(change -> events.add("destination"));
        try {
            destination.getChildren().add(child);
            throw new AssertionError("The observer failure escaped detection");
        } catch (IllegalStateException error) {
            check(error == expected, "The original failure is rethrown after the ownership update");
        } finally {
            first.unsubscribe();
            following.unsubscribe();
            raw.unsubscribe();
            target.unsubscribe();
        }
        check(events.equals(List.of("projected", "source", "destination")) && child.getOwner() == destination,
                "A failed projection listener does not interrupt the remaining transfer callbacks: " + events);
        source.getChildren().add(child);
        check(events.size() == 3 && child.getOwner() == source, "Disposed observers do not receive subsequent moves");
    }

    private static void verifyIndependentSubscriptionHandles() {
        var owner = ArchiveNode.newInstance();
        var ordinary = owner.getTrail();
        Runnable appendReference = () -> ordinary.add(ArchiveNode.newInstance());
        verifyHandles(ordinary, appendReference);
        verifyHandles(ordinary.asUnmodifiable(), appendReference);
        var parent = LegacyContainer.newInstance();
        verifyHandles(parent.getChildren(), () -> parent.getChildren().add(LegacyChild.newInstance()));
    }

    private static <T> void verifyHandles(VList<T> list, Runnable edit) {
        var calls = new AtomicInteger();
        VListChangeListener<T> callback = change -> calls.incrementAndGet();
        var first = list.addChangeListener(callback);
        var second = list.addChangeListener(callback);
        first.unsubscribe();
        first.unsubscribe();
        edit.run();
        check(calls.get() == 1, "Unsubscribing the same handle twice leaves the duplicate callback active");

        var third = list.addChangeListener(callback);
        check(list.removeChangeListener(callback), "Legacy removal still removes the first matching registration");
        second.unsubscribe();
        edit.run();
        check(calls.get() == 2, "A handle for a registration removed by callback cannot remove another registration");
        third.unsubscribe();
        edit.run();
        check(calls.get() == 2, "The last handle independently disposes its registration");
    }

    private static <T, V> void verifySparseRemoval(VList<T> list, VList<V> view, T value, V projected, Runnable edit) {
        var delivered = new ArrayList<String>();
        var sourceSubscription = list.addChangeListener(change -> {
            check(change.source() == list && Arrays.equals(change.removed().indices(), new int[]{0, 2})
                            && change.removed().elements().get(0) == value && change.removed().elements().get(1) == value
                            && change.added().elements().isEmpty(),
                    "The source reports both actual positions with the original repeated identity");
            delivered.add("source");
        });
        var viewSubscription = view.addChangeListener(change -> {
            check(change.source() == view && Arrays.equals(change.removed().indices(), new int[]{0, 2})
                            && change.removed().elements().get(0) == projected && change.removed().elements().get(1) == projected
                            && change.added().elements().isEmpty(),
                    "The projected event uses its view source and the canonical projection of each occurrence");
            delivered.add("view");
        });
        try {
            edit.run();
        } finally {
            viewSubscription.unsubscribe();
            sourceSubscription.unsubscribe();
        }
        check(delivered.size() == 2 && delivered.contains("source") && delivered.contains("view") && list.size() == 1,
                "A single batched sparse removal reaches both current observers: " + delivered);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
