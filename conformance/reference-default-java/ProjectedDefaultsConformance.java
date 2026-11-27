package eu.mihosoft.vmf.conformance.referencedefaults;

import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import java.util.List;

final class ProjectedDefaultsConformance {
    private ProjectedDefaultsConformance() { }

    public static void main(String[] arguments) {
        verify();
    }

    static void verify() {
        ProjectedDefaultBindings.events.clear();
        var primary = ProjectionPrimary.newInstance();
        calls("before", "numbers", "path");
        var queueRole = ProjectedQueueRole.newInstance();
        calls("queue", "summary", "zulu");
        var favoriteRole = ProjectedFavoriteRole.newInstance();
        calls("accent", "favorites");

        var calibration = ProjectedCalibrationRole.newInstance();
        calls("marks", "prunes");

        var owner = ProjectedDefaultsLeaf.newInstance();
        calls("accent", "ahead", "before", "favorites", "marks", "numbers", "path", "prunes", "queue", "summary", "zulu");
        ProjectedQueueRoot queues = owner;
        ProjectedFavoriteRoot favorites = owner;
        var queueSeed = owner.getQueue().get(0);
        var favoriteSeed = owner.getFavorites().iterator().next();
        check(defaults(queues, "queue").get(0) == queueSeed
                        && defaults(favorites, "favorites").get(0) == favoriteSeed
                        && defaults(queues.asReadOnly(), "queue").get(0) == queueSeed.asReadOnly()
                        && defaults(favorites.asReadOnly(), "favorites").get(0) == favoriteSeed.asReadOnly(),
                "Each secondary alias sees its member and the same concrete owner's fallback.");
        check(defaults(queueRole, "queue").get(0) != queueSeed
                        && defaults(favoriteRole, "favorites").get(0) != favoriteSeed
                        && owner.getNumbers().equals(primary.getNumbers()) && owner.getSummary().equals(List.of(4, 5))
                        && owner.getMarks().equals(calibration.getMarks()) && owner.getPrunes().equals(calibration.getPrunes())
                        && "before".equals(owner.getBefore()) && "path".equals(owner.getPath())
                        && "concrete".equals(owner.getLabel()) && "ahead".equals(owner.getAhead()),
                "Standalone role caches remain independent and every projected provider is evaluated.");

        var builder = ProjectedDefaultsLeaf.newBuilder();
        calls("favorites", "queue", "accent", "ahead", "before", "marks", "numbers", "path", "prunes", "summary", "zulu");
        var first = builder.build();
        calls("accent", "ahead", "before", "marks", "numbers", "path", "prunes", "summary", "zulu");
        var empty = builder.withQueue(List.of()).withFavorites(List.of()).withNotes("configured").withRank(18).build();
        calls("accent", "ahead", "before", "marks", "numbers", "path", "prunes", "summary", "zulu");
        check(empty.getQueue().isEmpty() && empty.getFavorites().isEmpty()
                        && defaults(empty, "queue") == defaults(first, "queue")
                        && defaults(empty, "favorites") == defaults(first, "favorites")
                        && "configured".equals(empty.getNotes()) && empty.getRank() == 18,
                "Each builder cache survives explicit replacement and multiple builds.");
        field(empty, "favorites").unset();
        check(empty.getFavorites().iterator().next() == first.getFavorites().iterator().next(),
                "Projected reset uses its captured fallback identity.");
        calls();

        ProjectedDefaultsLeaf deep = owner.vmf().content().deepCopy();
        calls("accent", "ahead", "before", "marks", "numbers", "path", "prunes", "summary", "zulu");
        ProjectedDefaultsLeaf shallow = owner.vmf().content().shallowCopy();
        calls("accent", "ahead", "before", "marks", "numbers", "path", "prunes", "summary", "zulu");
        check(deep.getQueue().get(0) != queueSeed && deep.getFavorites().iterator().next() != favoriteSeed
                        && defaults(deep, "queue").get(0) == deep.getQueue().get(0)
                        && defaults(deep, "favorites").get(0) == deep.getFavorites().iterator().next()
                        && shallow.getQueue().get(0) == queueSeed && shallow.getFavorites().iterator().next() == favoriteSeed
                        && defaults(shallow, "queue") == defaults(owner, "queue")
                        && defaults(shallow, "favorites") == defaults(owner, "favorites"),
                "Projection preserves the appropriate deep and shallow cache identities.");
        System.out.println("Java verified provider order and fallback identities across primary and secondary concrete inheritance roles.");
    }

    private static Property field(VObject owner, String name) {
        return owner.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static List<?> defaults(VObject owner, String name) {
        return (List<?>) field(owner, name).getDefault();
    }

    private static void calls(String... expected) {
        check(ProjectedDefaultBindings.events.equals(List.of(expected)), "Providers called: " + ProjectedDefaultBindings.events);
        ProjectedDefaultBindings.events.clear();
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
