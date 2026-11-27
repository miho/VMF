package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.conformance.ordinarycontainment.LegacyChild;
import eu.mihosoft.vmf.conformance.ordinarycontainment.LegacyContainer;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import vjavax.observer.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class ProjectedListObservationConformance {
    private ProjectedListObservationConformance() {}

    static void verify() {
        verifyLaterModeledRelay();
        verify(() -> {
            var owner = CollectionProfile.newInstance();
            return new Fixture<>(owner.getMessages(), owner::asReadOnly, () -> owner.asReadOnly().getMessages(),
                    value -> owner.getMessages().add(value), Function.identity(), Function.identity());
        });
        verify(() -> {
            var owner = ArchiveNode.newInstance();
            return new Fixture<>(owner.getTrail(), owner::asReadOnly, () -> owner.asReadOnly().getTrail(),
                    value -> owner.getTrail().add(ArchiveNode.newBuilder().withName(value).build()),
                    node -> node.getName(), node -> node.getName());
        });
        verify(() -> {
            var owner = LegacyContainer.newInstance();
            return new Fixture<>(owner.getChildren(), owner::asReadOnly, () -> owner.asReadOnly().getChildren(),
                    value -> owner.getChildren().add(LegacyChild.newBuilder().withName(value).build()),
                    node -> node.getName(), node -> node.getName());
        });
    }

    private static void verifyLaterModeledRelay() {
        var owner = CollectionProfile.newInstance();
        var source = owner.getMessages();
        var view = owner.asReadOnly().getMessages();
        var projected = new ArrayList<String>();
        var original = view.addChangeListener(edit -> projected.add("old:" + edit.added().elements().get(0)));
        owner.vmf().changes().addListener(edit -> {
            if (!edit.propertyName().equals("messages") || source.size() != 1) return;
            original.unsubscribe();
            view.addChangeListener(delta -> projected.add("new:" + delta.added().elements().get(0)));
        }, false);

        source.add("outer");
        source.add("future");

        expect(projected, "old:outer", "new:future");
    }

    private static <T, V> void verify(Supplier<Fixture<T, V>> create) {
        verifyMaterialization(create.get());
        verifySubscriptionReentry(create.get(), false);
        verifySubscriptionReentry(create.get(), true);
        verifyActiveProjectionSnapshot(create.get());
    }

    private static <T, V> void verifyMaterialization(Fixture<T, V> f) {
        var events = new ArrayList<String>();
        f.source.addChangeListener(change -> events.add("before view"));
        f.materializeModel.run();
        f.source.addChangeListener(change -> events.add("before list"));
        var view = f.view.get();
        f.add.accept("unobserved");
        expect(events, "before view", "before list");
        f.source.addChangeListener(change -> events.add("after list"));
        var first = view.addChangeListener(change -> {
            if (change.source() != view) throw new AssertionError("Expected the exact projected list");
            events.add("first projected");
        });
        f.source.addChangeListener(change -> events.add("last mutable"));
        var second = view.addChangeListener(change -> events.add("second projected"));
        f.add.accept("started");
        expect(events, "before view", "before list", "first projected", "second projected", "after list", "last mutable");
        first.unsubscribe();
        second.unsubscribe();
        f.add.accept("unobserved again");
        expect(events, "before view", "before list", "after list", "last mutable");
        view.addChangeListener(change -> events.add("restarted"));
        f.add.accept("again");
        expect(events, "before view", "before list", "restarted", "after list", "last mutable");
    }

    private static <T, V> void verifySubscriptionReentry(Fixture<T, V> f, boolean initiallyObserved) {
        var events = new ArrayList<String>();
        final Subscription[] old = new Subscription[1];
        f.source.addChangeListener(change -> {
            var key = f.sourceKey.apply(change.added().elements().get(0));
            events.add("early:" + key);
            if ("outer".equals(key)) {
                if (old[0] != null) old[0].unsubscribe();
                f.view.get().addChangeListener(edit -> events.add("new:" + f.viewKey.apply(edit.added().elements().get(0))));
                f.source.addChangeListener(edit -> events.add("new mutable:" + f.sourceKey.apply(edit.added().elements().get(0))));
                f.add.accept("nested");
            }
        });
        var view = f.view.get();
        if (initiallyObserved) {
            old[0] = view.addChangeListener(change -> events.add("old:" + f.viewKey.apply(change.added().elements().get(0))));
        }
        f.source.addChangeListener(change -> events.add("late:" + f.sourceKey.apply(change.added().elements().get(0))));
        f.add.accept("outer");
        expect(events, "early:outer", "early:nested", "new:nested", "late:nested", "new mutable:nested", "new:outer", "late:outer");
    }

    private static <T, V> void verifyActiveProjectionSnapshot(Fixture<T, V> f) {
        var events = new ArrayList<String>();
        var view = f.view.get();
        final Subscription[] old = new Subscription[1];
        view.addChangeListener(change -> {
            var key = f.viewKey.apply(change.added().elements().get(0));
            events.add("first:" + key);
            if ("outer".equals(key)) {
                old[0].unsubscribe();
                view.addChangeListener(edit -> events.add("new:" + f.viewKey.apply(edit.added().elements().get(0))));
                f.add.accept("nested");
            }
        });
        old[0] = view.addChangeListener(change -> events.add("old:" + f.viewKey.apply(change.added().elements().get(0))));
        f.source.addChangeListener(change -> events.add("late:" + f.sourceKey.apply(change.added().elements().get(0))));
        f.add.accept("outer");
        expect(events, "first:outer", "first:nested", "new:nested", "late:nested", "old:outer", "late:outer");
    }

    private static void expect(List<String> events, String... expected) {
        if (!events.equals(List.of(expected))) throw new AssertionError("Expected " + List.of(expected) + ", got " + events);
        events.clear();
    }

    private static final class Fixture<T, V> {
        final VList<T> source;
        final Runnable materializeModel;
        final Supplier<VList<V>> view;
        final Consumer<String> add;
        final Function<T, String> sourceKey;
        final Function<V, String> viewKey;

        Fixture(VList<T> source, Runnable materializeModel, Supplier<VList<V>> view, Consumer<String> add,
                Function<T, String> sourceKey, Function<V, String> viewKey) {
            this.source = source;
            this.materializeModel = materializeModel;
            this.view = view;
            this.add = add;
            this.sourceKey = sourceKey;
            this.viewKey = viewKey;
        }
    }
}
