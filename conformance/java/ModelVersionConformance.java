package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.equality.EqualityContainer;
import eu.mihosoft.vmf.conformance.immutables.ImmutableBadge;
import eu.mihosoft.vmf.conformance.redeclarations.PayloadRecord;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.example.workshops.Station;
import eu.mihosoft.vmf.example.workshops.Workshop;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.Changes;
import eu.mihosoft.vmf.runtime.core.internal.ChangesImpl;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
final class ModelVersionConformance {
    private ModelVersionConformance() { }

    public static void main(String[] args) {
        mutableLifecycle();
        readOnlyLifecycle();
        readOnlyInverseAndImmutable();
        applyReadOnlyChanges();
        retainReadOnlyChanges();
        System.out.println("Java verified live versioning and independent read-only view observation.");
    }

    private static void mutableLifecycle() {
        var model = EqualityContainer.newInstance();
        var changes = (ChangesImpl) model.vmf().changes();
        var initial = changes.modelVersion();
        check(version(changes) == 0 && !changes.isModelVersioningEnabled(), "Versioning initially disabled with zero version");
        model.setName("before");
        check(changes.modelVersion() == initial, "Untracked edit leaves the original version unchanged");

        var events = new ArrayList<String>();
        var earlier = changes.addRecursiveListener(change -> {
            var name = change.propertyChange().orElseThrow().newValue().toString();
            events.add("before-" + name + "=" + version(changes));
            if ("outer".equals(name)) model.setName("nested");
        });
        changes.start();
        var later = changes.addRecursiveListener(change -> {
            var name = change.propertyChange().orElseThrow().newValue();
            check(changes.modelVersion().timestamp() == change.getTimestamp(), "The current version takes the delivered event's timestamp");
            events.add("after-" + name + "=" + version(changes));
        });
        model.setName("outer");
        check(events.equals(List.of("before-outer=0", "before-nested=0", "after-nested=1", "after-outer=2")),
                "Version advances at its listener's turn, including reentrant dispatch");
        check(changes.modelVersion().timestamp() == changes.all().get(0).getTimestamp()
                && changes.all().get(0).getTimestamp() < changes.all().get(1).getTimestamp(),
                "The last delivered version need not have the latest event creation timestamp");
        earlier.unsubscribe();
        later.unsubscribe();

        changes.clear();
        check(version(changes) == 2 && changes.isModelVersioningEnabled() && changes.all().isEmpty(), "Clear removes only recorded history");
        boolean refused = false;
        try {
            changes.disableModelVersioning();
        } catch (RuntimeException expected) {
            refused = changes.isModelVersioningEnabled();
        }
        check(refused, "Versioning cannot be disabled during recording");
        changes.startTransaction();
        model.setName("published");
        changes.publishTransaction();
        check(version(changes) == 3 && changes.transactions().size() == 1, "Publishing does not advance version");
        model.setName("pending");
        changes.stop();
        check(version(changes) == 4 && !changes.isModelVersioningEnabled() && changes.transactions().size() == 2,
                "Stop publishes remaining edits and disables versioning");
        model.setName("stopped");
        check(version(changes) == 4, "Stopped edits do not advance version");

        changes.enableModelVersioning();
        changes.enableModelVersioning();
        model.setName("version only");
        check(version(changes) == 5 && changes.all().size() == 2, "Repeated enable tracks each event without recording history");
        changes.disableModelVersioning();
        model.setName("disabled");
        check(version(changes) == 5, "Manual disable freezes the version");
        changes.start();
        model.setName("next session");
        check(version(changes) == 6 && changes.all().size() == 1 && changes.transactions().isEmpty(),
                "A new recording session resumes the version and replaces the old history");
        changes.stop();
    }

    private static void readOnlyLifecycle() {
        var model = ArchiveRoot.newInstance();
        var child = ArchiveNode.newInstance();
        model.setHead(child);
        var view = model.asReadOnly();
        var mutable = model.vmf().changes();
        var facade = view.vmf();
        var changes = facade.changes();
        check(changes != mutable && changes == facade.changes(), "The read-only facade handle has its own stream");
        var observed = new ArrayList<String>();
        changes.addRecursiveListener(change -> {
            check(change.object() == view, "Projected change source is the read-only owner");
            if (change.propertyChange().isPresent()) {
                var property = change.propertyChange().orElseThrow();
                if (property.oldValue() != null) check(property.oldValue() == child.asReadOnly(), "Projected old model value");
                if (property.newValue() != null) check(property.newValue() == model.getHead().asReadOnly(), "Projected new model value");
            } else {
                var member = change.listChange().orElseThrow().added().elements().get(0);
                check(member == child.asReadOnly(), "Projected collection member");
            }
            observed.add(change.propertyName());
        });
        changes.start();
        child.setName("existing child");
        check(version(changes) == 0 && observed.isEmpty(), "Recursive view observation does not descend into existing children");
        model.getReview().add(child);
        var replacement = ArchiveNode.newInstance();
        model.setHead(replacement);
        replacement.setName("new child");
        check(observed.equals(List.of("review", "head", "head")) && version(changes) == 3 && changes.all().size() == 3,
                "View observes root edits independently and does not follow new children");
        check(version(mutable) == 0 && mutable.all().isEmpty(), "View recording does not start mutable recording");
        changes.stop();
        check(!changes.isModelVersioningEnabled() && changes.transactions().size() == 1
                && changes.transactions().get(0).changes().size() == 3, "The view publishes its own projected history");
    }

