package eu.mihosoft.vmf.runtime.core.journal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.*;

/** Keeps a definition with its original edit unless an earlier edit first needs it. */
final class JournalCreationPlacement {
    private JournalCreationPlacement() {}

    static List<Operation> order(List<Operation> operations) {
        int lastDefinition = operations.size() - 1;
        while (lastDefinition > 0 && operations.get(lastDefinition).created().isEmpty()) lastDefinition--;
        if (lastDefinition <= 0) return operations;
        Map<String, Placement> definitions = new HashMap<>();
        for (int index = 0; index < operations.size(); index++) {
            for (var creation : operations.get(index).created()) {
                definitions.put(creation.id(), new Placement(creation, index));
            }
        }
        List<Operation> result = null;
        for (int index = 0; index < operations.size(); index++) {
            var operation = operations.get(index);
            List<Creation> earlier = new ArrayList<>();
            requireValues(operation.before(), index, definitions, earlier);
            requireValues(operation.after(), index, definitions, earlier);
            int originalCount = 0;
            for (var creation : operation.created()) {
                if (definitions.get(creation.id()).position == index) {
                    requireMembers(creation, index, definitions, earlier);
                    originalCount++;
                }
            }
            if (!earlier.isEmpty() || originalCount != operation.created().size()) {
                for (var creation : operation.created()) {
                    if (definitions.get(creation.id()).position == index) earlier.add(creation);
                }
                if (result == null) result = new ArrayList<>(operations);
                result.set(index, new Operation(operation.kind(), operation.target(), operation.property(), operation.indices(),
                        operation.before(), operation.after(), earlier));
            }
        }
        return result == null ? operations : result;
    }

    private static void requireMembers(Creation creation, int index, Map<String, Placement> definitions, List<Creation> earlier) {
        for (var values : creation.properties().values()) requireValues(values, index, definitions, earlier);
    }

    private static void requireValues(List<Value> values, int index, Map<String, Placement> definitions, List<Creation> earlier) {
        for (var value : values) {
            if (!value.kind().isReference()) continue;
            var placement = definitions.get(value.text());
            if (placement == null || placement.position <= index) continue;
            placement.position = index;
            requireMembers(placement.creation, index, definitions, earlier);
            earlier.add(placement.creation);
        }
    }

    private static final class Placement {
        final Creation creation;
        int position;

        Placement(Creation creation, int position) {
            this.creation = creation;
            this.position = position;
        }
    }
}
