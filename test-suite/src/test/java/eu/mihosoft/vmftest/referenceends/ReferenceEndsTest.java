package eu.mihosoft.vmftest.referenceends;

import eu.mihosoft.vmf.runtime.core.*;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;
import java.util.*;
import org.junit.Test;
import vjavax.observer.Subscription;
import static org.junit.Assert.*;

public class ReferenceEndsTest {
    @Test public void reciprocalListOrdersSourceRelaysAndRecordsOnlyPrimaryEdits() {
        for (boolean viaOpposite : new boolean[]{false, true}) {
            var member = Member.newInstance();
            var team = Team.newInstance();
            var teams = member.getTeams();
            var changes = member.vmf().changes();
            var trace = new ArrayList<String>();
            teams.addChangeListener(edit -> trace.add("direct:" + team.getMembers().contains(member)));
            member.asReadOnly().getTeams().addChangeListener(edit -> trace.add("projected"));
            changes.addLocalListener(edit -> trace.add("local:" + changes.all().size()));
            team.vmf().changes().addLocalListener(edit -> trace.add("opposite"));
            teams.addChangeListener(edit -> trace.add("late:" + changes.all().size()));
            changes.addRecursiveListener(edit -> trace.add("recursive:" + changes.all().size()));
            changes.start();
            if (viaOpposite) team.getMembers().add(member);
            else teams.add(team);
            changes.stop();

            assertEquals(viaOpposite ? List.of("direct:true", "projected", "local:0", "late:0", "recursive:0", "opposite")
                    : List.of("opposite", "direct:true", "projected", "local:0", "late:1", "recursive:1"), trace);
            assertEquals(viaOpposite ? 0 : 1, changes.all().size());
            assertSame(team, teams.get(0));
        }
    }

    @Test public void reciprocalListCapturesSourceRelaysBeforeInverseCallbacks() {
        var cases = Map.of(
                "none", List.of("inverse"),
                "direct", List.of("inverse", "old-direct"),
                "dormant", List.of("inverse", "projected"),
                "model", List.of("inverse", "old-model", "new-model"),
                "replace", List.of("inverse", "old-direct"));
        for (var scenario : cases.entrySet()) {
            var kind = scenario.getKey();
            var member = Member.newInstance();
            var team = Team.newInstance();
            var teams = member.getTeams();
            var trace = new ArrayList<String>();
            Subscription first;
            switch (kind) {
                case "direct":
                case "replace":
                    first = teams.addChangeListener(edit -> trace.add("old-direct"));
                    break;
                case "model":
                    first = member.vmf().changes().addLocalListener(edit -> trace.add("old-model"));
                    break;
                default:
                    first = null;
            }
            if (kind.equals("dormant")) member.asReadOnly().getTeams();
            team.vmf().changes().addLocalListener(edit -> {
                trace.add("inverse");
                if (kind.equals("replace")) first.unsubscribe();
                teams.addChangeListener(raw -> trace.add("new-direct"));
                member.vmf().changes().addLocalListener(raw -> trace.add("new-model"));
                member.asReadOnly().getTeams().addChangeListener(raw -> trace.add("projected"));
            });
            teams.add(team);
            assertEquals(kind, scenario.getValue(), trace);
            trace.clear();
            teams.add(Team.newInstance());
            assertTrue(kind, trace.containsAll(List.of("new-direct", "new-model", "projected")));
        }
    }

    private static Station station(String name) {
        return Station.newBuilder().withName(name).build();
    }

