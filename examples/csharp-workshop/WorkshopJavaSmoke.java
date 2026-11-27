package eu.mihosoft.vmf.example.workshops.verification;

import eu.mihosoft.vmf.example.workshops.Station;
import eu.mihosoft.vmf.example.workshops.Workshop;
import eu.mihosoft.vmf.runtime.core.ValidationReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class WorkshopJavaSmoke {
    private WorkshopJavaSmoke() {
    }

    public static void main(String[] args) throws IOException {
        var left = Workshop.newInstance();
        var right = Workshop.newInstance();
        var first = station("first");
        var second = station("second");

        check(hasMultiplicityIssue(left.vmf().content().validate(), left, "stations"),
                "Empty containment must fail lower-bound validation");
        check(left.getStations().add(first) && left.getStations().add(second), "Insert both stations");
        boolean duplicate = left.getStations().add(first);
        check(!duplicate && left.getStations().size() == 2, "Adding the same instance is a no-op");

        left.getStations().move(1, 0);
        check(left.getStations().get(0) == second && left.getStations().get(1) == first,
                "Reordering must preserve both station identities");
        String ordered = left.getStations().get(0).getName() + "," + left.getStations().get(1).getName();

        second.setName(first.getName());
        check(left.getFavorites().add(first) && left.getFavorites().add(second),
                "Two stations with equal values can both be favorites");
        check(!left.getFavorites().add(first) && !left.getFavorites().contains(station(first.getName())),
                "Favorites must use identity");
        second.setName("second");

        first.setWorkshop(right);
        String reparent = left.getStations().size() + "," + right.getStations().size();
        boolean opposite = first.getWorkshop() == right && second.getWorkshop() == left;
        boolean identity = right.getStations().get(0) == first && left.getStations().get(0) == second;
        boolean favoriteAlias = left.getFavorites().contains(right.getStations().get(0));
        check(reparent.equals("1,1") && opposite && identity && favoriteAlias,
                "Assigning a container must move the same station and preserve its favorite reference");
        check(left.vmf().content().validate().isValid() && right.vmf().content().validate().isValid(),
                "The two workshops must be valid after the move");

        left.getStations().add(0, station("third"));
        left.getStations().add(station("fourth"));
        int overfull = left.getStations().size();
        check(overfull == 3 && hasMultiplicityIssue(left.vmf().content().validate(), left, "stations"),
                "Capacity is checked by validation after reference edits");

        var unnamed = Station.newInstance();
        right.getStations().add(unnamed);
        boolean missingName = hasMultiplicityIssue(right.vmf().content().validate(), unnamed, "name");
        check(missingName && hasMultiplicityIssue(unnamed.vmf().content().validate(), unnamed, "name"),
                "The required property must validate on the station and its containing workshop");
        unnamed.setName("");
        check(right.vmf().content().validate().isValid(), "An empty string counts as present");
        unnamed.setWorkshop(null);
        unnamed.setName(null);
        check(unnamed.getWorkshop() == null && right.getStations().size() == 1
                && right.getFavorites().add(unnamed) && right.vmf().content().validate().isValid(),
                "Validation follows containment and clearing the opposite detaches its object");

        String trace = "ordered=" + ordered + ";duplicate=" + duplicate + ";reparent=" + reparent
                + ";opposite=" + opposite + ";identity=" + identity + ";favoriteAlias=" + favoriteAlias
                + ";overfull=" + overfull + ";missingName=" + missingName;
        Files.writeString(Path.of(args[0]), trace);
    }

    private static Station station(String name) {
        return Station.newBuilder().withName(name).build();
    }

    private static boolean hasMultiplicityIssue(ValidationReport report, Object node, String property) {
        return report.getMultiplicityViolations().stream()
                .anyMatch(issue -> issue.getObject() == node && issue.getProperty().getName().equals(property));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
