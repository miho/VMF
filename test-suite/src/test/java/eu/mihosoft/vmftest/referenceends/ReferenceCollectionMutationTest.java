package eu.mihosoft.vmftest.referenceends;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReferenceCollectionMutationTest {

    @Test public void invalidBatchLeavesTheOriginalContainerAndItsOppositeIntact() {
        var source = Workshop.newInstance();
        var destination = Workshop.newInstance();
        var station = Station.newInstance();
        source.getStations().add(station);
        var edits = new ArrayList<String>();
        source.vmf().changes().addListener(event -> edits.add(event.propertyName()));
        destination.vmf().changes().addListener(event -> edits.add(event.propertyName()));

        assertThrows(NullPointerException.class,
                () -> destination.getStations().addAll(Arrays.asList(station, null)));
        assertSame(source, station.getWorkshop());
        assertSame(station, source.getStations().get(0));
        assertTrue(destination.getStations().isEmpty());
        assertTrue(edits.isEmpty());
    }

    @Test public void replacingMembersFinishesEvenWhenAReorderObserverThrows() {
        var a = Station.newInstance();
        var b = Station.newInstance();
        var c = Station.newInstance();
        var d = Station.newInstance();
        var extra = Station.newInstance();
        var shop = Workshop.newBuilder().withStations(a, b, c, d).build();
        var failure = new AssertionError("list observer");
        var subscription = shop.vmf().changes().addListener(event -> {
            if (event.propertyName().equals("stations")) throw failure;
        });

        try {
            assertSame(failure, assertThrows(AssertionError.class, () ->
                    shop.vmf().reflect().propertyByName("stations").orElseThrow().set(List.of(c, extra, a))));
        } finally {
            subscription.unsubscribe();
        }

        assertEquals(3, shop.getStations().size());
        assertSame(c, shop.getStations().get(0));
        assertSame(extra, shop.getStations().get(1));
        assertSame(a, shop.getStations().get(2));
        assertSame(shop, extra.getWorkshop());
        assertNull(b.getWorkshop());
        assertNull(d.getWorkshop());
    }

    @Test public void nestedRemovalDuringBulkInsertionCannotLeaveAnUnpairedOpposite() {
        var owner = Panel.newInstance();
        var first = StationLink.newInstance();
        var second = StationLink.newInstance();
        first.vmf().changes().addListener(event -> {
            if (event.propertyName().equals("panels")) owner.getLinks().remove(second);
        });

        owner.getLinks().addAll(List.of(first,second));
        assertSame(first, owner.getLinks().get(0));
        assertTrue(first.getPanels().contains(owner));
        assertFalse(owner.getLinks().contains(second));
        assertFalse(second.getPanels().contains(owner));
    }

    @Test public void unorderedReplacementAndDuplicateReentryPreserveMembershipWithoutExtraChanges() {
        var a = Station.newInstance();
        var b = Station.newInstance();
        var shop = Workshop.newInstance();
        var edits = new ArrayList<String>();
        shop.vmf().changes().addListener(event -> {
            if (event.propertyName().equals("favorites")) {
                edits.add(event.propertyName());
                assertFalse(shop.getFavorites().add(a));
            }
        });

        shop.getFavorites().addAll(List.of(a,b));
        shop.vmf().reflect().propertyByName("favorites").orElseThrow().set(List.of(b,a));
        assertEquals(List.of("favorites"), edits);
        assertEquals(2, shop.getFavorites().size());
        assertTrue(shop.asReadOnly().getFavorites().contains(a));
        assertTrue(shop.getFavorites().contains(b.asReadOnly()));
    }
}