    @Test public void boundedPositionalReferencesKeepTheirListAndSetSemantics() {
        var a = station("a");
        var b = station("b");
        var c = station("c");
        var kept = station("kept");
        var source = BoundedPicks.newBuilder().withTrail(Arrays.asList(a, null, b, a)).withChoices(a, b, c).build();
        var builder = BoundedPicks.newBuilder().applyFrom(source);
        var target = BoundedPicks.newBuilder().withTrail(kept).withChoices(kept).build();
        var trail = target.getTrail();
        var choices = target.getChoices();

        builder.applyTo(target);
        assertSame(trail, target.getTrail());
        assertSame(choices, target.getChoices());
        assertEquals(5, trail.size());
        assertSame(kept, trail.get(0));
        assertSame(a, trail.get(1));
        assertNull(trail.get(2));
        assertSame(b, trail.get(3));
        assertSame(a, trail.get(4));
        assertEquals(4, choices.size());
        assertSame(kept, choices.get(0));
        assertSame(a, choices.get(1));
        assertSame(b, choices.get(2));
        assertSame(c, choices.get(3));
        source.getTrail().clear();
        source.getChoices().clear();

        builder.appendCollections(false).applyTo(target);
        assertEquals(4, trail.size());
        assertSame(a, trail.get(0));
        assertNull(trail.get(1));
        assertSame(b, trail.get(2));
        assertSame(a, trail.get(3));
        assertEquals(3, choices.size());
        assertSame(a, choices.get(0));
        assertSame(b, choices.get(1));
        assertSame(c, choices.get(2));
        assertEquals(List.of("/choices", "/trail"), target.vmf().content().validate().getIssues().stream()
                .map(ValidationIssue::getPath).collect(java.util.stream.Collectors.toList()));
    }

    @Test public void boundedOrderedBuilderRetainsExistingSlotsAndAppliesOwningTransfers() {
        var a = station("a");
        var b = station("b");
        var c = station("c");
        var source = Workshop.newBuilder().withStations(a, b).withFavorites(a).build();
        var target = Workshop.newBuilder().withStations(c).build();
        var staged = Workshop.newBuilder().applyFrom(source);
        assertSame(source, a.getWorkshop());
        assertSame(source, b.getWorkshop());

        var stations = target.getStations();
        staged.applyTo(target);
        assertSame(stations, target.getStations());
        assertEquals(3, stations.size());
        assertSame(c, stations.get(0));
        assertSame(a, stations.get(1));
        assertSame(b, stations.get(2));
        assertSame(target, a.getWorkshop());
        assertSame(target, b.getWorkshop());
        assertTrue(source.getStations().isEmpty());
        assertTrue(source.getFavorites().contains(a));
        assertTrue(target.getFavorites().contains(a));
        assertEquals("/stations", target.vmf().content().validate().getIssues().get(0).getPath());

        Workshop.newBuilder().withStations(b, a).appendCollections(false).applyTo(target);
        assertEquals(2, stations.size());
        assertSame(b, stations.get(0));
        assertSame(a, stations.get(1));
        assertNull(c.getWorkshop());
        assertTrue(target.vmf().content().validate().isValid());
    }

    @Test public void membershipUsesIdentityThroughReorderRenameAndReadOnlyProjection() {
        var shop = Workshop.newInstance();
        OrderedRefSet<Station> stations = shop.getStations();
        var first = station("Inspection");
        var second = station("Inspection");
        assertEquals(first, second);
        assertTrue(stations.add(first));
        assertFalse(stations.contains(second));
        assertEquals(-1, stations.indexOf(second));
        assertTrue(stations.add(second));
        List<Change> edits = new ArrayList<>();
        shop.vmf().changes().addListener(edits::add);
        assertFalse(stations.add(first));
        assertFalse(stations.addAll(List.of(first, second)));
        assertTrue(edits.isEmpty());
        assertThrows(NullPointerException.class, () -> stations.addAll(Arrays.asList(station("third"), null)));
        assertEquals(2, stations.size());
        assertTrue(edits.isEmpty());
        var readOnly = shop.asReadOnly().getStations();
        assertTrue(readOnly.contains(second.asReadOnly()));
        assertTrue(stations.contains(second.asReadOnly()));
        assertSame(second.asReadOnly(), readOnly.get(1));
        assertThrows(UnsupportedOperationException.class, () -> readOnly.remove(first.asReadOnly()));
        assertThrows(UnsupportedOperationException.class, () -> readOnly.move(0,1));
        assertThrows(UnsupportedOperationException.class, () -> {
            var it = readOnly.iterator();
            it.next();
            it.remove();
        });
        stations.move(1, 0);
        assertSame(second, stations.get(0));
        assertSame(shop, second.getWorkshop());
        assertEquals(1, edits.size());
        assertEquals("stations", edits.get(0).propertyName());
        second.setName("Calibration");
        assertTrue(stations.remove(second.asReadOnly()));
        assertNull(second.getWorkshop());
        assertSame(first, stations.get(0));
        assertSame(first.asReadOnly(), readOnly.get(0));
    }

