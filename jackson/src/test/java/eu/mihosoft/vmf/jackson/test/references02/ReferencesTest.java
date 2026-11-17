package eu.mihosoft.vmf.jackson.test.references02;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.Immutable;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VIterator;
import eu.mihosoft.vmf.runtime.core.VObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lossless round trips of non-containment references (miho/VMF#93): one-to-one, many-to-one and
 * many-to-many cross references, plain references and lists of them, forward and backward
 * references, references to the root and to a subtype, next to an immutable value.
 */
public class ReferencesTest {

    private static VMFJacksonModule module() {
        return VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true);
    }

    private static ObjectMapper jsonMapper() {
        return new ObjectMapper().registerModule(module());
    }

    /**
     * A project with every kind of reference. The lists of the many-to-many and many-to-one
     * references have orders that differ from the order in which a reader meets them.
     */
    static Project project() {
        Project project = Project.newBuilder().withName("p")
                .withVersion(Version.newBuilder().withMajor(1).withMinor(2).build()).build();

        Section s1 = Section.newBuilder().withName("s1").build();
        Section s2 = Section.newBuilder().withName("s2").build();
        project.getSections().add(s1);
        project.getSections().add(s2);

        Task t1 = Task.newBuilder().withName("t1").build();
        Task t2 = Task.newBuilder().withName("t2").build();
        Task t3 = Task.newBuilder().withName("t3").build();
        Milestone m1 = Milestone.newBuilder().withName("m1").withDate("2026-12-01").build();
        s1.getTasks().add(t1);
        s1.getTasks().add(t2);
        s2.getTasks().add(t3);
        s2.getTasks().add(m1);

        Tag a = Tag.newBuilder().withName("a").build();
        Tag b = Tag.newBuilder().withName("b").build();
        project.getTags().add(a);
        project.getTags().add(b);

        // many-to-many: t1.tags = [b, a], t2.tags = [a, b], a.tasks = [t2, t1, m1], b.tasks = [t1, t2]
        t1.getTags().add(b);
        t2.getTags().add(a);
        t1.getTags().add(a);
        t2.getTags().add(b);
        m1.getTags().add(a);

        // many-to-one, forward (t3 -> m1) and backward (m1 -> t1): m1.dependents = [t3, t2]
        t3.setDependsOn(m1);
        t2.setDependsOn(m1);
        m1.setDependsOn(t1);

        // one-to-one: the opposite points to the root
        project.setFocus(t2);

        // plain references: a subtype, and a list with order and duplicates
        project.setPinned(m1);
        project.getHistory().add(t3);
        project.getHistory().add(t1);
        project.getHistory().add(t3);

        return project;
    }

    /**
     * Renders the object graph: every object of the containment tree with its properties, model
     * objects as their index in the containment tree (so identity, order and duplicates count).
     */
    static String signature(VObject root) {
        List<VObject> nodes = new ArrayList<>();
        nodes.add(root);
        root.vmf().content().stream(VIterator.IterationStrategy.CONTAINMENT_TREE)
                .filter(n -> n != root).forEach(nodes::add);

        Map<Object, Integer> index = new IdentityHashMap<>();
        for (VObject n : nodes) {
            index.putIfAbsent(n, index.size());
        }

        StringBuilder sb = new StringBuilder();
        for (VObject n : nodes) {
            sb.append('#').append(index.get(n)).append(' ').append(n.vmf().reflect().type().getName());
            for (Property p : n.vmf().reflect().properties()) {
                sb.append(' ').append(p.getName()).append('=').append(render(p.get(), index));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String render(Object value, Map<Object, Integer> index) {
        if (value instanceof List) {
            return ((List<?>) value).stream().map(e -> render(e, index))
                    .collect(Collectors.joining(",", "[", "]"));
        }
        if (value instanceof VObject && !(value instanceof Immutable)) {
            Integer i = index.get(value);
            return i == null ? "<outside>" : "#" + i;
        }
        return String.valueOf(value);
    }

    private static void assertRoundTrip(Project project, Project copy) {
        String expected = signature(project);
        assertFalse(expected.contains("<outside>"), expected);
        assertEquals(expected, signature(copy));
    }

    @Test
    void jsonRoundTripRestoresAllReferences() throws Exception {
        Project project = project();
        ObjectMapper mapper = jsonMapper();

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(project);
        Project copy = mapper.readValue(json, Project.class);

        assertRoundTrip(project, copy);

        // a few of them spelled out
        Task t1 = copy.getSections().get(0).getTasks().get(0);
        Task t2 = copy.getSections().get(0).getTasks().get(1);
        Task t3 = copy.getSections().get(1).getTasks().get(0);
        Task m1 = copy.getSections().get(1).getTasks().get(1);
        Tag a = copy.getTags().get(0);
        Tag b = copy.getTags().get(1);

        assertSame(t2, copy.getFocus());
        assertSame(copy, t2.getFocusOf());
        assertTrue(m1 instanceof Milestone);
        assertSame(m1, copy.getPinned());
        assertEquals(List.of(t3, t1, t3), new ArrayList<>(copy.getHistory()));
        assertEquals(List.of(b, a), new ArrayList<>(t1.getTags()));
        assertEquals(List.of(t2, t1, m1), new ArrayList<>(a.getTasks()));
        assertEquals(List.of(t3, t2), new ArrayList<>(m1.getDependents()));
        assertSame(t1, m1.getDependsOn());
        assertEquals(1, copy.getVersion().getMajor());
        assertEquals(2, copy.getVersion().getMinor());

        // writing the copy gives the same JSON (ids are assigned in containment order)
        assertEquals(json, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(copy));
    }

    @Test
    void yamlRoundTripRestoresAllReferences() throws Exception {
        Project project = project();
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory()).registerModule(module());

        String yaml = mapper.writeValueAsString(project);
        Project copy = mapper.readValue(yaml, Project.class);

        assertRoundTrip(project, copy);
    }

    @Test
    void xmlRoundTripRestoresAllReferences() throws Exception {
        Project project = project();
        XmlMapper mapper = new XmlMapper();
        mapper.registerModule(module());

        String xml = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(project);
        assertTrue(xml.contains("<vmf-id>"), xml);
        Project copy = mapper.readValue(xml, Project.class);

        assertRoundTrip(project, copy);
    }

    /**
     * Ids can be any string, and a reference written on one side only restores its opposite.
     */
    @Test
    void handWrittenIdsAndOneSidedReferencesAreAccepted() throws Exception {
        String json = "{\n"
                + "  \"name\": \"p\",\n"
                + "  \"sections\": [ { \"name\": \"s\", \"tasks\": [\n"
                + "    { \"@vmf-id\": \"first\", \"name\": \"t1\" },\n"
                + "    { \"@vmf-id\": \"second\", \"name\": \"t2\", \"dependsOn\": \"first\" } ] } ],\n"
                + "  \"focus\": \"second\",\n"
                + "  \"history\": [ \"second\", \"first\", \"second\" ]\n"
                + "}";

        Project copy = jsonMapper().readValue(json, Project.class);

        Task t1 = copy.getSections().get(0).getTasks().get(0);
        Task t2 = copy.getSections().get(0).getTasks().get(1);
        assertSame(t1, t2.getDependsOn());
        assertEquals(List.of(t2), new ArrayList<>(t1.getDependents()));
        assertSame(t2, copy.getFocus());
        assertSame(copy, t2.getFocusOf());
        assertEquals(List.of(t2, t1, t2), new ArrayList<>(copy.getHistory()));
    }

    @Test
    void writingAReferenceToAnObjectOutsideTheTreeFails() {
        Project project = project();
        project.setPinned(Task.newBuilder().withName("not contained").build());

        JsonMappingException e = assertThrows(JsonMappingException.class,
                () -> jsonMapper().writeValueAsString(project));
        assertTrue(e.getMessage().contains("Project.pinned"), e.getMessage());
    }

    @Test
    void writingASubtreeWithReferencesLeavingItFails() {
        // t2 (in s1) is the focus of the project and depends on m1 (in s2)
        Section s1 = project().getSections().get(0);

        assertThrows(JsonMappingException.class, () -> jsonMapper().writeValueAsString(s1));
    }

    @Test
    void readingAnUnknownIdFails() {
        String json = "{ \"name\": \"p\", \"pinned\": \"99\" }";

        JsonMappingException e = assertThrows(JsonMappingException.class,
                () -> jsonMapper().readValue(json, Project.class));
        assertTrue(e.getMessage().contains("'99'"), e.getMessage());
    }

    @Test
    void readingADuplicateIdFails() {
        String json = "{ \"name\": \"p\", \"sections\": [ { \"name\": \"s\", \"tasks\": [\n"
                + "  { \"@vmf-id\": \"x\", \"name\": \"t1\" }, { \"@vmf-id\": \"x\", \"name\": \"t2\" } ] } ] }";

        IOException e = assertThrows(IOException.class, () -> jsonMapper().readValue(json, Project.class));
        assertTrue(causes(e).contains("Duplicate @vmf-id 'x'"), causes(e));
    }

    private static String causes(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
