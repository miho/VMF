package eu.mihosoft.vmf.conformance.referencedefaults;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;

public final class ProjectedConstructionBehavior implements DelegatedBehavior<HookedProjection> {
    private HookedProjection owner;
    private int calls;

    @Override
    public void setCaller(HookedProjection owner) {
        this.owner = owner;
    }

    public String describe(String prefix) {
        return prefix + ":" + (++calls) + ":" + owner.getQueue().size() + ":" + owner.getFavorites().size()
                + ":" + owner.getSummary().size() + ":" + owner.getBefore();
    }

    public void onHookedProjectionInstantiated() {
        var leaf = owner instanceof HookedProjectionLeaf ? (HookedProjectionLeaf) owner : null;
        ProjectedDefaultBindings.events.add(leaf == null ? "hook" : "hook-leaf");
        ProjectedQueueRoot queues = owner;
        ProjectedFavoriteRoot favorites = owner;
        check(queues.getQueue().isEmpty() && favorites.getFavorites().isEmpty()
                && owner.getNumbers().isEmpty() && queues.getSummary().isEmpty()
                && owner.getBefore() == null && owner.getPath() == null
                && queues.getZulu() == null && favorites.getAccent() == null
                && (leaf == null || leaf.getAhead() == null),
                "The concrete owner exposes all slots before providers run.");
        unavailable(queues, queues.asReadOnly(), "queue");
        unavailable(favorites, favorites.asReadOnly(), "favorites");
        check("hook:1:0:0:0:null".equals(owner.describe("hook")),
                "The hook dispatches the ordinary method on its own behavior before initialization.");

        queues.getQueue().add(ReferenceDefaultBindings.target("hook-queue"));
        favorites.getFavorites().add(ReferenceDefaultBindings.target("hook-favorite"));
        queues.getSummary().add(90);
        owner.getNumbers().add(80);
        owner.setBefore("hook");
        queues.setZulu("hook");
        if (leaf != null) leaf.setAhead("hook");
    }

    private static void unavailable(VObject alias, VObject readonlyAlias, String name) {
        var field = HookedProjectionConformance.field(alias, name);
        var projected = HookedProjectionConformance.field(readonlyAlias, name);
        check(field.getDefault() == null && field.isSet(), "Reflected fallback remains unavailable in the hook.");
        requireUnavailable(projected::getDefault);
        requireUnavailable(projected::isSet);
    }

    private static void requireUnavailable(Runnable read) {
        try {
            read.run();
        } catch (NullPointerException unavailable) {
            return;
        }
        throw new AssertionError("The projected fallback unexpectedly became available.");
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