    @Test public void graphValidationCountsRealReferencesOnBothSidesOfAReparent() {
        var oldShop = Workshop.newInstance();
        var newShop = Workshop.newInstance();
        assertEquals(0, oldShop.vmf().content().validate().getMultiplicityViolations().get(0).getActualCount());
        var a = station("one");
        oldShop.getStations().add(a);
        assertTrue(oldShop.vmf().content().validate().isValid());
        a.setWorkshop(newShop);
        assertTrue(oldShop.getStations().isEmpty());
        assertEquals(0, oldShop.vmf().content().validate().getMultiplicityViolations().get(0).getActualCount());
        assertSame(a, newShop.getStations().get(0));
        newShop.getStations().addAll(List.of(station("two"), station("three")));
        var issues = newShop.asReadOnly().vmf().content().validate().getMultiplicityViolations();
        assertEquals(1, issues.size());
        assertEquals(3, issues.get(0).getActualCount());
        assertEquals("stations", issues.get(0).getProperty().getName());
        a.setName(null);
        assertTrue(newShop.vmf().content().validate().getMultiplicityViolations().stream().anyMatch(issue ->
                issue.getObject() == a && issue.getProperty().getName().equals("name")));
    }

    @Test public void writableNonOwningSetsResolveHeldReadOnlyAliases() {
        var shop = Workshop.newInstance();
        var first = station("Inspection");
        var second = station("Inspection");
        var absent = station("Inspection");
        shop.getStations().addAll(List.of(first, second));
        shop.getFavorites().addAll(List.of(first, second));
        var alias = second.asReadOnly();
        assertTrue(shop.getFavorites().contains(alias));
        assertFalse(shop.getFavorites().remove(absent.asReadOnly()));
        var changes = new ArrayList<Change>();
        shop.vmf().changes().addPropertyListener("favorites", changes::add);
        assertTrue(shop.getFavorites().remove(alias));
        assertFalse(shop.getFavorites().remove(alias));
        assertSame(first, shop.getFavorites().iterator().next());
        assertSame(shop, second.getWorkshop());
        assertSame(second, changes.get(0).referenceSetChange().orElseThrow().removed().get(0));
        assertEquals(1, changes.size());

        var panel = Panel.newInstance();
        var link = StationLink.newInstance();
        assertTrue(panel.getLinks().add(link));
        var linkAlias = link.asReadOnly();
        assertTrue(panel.getLinks().contains(linkAlias));
        assertEquals(0, panel.getLinks().indexOf(linkAlias));
        assertTrue(panel.getLinks().remove(linkAlias));
        assertTrue(panel.getLinks().isEmpty() && link.getPanels().isEmpty());
        assertEquals(-1, panel.getLinks().indexOf(linkAlias));
    }

    @Test public void ordinaryOwningListRemovesTheFirstEqualValueThroughAHeldView() {
        var group = MixedGroup.newInstance();
        var first = station("same");
        var second = station("same");
        group.getMembers().addAll(List.of(first, second));
        var view = second.asReadOnly();
        assertEquals(first, second);
        assertTrue(group.getMembers().contains(view));
        assertEquals(0, group.getMembers().indexOf(view));
        assertTrue(group.getMembers().remove(view));
        assertSame(second, group.getMembers().get(0));
        assertSame(view, group.asReadOnly().getMembers().get(0));
    }

