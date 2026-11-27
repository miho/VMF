package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.example.workshops.Station;
import eu.mihosoft.vmf.example.workshops.Workshop;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkshopGraphConformance {
    private WorkshopGraphConformance() {
    }

    public static void main(String[] args) throws IOException {
        var steps = new ObjectMapper().readTree(new File(args[0]));
        var workshops = Map.of("left", Workshop.newInstance(), "right", Workshop.newInstance());
        var stations = new LinkedHashMap<String, Station>();
        var ids = new IdentityHashMap<Station, String>();
        for (String id : List.of("a", "b", "c", "d")) {
            var station = Station.newBuilder().withName(id).build();
            stations.put(id, station);
            ids.put(station, id);
        }

        int index = 0;
        for (JsonNode step : steps) {
            String label = "Step " + (++index) + " (" + step.path("op").asText() + ")";
            try {
                apply(step, workshops, stations);
                assertState(step, workshops, stations, ids);
            } catch (RuntimeException failure) {
                throw new IllegalStateException(label + ": " + failure.getMessage(), failure);
            }
        }
        System.out.println("Java matched all " + index + " shared Workshop operations.");
    }

    private static void apply(JsonNode step, Map<String, Workshop> workshops, Map<String, Station> stations) {
        var shop = workshops.get(step.path("shop").asText());
        var station = stations.get(step.path("item").asText());
        switch (step.path("op").asText()) {
            case "add":
                check(shop.getStations().add(station) == step.path("result").asBoolean(), "add result");
                break;
            case "remove":
                check(shop.getStations().remove(station) == step.path("result").asBoolean(), "remove result");
                break;
            case "favorite":
                check(shop.getFavorites().add(station) == step.path("result").asBoolean(), "favorite result");
                break;
            case "duplicate-insert":
                reject(IllegalArgumentException.class, () -> shop.getStations().add(0, station));
                break;
            case "invalid-move":
                reject(IndexOutOfBoundsException.class, () -> shop.getStations().move(0, shop.getStations().size()));
                break;
            case "move":
                shop.getStations().move(step.path("from").asInt(), step.path("to").asInt());
                break;
            case "set-parent":
                station.setWorkshop(shop);
                break;
            case "rename":
                var name = step.get("name");
                station.setName(name.isNull() ? null : name.asText());
                break;
            default:
                throw new IllegalStateException("Unknown workshop operation");
        }
    }

    private static void assertState(JsonNode step, Map<String, Workshop> workshops,
                                    Map<String, Station> stations, Map<Station, String> ids) {
        var left = workshops.get("left");
        var right = workshops.get("right");
        check(readIds(step, "left").equals(idsOf(left.getStations(), ids)), "left containment order");
        check(readIds(step, "right").equals(idsOf(right.getStations(), ids)), "right containment order");
        var favorites = idsOf(left.getFavorites(), ids);
        favorites.sort(String::compareTo);
        check(readIds(step, "favorites").equals(favorites) && right.getFavorites().isEmpty(), "favorite identities");
        check(left.vmf().content().validate().isValid() == step.path("leftValid").asBoolean(), "left validation");
        check(right.vmf().content().validate().isValid() == step.path("rightValid").asBoolean(), "right validation");
        for (var station : stations.values()) {
            var parent = left.getStations().contains(station) ? left
                    : right.getStations().contains(station) ? right : null;
            check(station.getWorkshop() == parent, "station opposite");
        }
    }

    private static List<String> idsOf(Iterable<Station> values, Map<Station, String> ids) {
        var result = new ArrayList<String>();
        for (var station : values) result.add(ids.get(station));
        return result;
    }

    private static List<String> readIds(JsonNode step, String field) {
        var result = new ArrayList<String>();
        step.path(field).forEach(id -> result.add(id.asText()));
        return result;
    }

    private static void reject(Class<? extends RuntimeException> type, Runnable edit) {
        try {
            edit.run();
        } catch (RuntimeException failure) {
            if (type.isInstance(failure)) return;
            throw failure;
        }
        throw new IllegalStateException("Accepted invalid edit");
    }

    private static void check(boolean condition, String problem) {
        if (!condition) throw new IllegalStateException(problem);
    }
}
