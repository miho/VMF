package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.opposites.Endpoint;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.DefaultBase;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;
import eu.mihosoft.vmf.conformance.values.ReadOnlyArchiveNode;
import eu.mihosoft.vmf.conformance.values.ValueLabel;
import eu.mihosoft.vmf.conformance.values.ValueSelection;
import eu.mihosoft.vmf.example.workshops.Station;
import eu.mihosoft.vmf.example.workshops.Workshop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class ShallowCopyConformance {
    private ShallowCopyConformance() {}

    public static void main(String[] args) {
        checkCollectionStorage();
        checkLazyListsAndObservation();
        checkObserverOrderAndHistory();
        checkMixedObserverOrder();
        checkInheritedValuesAndAliases();
        checkOwnershipAndSafeOpposites();
        System.out.println("Java verified shallow-copy list storage, owner-specific callbacks and history, and model aliases.");
    }

    private static void checkCollectionStorage() {
        var source = CollectionProfile.newInstance();
        source.getMessages().add("before");
        source.getReadings().add(1.0);
        source.getPhases().add("phase");
        source.getCounts().add(1);
        source.getLevels().add(1.0);
        source.getOptions().put("present", null);
        CollectionProfile copy = source.vmf().content().shallowCopy();
        check(source != copy && source.getMessages() == copy.getMessages() && source.getReadings() == copy.getReadings(),
                "materialized ordinary scalar lists share storage");
        check(source.getPhases() != copy.getPhases() && source.getCounts() != copy.getCounts()
                && source.getLevels() != copy.getLevels() && source.getOptions() != copy.getOptions(),
                "checked lists, maps and sets have independent membership");
        copy.getMessages().add(null);
        source.getReadings().add(2.0);
        copy.getPhases().clear();
        copy.getCounts().add(2);
        copy.getLevels().clear();
        copy.getOptions().put("other", true);
        check(source.getMessages().equals(Arrays.asList("before", null)) && copy.getReadings().equals(List.of(1.0, 2.0)),
                "ordinary list changes are shared");
        check(source.getPhases().equals(List.of("phase")) && source.getCounts().equals(List.of(1))
                && source.getLevels().equals(Set.of(1.0)) && source.getOptions().size() == 1
                && source.getOptions().containsKey("present") && copy.getOptions().containsKey("present")
                && copy.getOptions().get("present") == null, "managed collections retain independent captured members");
    }

    private static void checkLazyListsAndObservation() {
        var source = CollectionProfile.newInstance();
        CollectionProfile first = source.vmf().content().shallowCopy();
        check(source.getMessages() != first.getMessages(), "untouched ordinary lists initialize independently");
        CollectionProfile second = source.vmf().content().shallowCopy();
        check(source.getMessages() == second.getMessages(), "reading the list materializes shared storage");

        var observed = CollectionProfile.newInstance();
        observed.vmf().changes().addLocalListener(change -> {}).unsubscribe();
        CollectionProfile observedCopy = observed.vmf().content().shallowCopy();
        check(observed.getMessages() == observedCopy.getMessages(), "registration materializes the list's observation relay");
    }

    private static void checkObserverOrderAndHistory() {
        var source = CollectionProfile.newInstance();
        source.getMessages().add("before");
        CollectionProfile copy = source.vmf().content().shallowCopy();
        var trace = new ArrayList<String>();
        var local = source.vmf().changes().addLocalListener(change -> {
            check(change.object() == source, "local event owner");
            trace.add("source local");
        });
        var copyRecursive = copy.vmf().changes().addRecursiveListener(change -> {
            check(change.object() == copy, "shallow copy recursive event owner");
            trace.add("copy recursive");
        });
        var sourceRecursive = source.vmf().changes().addRecursiveListener(change -> {
            check(change.object() == source, "source recursive event owner");
            trace.add("source recursive");
        });
        try {
            copy.getMessages().add("edit");
            check(trace.equals(List.of("source local", "copy recursive", "source recursive")), "shared list callback order");
        } finally {
            local.unsubscribe();
            copyRecursive.unsubscribe();
            sourceRecursive.unsubscribe();
        }

        var original = ArchiveNode.newInstance();
        original.vmf().changes().start();
        original.getTrail().add(null);
        ArchiveNode clone = original.vmf().content().shallowCopy();
        check(original.vmf().changes().all().size() == 1 && clone.vmf().changes().all().isEmpty(), "history is fresh");
        original.vmf().changes().stop();
        var root = ArchiveNode.newInstance();
        root.getChildren().addAll(List.of(clone, original));
        root.vmf().changes().start();
        original.getTrail().add(null);
        var history = root.vmf().changes().all();
        check(history.size() == 2 && history.get(0).object() == clone && history.get(1).object() == original,
                "recursive history records both logical owners in containment registration order");
        root.vmf().changes().stop();
    }

    private static void checkInheritedValuesAndAliases() {
        var settings = DefaultSettings.newBuilder().withName("name").withLimit(9).withNote("detail").build();
        DefaultBase copied = settings.vmf().content().shallowCopy();
        check(copied instanceof DefaultSettings && copied.getName().equals("name") && copied.getLimit() == 9
                && ((DefaultSettings) copied).getNote().equals("detail"), "inherited properties and concrete root");
        var original = ArchiveNode.newInstance();
        var member = ArchiveNode.newInstance();
        original.setAnchor(original);
        original.getTrail().addAll(Arrays.asList(member, null, member));
        original.getPeers().add(member);
        original.getFeatured().add(member);
        ArchiveNode clone = original.vmf().content().shallowCopy();
        check(clone != original && clone.getAnchor() == original && clone.getTrail() == original.getTrail(),
                "ordinary references and self-edges are shared");
        check(clone.getPeers() != original.getPeers() && clone.getFeatured() != original.getFeatured()
                && clone.getPeers().contains(member) && clone.getFeatured().contains(member), "reference sets copy membership");
        ReadOnlyArchiveNode viewCopy = original.asReadOnly().vmf().content().shallowCopy();
        check(!(viewCopy instanceof ArchiveNode) && viewCopy.getAnchor() == original.asReadOnly()
                && viewCopy.getTrail().get(0) == member.asReadOnly(), "read-only shallow view keeps projected aliases");

        var label = ValueLabel.newBuilder().withText("same").build();
        var owner = ValueSelection.newBuilder().withCurrent(label).build();
        ValueSelection ownerCopy = owner.vmf().content().shallowCopy();
        check(ownerCopy.getCurrent() == label && label.vmf().content().shallowCopy() == label, "immutable aliases are reused");
    }

    private static void checkMixedObserverOrder() {
        var source = CollectionProfile.newInstance();
        var raw = source.getMessages();
        CollectionProfile copy = source.vmf().content().shallowCopy();
        check(copy.getMessages() == raw, "The owners share one physical list source");
        var sourceChanges = source.vmf().changes();
        var copyChanges = copy.vmf().changes();
        var trace = new ArrayList<String>();
        raw.addChangeListener(edit -> trace.add("raw0:" + sourceChanges.all().size()));
        sourceChanges.addLocalListener(change -> {
            check(change.object() == source, "The first owner receives its own modeled list change");
            trace.add("source:" + sourceChanges.all().size());
        });
        var projected = source.asReadOnly().getMessages();
        projected.addChangeListener(edit -> trace.add("projected0:" + sourceChanges.all().size()));
        raw.addChangeListener(edit -> trace.add("raw1:" + copyChanges.all().size()));
        copy.asReadOnly().vmf().changes().addLocalListener(change -> {
            check(change.object() == copy.asReadOnly(), "The projected owner receives its own modeled list change");
            trace.add("readonly-copy");
        });
        projected.addChangeListener(edit -> trace.add("projected1"));
        copyChanges.addLocalListener(change -> {
            check(change.object() == copy, "The shallow copy receives its own modeled list change");
            trace.add("copy:" + copyChanges.all().size());
        });
        sourceChanges.start();
        copyChanges.start();
        raw.add("outer");
        sourceChanges.stop();
        copyChanges.stop();
        check(trace.equals(List.of("raw0:0", "source:0", "projected0:1", "readonly-copy", "projected1", "raw1:0", "copy:0")),
                "Mixed shared-list observation: " + trace);
        check(sourceChanges.all().size() == 1 && sourceChanges.all().get(0).object() == source
                && copyChanges.all().size() == 1 && copyChanges.all().get(0).object() == copy,
                "A shared edit is recorded once on each logical owner");

        var fresh = CollectionProfile.newInstance();
        var shared = fresh.getMessages();
        CollectionProfile shallow = fresh.vmf().content().shallowCopy();
        var view = shallow.asReadOnly();
        trace.clear();
        shared.addChangeListener(edit -> {
            trace.add("raw");
            view.vmf().changes().addLocalListener(change -> {
                check(change.object() == view, "The new modeled relay retains the shallow view's owner");
                trace.add("readonly-copy");
            });
        });
        fresh.asReadOnly().getMessages().addChangeListener(edit -> trace.add("projected"));
        shared.add("first");
        check(trace.equals(List.of("raw", "projected", "readonly-copy")), "A live projected relay: " + trace);
    }

    private static void checkOwnershipAndSafeOpposites() {
        var workshop = Workshop.newInstance();
        Workshop empty = workshop.vmf().content().shallowCopy();
        check(workshop.getStations() != empty.getStations(), "empty owning lists are independent");
        var station = Station.newBuilder().withName("station").build();
        workshop.getStations().add(station);
        try {
            workshop.vmf().content().shallowCopy();
            throw new AssertionError("A populated checked containment was copied");
        } catch (IllegalStateException expected) {
            check(station.getWorkshop() == workshop, "rejected copy preserves its original owner");
        }
        Station detached = station.vmf().content().shallowCopy();
        check(detached.getName().equals("station") && detached.getWorkshop() == null, "a copied leaf has no owner");

        var first = Endpoint.newInstance();
        var second = Endpoint.newInstance();
        first.getLinks().add(second);
        var notification = new ArrayList<String>();
        var listening = second.vmf().changes().addLocalListener(change -> notification.add(change.propertyName()));
        try {
            Endpoint clone = first.vmf().content().shallowCopy();
            check(clone.getLinks().get(0) == second && second.getPanels().contains(first)
                    && second.getPanels().contains(clone) && notification.equals(List.of("panels")),
                    "both copied and original links are registered with the live many-ended opposite");
        } finally {
            listening.unsubscribe();
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