    @Test public void ordinaryReciprocalListUsesAReadOnlyAliasToClearItsOpposite() {
        var team = Team.newInstance();
        var member = Member.newInstance();
        member.getTeams().add(team);
        var view = team.asReadOnly();
        assertTrue(member.getTeams().contains(view));
        assertEquals(0, member.getTeams().indexOf(view));
        assertTrue(member.getTeams().remove(view));
        assertTrue(member.getTeams().isEmpty() && team.getMembers().isEmpty());
    }

    @Test public void instanceProjectionUsesIdentityAndKeepsOrdinaryReciprocalListLookup() {
        var team = Team.newInstance();
        var member = Member.newInstance();
        var view = team.asReadOnly();
        assertSame(view, team.asReadOnly());
        assertTrue(view.equals(view));
        assertFalse(view.equals(team));
        assertFalse(team.equals(view));
        assertEquals(System.identityHashCode(view), view.hashCode());

        var teams = member.getTeams();
        teams.addAll(Arrays.asList(team, null, team));
        var projected = member.asReadOnly().getTeams();
        assertSame(view, projected.get(0));
        assertTrue(projected.contains(view));
        assertFalse(projected.contains(team));
        assertEquals(2, projected.lastIndexOf(view));
        assertThrows(UnsupportedOperationException.class, () -> projected.remove(view));

        assertTrue(teams.containsAll(Arrays.asList(view, null)));
        assertEquals(0, teams.indexOf(view));
        assertEquals(2, teams.lastIndexOf(view));
        assertFalse(teams.removeAll(Collections.singletonList(view)));
        assertTrue(teams.remove(view));
        assertTrue(team.getMembers().contains(member));
        var tail = teams.subList(1, 2);
        assertEquals(0, tail.indexOf(view));
        assertTrue(tail.remove(view));
        assertTrue(team.getMembers().isEmpty());
        assertEquals(Collections.singletonList(null), teams);
    }

    @Test public void reflectionReportsThePublicCollectionAndReplacesExactly() {
        var shop = Workshop.newInstance();
        var property = shop.vmf().reflect().propertyByName("stations").orElseThrow();
        assertTrue(property.getType().isReferenceSetType());
        assertTrue(property.getType().isModelType());
        assertFalse(property.getType().isListType());
        assertEquals(OrderedRefSet.class.getName(), property.getType().getDescriptor().getName());
        assertEquals(Station.class.getName(), property.getType().getElementTypeName().orElseThrow());
        assertEquals(Multiplicity.of(1,2,true,true), property.getMultiplicity());
        assertSame(shop.getStations(), property.get());
        var a = station("a");
        var b = station("b");
        property.set(List.of(a, b));
        assertThrows(IllegalArgumentException.class, () -> property.set(List.of(b, b)));
        assertSame(a, shop.getStations().get(0));
        assertSame(b, shop.getStations().get(1));
        property.set(shop.getStations());
        assertSame(b, shop.getStations().get(1));
        property.unset();
        assertTrue(shop.getStations().isEmpty());
        assertNull(a.getWorkshop());
    }

    @Test public void clonePreservesAliasesBetweenContainmentAndUnorderedReferences() {
        Station a = station("a"), b = station("b");
        var shop = Workshop.newBuilder().withStations(a,b).withFavorites(b,a).build();
        var copy = shop.clone();
        assertNotSame(a, copy.getStations().get(0));
        assertTrue(copy.getFavorites().contains(copy.getStations().get(0)));
        assertTrue(copy.getFavorites().contains(copy.getStations().get(1)));
        assertFalse(copy.getFavorites().contains(a));
        assertSame(copy, copy.getStations().get(1).getWorkshop());
        assertSame(shop, a.getWorkshop());
        assertEquals(2, copy.vmf().content().stream(Station.class).count());
    }

    @Test public void unorderedContentsCompareIndependentlyOfIterationOrder() {
        Station a = station("a"), b = station("b");
        var first = Network.newBuilder().withNearby(a,b).build();
        var second = Network.newBuilder().withNearby(station("b"),station("a")).build();
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        var third = Network.newBuilder().withNearby(station("a"),station("a")).build();
        assertNotEquals(first, third);
        assertTrue(first.getNearby().contains(a));
        assertFalse(first.getNearby().contains(station("a")));
        assertEquals(2, third.getNearby().size());
    }

