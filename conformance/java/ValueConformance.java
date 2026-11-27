package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.values.ValueLabel;
import eu.mihosoft.vmf.conformance.values.ValueNode;
import eu.mihosoft.vmf.conformance.values.ValueSelection;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ValueConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private ValueConformance() {
    }

    public static void main(String[] args) throws IOException {
        Path snapshot = Path.of(args[1]);
        if (args[0].equals("read")) {
            verify(MAPPER.readValue(snapshot.toFile(), ValueSelection.class));
            System.out.println("Java restored the C# immutable graph.");
            return;
        }

        String fixture = Files.readString(Path.of(args[2]));
        verify(MAPPER.readValue(fixture, ValueSelection.class));
        reject(fixture.replace("\"next\": \"leaf\"", "\"next\": \"parent\""), "immutable cycle");
        reject(fixture.replace("\"label\": \"shared\"", "\"label\": \"parent\""), "wrong-type immutable ID");
        reject(fixture.replace("\"@vmf-id\": \"twin\"", "\"@vmf-id\": \"shared\""), "duplicate immutable ID");

        var shared = ValueLabel.newBuilder().withText("A").build();
        var twin = ValueLabel.newBuilder().withText("A").build();
        var leaf = ValueNode.newBuilder().withLabel(shared).build();
        var parent = ValueNode.newBuilder().withLabel(shared).withNext(leaf).build();
        var selection = ValueSelection.newBuilder()
                .withCurrent(shared).withFirst(parent).withNodes(parent, leaf)
                .withSecond(twin).withSelected(shared, twin).build();
        verify(MAPPER.readValue(MAPPER.writeValueAsString(selection), ValueSelection.class));
        Files.createDirectories(snapshot.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(snapshot.toFile(), selection.asReadOnly());
    }

    private static void verify(ValueSelection selection) {
        var shared = selection.getCurrent();
        var twin = selection.getSecond();
        var parent = selection.getFirst();
        var leaf = parent.getNext();
        check("A".equals(shared.getText()) && shared.equals(twin) && shared != twin,
                "Equal-valued but distinct immutable labels");
        check(selection.getSelected().size() == 2 && selection.getSelected().contains(shared)
                && selection.getSelected().contains(twin), "Unique set membership uses identity");
        check(selection.getNodes().size() == 2 && selection.getNodes().contains(parent)
                && selection.getNodes().contains(leaf), "Forward references resolve to the nested definitions");
        check(parent.getLabel() == shared && leaf.getLabel() == shared, "Nested shared immutable identity");
        check(selection.asReadOnly().getCurrent() == shared, "Immutable values are their own read-only views");
    }

    private static void reject(String json, String problem) throws IOException {
        try {
            MAPPER.readValue(json, ValueSelection.class);
        } catch (JsonProcessingException expected) {
            return;
        }
        throw new IllegalStateException("Accepted " + problem);
    }

    private static void check(boolean condition, String problem) {
        if (!condition) throw new IllegalStateException(problem);
    }
}