    private static void readOnlyInverseAndImmutable() {
        var station = Station.newInstance();
        var changes = station.asReadOnly().vmf().changes();
        var workshop = Workshop.newInstance();
        changes.start();
        workshop.getStations().add(station);
        workshop.getStations().remove(station);
        check(version(changes) == 2 && changes.all().isEmpty(), "View versions include inverse notifications without recording them");
        changes.stop();

        var immutable = ImmutableBadge.newBuilder().withText("fixed").build();
        var fixed = immutable.vmf().changes();
        fixed.start();
        check(fixed.isModelVersioningEnabled() && version(fixed) == 0, "Immutable control can be started with no possible model edits");
        fixed.stop();
        check(!fixed.isModelVersioningEnabled() && fixed.all().isEmpty(), "Immutable control stops without history");
    }

    private static void applyReadOnlyChanges() {
        var source = ArchiveNode.newBuilder().withName("source").build();
        var target = ArchiveNode.newBuilder().withName("target").build();
        var view = source.asReadOnly();
        var changes = view.vmf().changes();
        var rename = capture(changes, () -> source.setName("captured"));
        check(rename.object() == view, "The edit exposes its view as the source");
        rejects(() -> rename.apply(target.asReadOnly()));
        rename.apply(target);
        check("captured".equals(target.getName()), "A projected scalar edit applies to its writable peer");

        var nullSlot = capture(changes, () -> source.getTrail().add(null));
        nullSlot.apply(target);
        check(target.getTrail().size() == 1 && target.getTrail().get(0) == null,
                "A null-only ordinary model list edit preserves its target slot");
        var model = ArchiveNode.newInstance();
        var relation = capture(changes, () -> source.setAnchor(model));
        rejects(() -> relation.apply(target));
        rejects(() -> relation.apply(source));
        check(target.getAnchor() == null && source.getAnchor() == model,
                "A projected reference cannot forward a model payload");

        var scalarSource = CollectionProfile.newInstance();
        var scalarTarget = CollectionProfile.newInstance();
        var scalarChanges = scalarSource.asReadOnly().vmf().changes();
        scalarTarget.getMessages().add("existing");
        capture(scalarChanges, () -> scalarSource.getMessages().add("new")).apply(scalarTarget);
        check(scalarTarget.getMessages().equals(List.of("new", "existing")), "The target receives the positional edit");
        scalarTarget.getCounts().addAll(List.of(1, 2, 3));
        var bounded = capture(scalarChanges, () -> scalarSource.getCounts().add(9));
        rejects(() -> bounded.apply(scalarTarget));
        check(scalarTarget.getCounts().equals(List.of(1, 2, 3)), "A rejected peer keeps its bounded list");

        scalarTarget.getOptions().put("occupied", true);
        capture(scalarChanges, () -> scalarSource.getOptions().put("occupied", null)).apply(scalarTarget);
        check(scalarTarget.getOptions().containsKey("occupied") && scalarTarget.getOptions().get("occupied") == null,
                "A map edit distinguishes a present null from a missing key");
        scalarTarget.getLevels().add(0.0);
        capture(scalarChanges, () -> scalarSource.getLevels().add(-0.0)).apply(scalarTarget);
        check(scalarTarget.getLevels().containsAll(List.of(0.0, -0.0)) && scalarTarget.getLevels().size() == 2,
                "A set edit preserves distinct signed zero members");

        var opaqueSource = PayloadRecord.newInstance();
        var opaqueTarget = PayloadRecord.newInstance();
        var opaqueChanges = opaqueSource.asReadOnly().vmf().changes();
        var external = new StringBuilder("foreign host");
        capture(opaqueChanges, () -> opaqueSource.setPayload(external)).apply(opaqueTarget);
        check(opaqueTarget.getPayload() == external, "An external payload retains its ordinary identity");
        var badge = ImmutableBadge.newBuilder().withText("foreign immutable").build();
        var opaque = capture(opaqueChanges, () -> opaqueSource.setPayload(badge));
        rejects(() -> opaque.apply(opaqueTarget));
        check(opaqueTarget.getPayload() == external, "An immutable model inside an opaque field is still a model payload");
    }

    private static void retainReadOnlyChanges() {
        var source = CollectionProfile.newInstance();
        var target = CollectionProfile.newInstance();
        target.getMessages().add("existing");
        var view = source.asReadOnly();
        var history = view.vmf().changes();
        history.start();
        source.getMessages().add("captured");
        source.getOptions().put("occupied", null);
        var list = history.all().get(0);
        var map = history.all().get(1);

        source.getMessages().set(0, "later");
        source.getOptions().put("occupied", true);
        history.clear();
        history.stop();

        check(list.object() == view && map.object() == view && history.all().isEmpty(),
                "A retained edit keeps its projected source after history is cleared");
        list.apply(target);
        map.apply(target);
        check(target.getMessages().equals(List.of("captured", "existing"))
                        && target.getOptions().containsKey("occupied") && target.getOptions().get("occupied") == null
                        && source.getMessages().equals(List.of("later")) && source.getOptions().get("occupied"),
                "Retained collection edits apply the recorded payload and positions without depending on current source data");
    }

    private static Change capture(Changes changes, Runnable edit) {
        var observed = new ArrayList<Change>();
        var subscription = changes.addListener(observed::add);
        try {
            edit.run();
        } finally {
            subscription.unsubscribe();
        }
        check(observed.size() == 1, "Expected one projected edit");
        return observed.get(0);
    }

    private static void rejects(Runnable edit) {
        try {
            edit.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("The projected edit should be rejected");
    }

    private static long version(Changes changes) {
        return changes.modelVersion().versionNumber();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