    @Test public void anOppositeSequenceCanRepeatAnEdgeWhileTheSetHoldsOneMember() {
        var team = Team.newInstance();
        var member = Member.newInstance();
        member.getTeams().addAll(List.of(team, team));
        assertEquals(2, member.getTeams().size());
        assertEquals(1, team.getMembers().size());
        assertTrue(team.getMembers().contains(member));
        member.getTeams().remove(0);
        assertTrue(team.getMembers().contains(member));
        member.getTeams().add(team);
        team.getMembers().remove(member);
        assertTrue(member.getTeams().isEmpty());
        assertTrue(team.getMembers().isEmpty());
    }

    @Test public void ordinaryOppositeCanUndoBulkRemovalWhenTheRemovedIdentityIsAlreadyPresent() {
        var member = Member.newInstance();
        var restored = Team.newInstance();
        var replaced = Team.newInstance();
        var teams = member.getTeams();
        teams.addAll(List.of(restored, replaced));

        var changes = member.vmf().changes();
        changes.start();
        assertTrue(teams.removeAll(List.of(restored)));
        changes.stop();
        assertEquals(1, changes.all().size());
        var removal = changes.all().get(0);
        teams.set(0, restored);

        assertTrue(removal.isUndoable());
        removal.undo();
        assertEquals(2, teams.size());
        assertSame(restored, teams.get(0));
        assertSame(restored, teams.get(1));
        assertEquals(1, restored.getMembers().size());
        assertTrue(restored.getMembers().contains(member));
        assertTrue(replaced.getMembers().isEmpty());
    }

    @Test public void twoUniqueOppositesAcceptEditsFromEitherEnd() {
        var panel = Panel.newInstance();
        var link = StationLink.newInstance();
        assertTrue(link.getPanels().add(panel));
        assertSame(link, panel.getLinks().get(0));
        assertFalse(panel.getLinks().add(link));
        assertEquals(1, link.getPanels().size());
        assertTrue(panel.getLinks().remove(link));
        assertTrue(link.getPanels().isEmpty());
    }

    @Test public void diffAndMergePreserveTheTargetOfAReferenceToAContainedStation() {
        var original = station("A");
        var source = Workshop.newBuilder().withStations(original).build();
        var desired = source.clone();
        var added = station("B");
        desired.getStations().add(added);
        desired.getFavorites().add(added);
        var changes = ModelDiff.diff(source, desired);
        ModelDiff.apply(source, changes);
        var copied = source.getStations().get(1);
        assertSame(original, source.getStations().get(0));
        assertNotSame(added, copied);
        assertTrue(source.getFavorites().contains(copied));
        assertSame(desired, added.getWorkshop());
        for (int i = changes.size() - 1; i >= 0; i--) {
            assertTrue(changes.get(i).isUndoable());
            changes.get(i).undo();
        }
        assertEquals(1, source.getStations().size());
        assertTrue(source.getFavorites().isEmpty());
        var merged = ModelDiff.merge(Workshop.newInstance(), desired);
        assertTrue(merged.getFavorites().contains(merged.getStations().get(1)));
        assertSame(desired, added.getWorkshop());
    }

    @Test public void referencesToLegacyContainmentKeepTheirAliasesDuringDiffAndMerge() {
        var desired = MixedGroup.newInstance();
        var touched = station("Inspection");
        desired.getMembers().add(touched);
        desired.getFavorites().add(touched);
        var source = MixedGroup.newInstance();
        var diff = ModelDiff.diff(source, desired);
        assertTrue(source.getMembers().isEmpty());
        ModelDiff.apply(source, diff);
        assertTrue(source.getFavorites().contains(source.getMembers().get(0)));
        assertNotSame(touched, source.getMembers().get(0));
        var merged = ModelDiff.merge(MixedGroup.newInstance(), desired);
        assertTrue(merged.getFavorites().contains(merged.getMembers().get(0)));
        assertSame(touched, desired.getMembers().get(0));
    }
}
