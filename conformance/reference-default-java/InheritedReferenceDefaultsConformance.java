package eu.mihosoft.vmf.conformance.referencedefaults;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import java.io.IOException;
import java.util.List;

final class InheritedReferenceDefaultsConformance {
    private InheritedReferenceDefaultsConformance() { }

    static void verify(ObjectMapper mapper) throws IOException {
        InheritedReferenceDefaultBindings.events.clear();
        var base = InheritedSelection.newInstance();
        calls("queue", "saved");
        var middle = CuratedSelection.newInstance();
        calls("queue", "saved");
        var source = PublishedSelection.newInstance();
        calls("queue", "saved");
        var queue = field(source, "queue");
        var cache = members(queue);
        var original = source.getQueue().get(0);
        var saved = source.getSaved().iterator().next();
        check(cache.get(0) == original && cache.get(1) == source.getQueue().get(1)
                && original != members(field(base, "queue")).get(0)
                && original != members(field(middle, "queue")).get(0), "Providers capture fresh ordered values");
        check(members(field(source, "saved")).get(0) == saved && !queue.isSet(),
                "Inherited default membership also tracks the unordered seed");
        original.setLink(saved);
        check(members(field(source.asReadOnly(), "queue")).get(0) == original.asReadOnly(),
                "Read-only reflection projects the actual inherited seed");

        PublishedSelection deep = source.vmf().content().deepCopy();
        PublishedSelection shallow = source.vmf().content().shallowCopy();
        calls();
        check(deep.getQueue().get(0) != original && members(field(deep, "queue")).get(0) == deep.getQueue().get(0)
                && deep.getQueue().get(0).getLink() == deep.getSaved().iterator().next(),
                "Deep copy retains concrete shape and remaps linked fallback identities");
        check(members(field(shallow, "queue")) == cache && shallow.getQueue().get(0) == original
                && shallow.getQueue() != source.getQueue(), "Shallow copy shares captured identities and owns its membership");

        var builder = PublishedSelection.newBuilder().withQueue(List.of());
        calls("queue", "saved");
        var first = builder.build();
        var second = builder.build();
        calls();
        check(first.getQueue().isEmpty() && second.getQueue().isEmpty()
                && members(field(first, "queue")) == members(field(second, "queue")),
                "Builder stores explicit empty membership and reuses the fallback across builds");

        builder.appendCollections(false).applyFrom(base).applyTo(source);
        calls();
        check(source.getQueue().get(0) == base.getQueue().get(0) && source.getQueue().get(1) == base.getQueue().get(1)
                && source.getSaved().iterator().next() == base.getSaved().iterator().next()
                && queue.isSet() && members(queue) == cache && source.getLabel().equals("curated"),
                "Typed import accepts the base and preserves the target fallback and derived literal");
        queue.unset();
        check(source.getQueue().get(0) == original, "Reset restores the target's seed");
        calls();

        var absent = mapper.readValue("{}", PublishedSelection.class);
        snapshotCalls();
        check(absent.getQueue().size() == 2 && absent.getSaved().size() == 1 && !field(absent, "queue").isSet(),
                "Absent snapshot fields use fresh inherited defaults");
        var empty = mapper.readValue("{\"queue\":[],\"saved\":[]}", PublishedSelection.class);
        snapshotCalls();
        check(empty.getQueue().isEmpty() && empty.getSaved().isEmpty() && field(empty, "queue").isSet()
                && members(field(empty, "queue")).size() == 2, "Explicit empty fields retain their separate cache");
        System.out.println("Java verified inherited reference provider order, builder/copy identities, and snapshot presence.");
    }

    private static Property field(VObject owner, String name) {
        return owner.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static List<?> members(Property field) {
        return (List<?>) field.getDefault();
    }

    private static void snapshotCalls() {
        // Jackson can build metadata prototypes before constructing the returned model.
        var events = InheritedReferenceDefaultBindings.events;
        check(!events.isEmpty() && events.size() % 2 == 0, "Snapshot provider order: " + events);
        for (int index = 0; index < events.size(); index += 2) {
            check("queue".equals(events.get(index)) && "saved".equals(events.get(index + 1)),
                    "Snapshot provider order: " + events);
        }
        events.clear();
    }

    private static void calls(String... expected) {
        check(InheritedReferenceDefaultBindings.events.equals(List.of(expected)),
                "Provider order: " + InheritedReferenceDefaultBindings.events);
        InheritedReferenceDefaultBindings.events.clear();
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
