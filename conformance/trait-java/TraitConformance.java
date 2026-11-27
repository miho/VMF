package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.conformance.traits.AssetData;
import eu.mihosoft.vmf.conformance.traits.BadgeShowcase;
import eu.mihosoft.vmf.conformance.traits.BadgeTraits;
import eu.mihosoft.vmf.conformance.traits.ColorToken;
import eu.mihosoft.vmf.conformance.traits.FixedBadge;
import eu.mihosoft.vmf.conformance.traits.FixedBadgeBase;
import eu.mihosoft.vmf.conformance.traits.Folder;
import eu.mihosoft.vmf.conformance.traits.GenericFolder;
import eu.mihosoft.vmf.conformance.traits.KeyRepairItem;
import eu.mihosoft.vmf.conformance.traits.KeyRepairWorkspace;
import eu.mihosoft.vmf.conformance.traits.KeyedFolder;
import eu.mihosoft.vmf.conformance.traits.KeyedNote;
import eu.mihosoft.vmf.conformance.traits.KeyedTask;
import eu.mihosoft.vmf.conformance.traits.MutableBadge;
import eu.mihosoft.vmf.conformance.traits.NamedItem;
import eu.mihosoft.vmf.conformance.traits.NamedOwnedNote;
import eu.mihosoft.vmf.conformance.traits.NamedOwnedTask;
import eu.mihosoft.vmf.conformance.traits.NamedRoleFolder;
import eu.mihosoft.vmf.conformance.traits.NoteItem;
import eu.mihosoft.vmf.conformance.traits.ReadOnlyBadgeTraits;
import eu.mihosoft.vmf.conformance.traits.RoleKey;
import eu.mihosoft.vmf.conformance.traits.RoleSetFolder;
import eu.mihosoft.vmf.conformance.traits.RoleSetNote;
import eu.mihosoft.vmf.conformance.traits.RoleSetTask;
import eu.mihosoft.vmf.conformance.traits.SourceTraits;
import eu.mihosoft.vmf.conformance.traits.TaskFolder;
import eu.mihosoft.vmf.conformance.traits.TaskItem;
import eu.mihosoft.vmf.conformance.traits.UnorderedKeyedExactFolder;
import eu.mihosoft.vmf.conformance.traits.UnorderedKeyedExactItem;
import eu.mihosoft.vmf.conformance.traits.UnorderedKeyedFolder;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

final class TraitConformance {
    private static final ObjectMapper REFERENCES = mapper(true);
    private static final ObjectMapper VALUES = mapper(false);

    private TraitConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        Path exchange = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(exchange);
            verifyMixedImmutableMerge();
            verifyMixedGetterBuilderImports();
            write(exchange);
            verify(exchange, "java");
            verifyOwnedGetterRole(exchange, "java", true);
            verifyNamedGetterRole(exchange, "java", true);
            verifyNamedGetterRoleTransfers();
            verifyKeyedGetterRole(exchange, "java", true);
            verifyKeyedGetterRoleEdits();
            verifyGenericOwnedGetterRole();
            verifyGetterRoleSets(exchange, "java", true);
            verifyGetterRoleSetEdits();
            verifyKeyedUnorderedSnapshots(exchange, "java", true);
            verifyKeyedUnorderedEdits();
            verifyKeyRepairFromOwnershipCallback();
            KeyedUnorderedJournalConformance.exchange(exchange, "java", true);
            NamedRoleJournalConformance.exchange(exchange, "java", true);
            System.out.println("Java verified shared getter-only traits, immutable inheritance, and both JSON profiles.");
        } else {
            verify(exchange, "csharp");
            verifyOwnedGetterRole(exchange, "csharp", false);
            verifyNamedGetterRole(exchange, "csharp", false);
            verifyKeyedGetterRole(exchange, "csharp", false);
            verifyGetterRoleSets(exchange, "csharp", false);
            verifyKeyedUnorderedSnapshots(exchange, "csharp", false);
            KeyedUnorderedJournalConformance.exchange(exchange, "csharp", false);
            NamedRoleJournalConformance.exchange(exchange, "csharp", false);
            System.out.println("Java restored the C# shared-trait graphs in both JSON profiles.");
        }
    }

    private static void verifyOwnedGetterRole(Path directory, String author, boolean writing) throws IOException {
        for (boolean references : List.of(false, true)) {
            var mapper = references ? REFERENCES : VALUES;
            var file = directory.resolve(author + "-owned-getter-role-" + (references ? "reference" : "values") + ".json");
            if (writing) {
                var left = Folder.newInstance();
                var right = Folder.newInstance();
                NamedItem task = TaskItem.newBuilder().withLabel("task").withTaskId("T").build();
                NamedItem note = NoteItem.newBuilder().withLabel("note").withMessage("M").build();
                left.getItems().add(task);
                left.setFirst(note);
                right.getItems().add(task);
                right.setFirst(note);
                right.setSelected(task);
                check(left.getItems().isEmpty() && left.getFirst() == null
                        && right.getItems().get(0) == task && right.getFirst() == note,
                        "Getter-only owning slots transfer the original mutable values");
                var view = right.asReadOnly();
                check(view.getItems().get(0) == task.asReadOnly() && view.getFirst() == note.asReadOnly()
                        && view.getSelected() == view.getItems().get(0), "The read-only owner projects both role slots and aliases");
                mapper.writeValue(file.toFile(), right);
            }
            var restored = mapper.readValue(file.toFile(), Folder.class);
            var first = restored.getFirst();
            var item = restored.getItems().get(0);
            check(first instanceof NoteItem && "M".equals(((NoteItem) first).getMessage())
                    && item instanceof TaskItem && "T".equals(((TaskItem) item).getTaskId()),
                    "The owning role stores and restores concrete mutable subtypes");
            check(restored.getSelected() == (references ? item : null),
                    "A by-value file omits the unopposed reference, and a reference file retains the alias");
        }
        if (writing) {
            check(VALUES.readValue("{}", Folder.class).getItems().isEmpty(), "An empty abstract role needs no discriminator");
            try {
                VALUES.readValue("{\"items\":[{\"label\":\"unknown\"}]}", Folder.class);
                throw new AssertionError("Expected an abstract role value without a discriminator to be rejected");
            } catch (IOException expected) {
                // No concrete subtype was nominated.
            }
        }
    }


    private static void verifyNamedGetterRole(Path directory, String author, boolean writing) throws IOException {
        for (boolean references : List.of(false, true)) {
            for (String format : List.of("json", "xml", "yaml")) {
                var mapper = switchMapper(format).registerModule(
                        VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                                .withModelWireTypeAliases(BadgeShowcase.class).withReferences(references));
                String suffix = references ? "reference" : "values";
                var file = directory.resolve(author + "-named-role-ownership-" + suffix + "." + format);
                if (writing) {
                    var root = NamedRoleFolder.newInstance();
                    var single = NamedOwnedTask.newBuilder().withLabel("single").withTaskNumber(71).build();
                    root.setSingle(single);
                    root.getOrdinary().add(NamedOwnedNote.newBuilder().withLabel("ordinary").withNote("L").build());
                    root.getOrdered().add(NamedOwnedTask.newBuilder().withLabel("ordered").withTaskNumber(72).build());
                    root.getUnordered().add(NamedOwnedNote.newBuilder().withLabel("unordered").withNote("S").build());
                    root.setSelected(single);
                    mapper.writeValue(file.toFile(), root);
                }
                var root = mapper.readValue(file.toFile(), NamedRoleFolder.class);
                var single = root.getSingle();
                var ordinary = root.getOrdinary().get(0);
                var ordered = root.getOrdered().get(0);
                var unordered = root.getUnordered().iterator().next();
                check(single instanceof NamedOwnedTask && ((NamedOwnedTask) single).getTaskNumber() == 71
                                && ordinary instanceof NamedOwnedNote && "L".equals(((NamedOwnedNote) ordinary).getNote())
                                && ordered instanceof NamedOwnedTask && ((NamedOwnedTask) ordered).getTaskNumber() == 72
                                && unordered instanceof NamedOwnedNote && "S".equals(((NamedOwnedNote) unordered).getNote()),
                        "All four named slots restore the original concrete child types and properties");
                check(single.getSingleOwner() == root && ordinary.getOrdinaryOwner() == root
                                && ordered.getOrderedOwner() == root && unordered.getUnorderedOwner() == root
                                && root.asReadOnly().getSingle().getSingleOwner() == root.asReadOnly()
                                && root.getSelected() == (references ? single : null),
                        "Hydration restores all named inverses and the reference-profile alias");
            }
        }
    }

    private static void verifyNamedGetterRoleTransfers() {
        var left = NamedRoleFolder.newInstance();
        var right = NamedRoleFolder.newInstance();
        var note = NamedOwnedNote.newBuilder().withLabel("note").build();
        var task = NamedOwnedTask.newBuilder().withTaskNumber(71).build();
        left.setSingle(note);
        check(note.getSingleOwner() == left && left.asReadOnly().getSingle().getSingleOwner() == left.asReadOnly(),
                "Singular attachment exposes the inverse from both views");
        note.setOrderedOwner(right);
        check(left.getSingle() == null && note.getSingleOwner() == null && right.getOrdered().get(0) == note,
                "A writable concrete setter transfers a getter-role child to ordered ownership");
        right.getOrdinary().add(note);
        check(right.getOrdered().isEmpty() && note.getOrderedOwner() == null && note.getOrdinaryOwner() == right,
                "The ordinary role list adopts from the ordered slot of the same owner");
        note.setUnorderedOwner(left);
        check(right.getOrdinary().isEmpty() && note.getOrdinaryOwner() == null && note.getUnorderedOwner() == left
                        && left.getUnordered().contains(note.asReadOnly()),
                "The unordered inverse retains the projected alias after transfer");
        task.setSingleOwner(right);
        check(right.getSingle() == task && task.getSingleOwner() == right,
                "A secondary concrete implementation shares the same named containment link");
        task.setSingleOwner(null);
        note.setUnorderedOwner(null);
        check(right.getSingle() == null && left.getUnordered().isEmpty(), "Clearing the inverse detaches both children");
    }

    private static void verifyKeyedGetterRole(Path directory, String author, boolean writing) throws IOException {
        for (boolean references : List.of(false, true)) {
            var mapper = references ? REFERENCES : VALUES;
            var file = directory.resolve(author + "-keyed-getter-role-" + (references ? "reference" : "values") + ".json");
            if (writing) {
                var task = KeyedTask.newBuilder().withTaskId("T").build();
                var note = KeyedNote.newBuilder().withKey("note").withMessage("M").build();
                var folder = KeyedFolder.newBuilder().withEntries(task, note).withSelected(task).build();
                mapper.writeValue(file.toFile(), folder);
            }
            var restored = mapper.readValue(file.toFile(), KeyedFolder.class);
            var task = restored.findEntriesByKey("reset").orElseThrow();
            var note = restored.findEntriesByKey("note").orElseThrow();
            check(task instanceof KeyedTask && "T".equals(((KeyedTask) task).getTaskId())
                            && note instanceof KeyedNote && "M".equals(((KeyedNote) note).getMessage()),
                    "Keyed role snapshots retain distinct concrete child types");
            check(restored.getSelected() == (references ? task : null)
                            && restored.asReadOnly().findEntriesByKey("reset").orElseThrow() == task.asReadOnly(),
                    "Keyed snapshots restore reference-aware aliases and project lookup to the retained role view");
        }
    }


    private static void verifyGetterRoleSets(Path directory, String author, boolean writing) throws IOException {
        for (boolean references : List.of(false, true)) {
            var mapper = references ? REFERENCES : VALUES;
            var file = directory.resolve(author + "-role-sets-" + (references ? "reference" : "values") + ".json");
            if (writing) {
                var root = RoleSetFolder.newInstance();
                root.getOrdered().addAll(List.of(RoleSetTask.newBuilder().withTaskId("ordered first").build(),
                        RoleSetNote.newBuilder().withMessage("ordered last").build()));
                root.getUnordered().add(RoleSetNote.newBuilder().withMessage("unordered").build());
                if (references) {
                    var first = roleKey("same");
                    var second = roleKey("same");
                    var third = roleKey("for ordered index");
                    var task = RoleSetTask.newBuilder().withTaskId("indexed task").withToken(first).build();
                    var note = RoleSetNote.newBuilder().withMessage("indexed note").withToken(second).build();
                    root.getIndexed().addAll(List.of(task, note));
                    root.getOrderedIndex().add(RoleSetTask.newBuilder().withTaskId("ordered indexed").withToken(third).build());
                    root.getKeys().addAll(List.of(first, second, third));
                    root.setSelected(task);
                    try {
                        VALUES.writeValueAsString(root);
                        throw new AssertionError("A reference key should require a reference-aware document");
                    } catch (JsonMappingException expected) {
                        check(expected.getMessage().contains("Model reference key"), "Typed values explain the profile boundary");
                    }
                }
                mapper.writeValue(file.toFile(), root);
            }
            var root = mapper.readValue(file.toFile(), RoleSetFolder.class);
            check(root.getOrdered().size() == 2
                            && root.getOrdered().get(0) instanceof RoleSetTask
                            && "ordered first".equals(((RoleSetTask) root.getOrdered().get(0)).getTaskId())
                            && root.getOrdered().get(1) instanceof RoleSetNote
                            && "ordered last".equals(((RoleSetNote) root.getOrdered().get(1)).getMessage()),
                    "Ordered ownership retains both concrete role types and order");
            check(root.getUnordered().size() == 1
                            && root.getUnordered().iterator().next() instanceof RoleSetNote
                            && "unordered".equals(((RoleSetNote) root.getUnordered().iterator().next()).getMessage()),
                    "Unordered ownership restores concrete membership");
            if (references) {
                var first = root.getKeys().get(0);
                var second = root.getKeys().get(1);
                var third = root.getKeys().get(2);
                var task = root.findIndexedByToken(first).orElseThrow();
                var note = root.findIndexedByToken(second).orElseThrow();
                var ordered = root.findOrderedIndexByToken(third).orElseThrow();
                var view = root.asReadOnly();
                check(first != second && first.equals(second)
                                && task instanceof RoleSetTask && "indexed task".equals(((RoleSetTask) task).getTaskId())
                                && note instanceof RoleSetNote && "indexed note".equals(((RoleSetNote) note).getMessage())
                                && ordered instanceof RoleSetTask && "ordered indexed".equals(((RoleSetTask) ordered).getTaskId())
                                && root.getSelected() == task
                                && view.findIndexedByToken(first.asReadOnly()).orElseThrow() == task.asReadOnly()
                                && view.findIndexedByToken(second.asReadOnly()).orElseThrow() == note.asReadOnly()
                                && view.findOrderedIndexByToken(third.asReadOnly()).orElseThrow() == ordered.asReadOnly(),
                        "Reference-aware ownership restores identity keys, concrete types, aliases and typed projected lookup");
            } else {
                check(root.getIndexed().isEmpty() && root.getOrderedIndex().isEmpty()
                                && root.getKeys().isEmpty() && root.getSelected() == null,
                        "Typed values have neither reference keys nor aliases in the unpopulated slots");
            }
        }
    }

    private static void verifyGetterRoleSetEdits() {
        var task = RoleSetTask.newInstance();
        var note = RoleSetNote.newInstance();
        var src = RoleSetFolder.newInstance();
        var dst = RoleSetFolder.newInstance();
        var view = dst.asReadOnly();
        check(src.getOrdered().add(task) && !src.getOrdered().add(task), "Ordered role sets reject duplicate identity");
        check(dst.getUnordered().add(task) && !dst.getUnordered().add(task) && src.getOrdered().isEmpty()
                        && dst.getUnordered().contains(task.asReadOnly()) && view.getUnordered().contains(task.asReadOnly()),
                "An unordered role set transfers by identity and recognizes the projected object");
        check(dst.getOrdered().add(task) && dst.getOrdered().add(note) && view.getUnordered().isEmpty()
                        && dst.getOrdered().contains(task.asReadOnly()) && view.getOrdered().get(0) == task.asReadOnly(),
                "An ordered role set recognizes the transferred object and its view");
        dst.getOrdered().move(0, 1);
        check(view.getOrdered().get(0) == note.asReadOnly() && view.getOrdered().get(1) == task.asReadOnly(),
                "The projected collection reflects a move");
        check(dst.getOrdered().remove(task.asReadOnly()) && dst.getUnordered().add(task)
                        && dst.getUnordered().remove(task.asReadOnly()) && view.getUnordered().isEmpty(),
                "Both owning set shapes accept a read-only alias for removal");

        var one = roleKey("same");
        var two = roleKey("same");
        var three = roleKey("other");
        task.setToken(one);
        note.setToken(two);
        src.getOrdered().add(task);
        var duplicate = RoleSetNote.newBuilder().withToken(one).build();
        var previous = RoleSetFolder.newInstance();
        previous.getUnordered().add(duplicate);
        rejects(IllegalArgumentException.class, () -> RoleSetFolder.newBuilder().withIndexed(task, duplicate).build());
        check(src.getOrdered().contains(task) && previous.getUnordered().contains(duplicate),
                "Invalid builder input preserves both prior owners");
        var keyed = RoleSetFolder.newBuilder().withIndexed(note, task).build();
        var projectedKeys = keyed.asReadOnly();
        check(src.getOrdered().isEmpty()
                        && one != two && one.equals(two)
                        && keyed.findIndexedByToken(one).orElseThrow() == task
                        && keyed.findIndexedByToken(two).orElseThrow() == note
                        && projectedKeys.findIndexedByToken(two.asReadOnly()).orElseThrow() == note.asReadOnly(),
                "Identity keys distinguish structurally equal raw and projected keys");
        rejects(IllegalArgumentException.class, () -> keyed.getIndexed().add(duplicate));
        rejects(IllegalArgumentException.class, () -> task.setToken(two));
        rejects(IllegalArgumentException.class, () -> task.setToken(null));
        check(previous.getUnordered().contains(duplicate) && task.getToken() == one,
                "Rejected key edits and transfers keep the original key and ownership");
        task.setToken(three);
        check(keyed.findIndexedByToken(one).isEmpty()
                        && projectedKeys.findIndexedByToken(three.asReadOnly()).orElseThrow() == task.asReadOnly(),
                "Typed key lookup follows the successful live change");
        var token = task.vmf().reflect().propertyByName("token").orElseThrow();
        rejects(IllegalArgumentException.class, token::unset);
        check(keyed.getOrderedIndex().add(task) && !keyed.getOrderedIndex().add(task)
                        && !keyed.getIndexed().contains(task)
                        && projectedKeys.findOrderedIndexByToken(three.asReadOnly()).orElseThrow() == task.asReadOnly(),
                "The ordered identity-keyed set uses the same managed projected key");
        check(keyed.getOrderedIndex().remove(task.asReadOnly()), "A projected alias removes the keyed role object");
        token.unset();
        check(task.getToken() == null && projectedKeys.findOrderedIndexByToken(three.asReadOnly()).isEmpty(),
                "A detached key can be reset");
    }

    private static RoleKey roleKey(String code) {
        return RoleKey.newBuilder().withCode(code).build();
    }


    private static void verifyKeyedUnorderedSnapshots(Path directory, String author, boolean writing) throws IOException {
        for (boolean references : List.of(false, true)) {
            for (String format : List.of("json", "xml", "yaml")) {
                ObjectMapper mapper = switchMapper(format).registerModule(
                        VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                                .withModelWireTypeAliases(BadgeShowcase.class).withReferences(references));
                String suffix = references ? "reference" : "values";
                var file = directory.resolve(author + "-keyed-unordered-" + suffix + "." + format);
                if (writing) mapper.writeValue(file.toFile(), keyedUnorderedSnapshot(references));
                var root = mapper.readValue(file.toFile(), UnorderedKeyedFolder.class);
                var tagged = root.findTaggedByKey("one").orElseThrow();
                check(tagged instanceof KeyedTask && "S".equals(((KeyedTask) tagged).getTaskId()),
                        "Both document profiles restore a scalar-keyed concrete owned subtype");
                if (references) {
                    var first = root.getKeys().get(0);
                    var second = root.getKeys().get(1);
                    var task = root.findItemsByToken(first).orElseThrow();
                    var note = root.findItemsByToken(second).orElseThrow();
                    var view = root.asReadOnly();
                    check(first != second && first.equals(second)
                                    && task instanceof RoleSetTask && "T".equals(((RoleSetTask) task).getTaskId())
                                    && note instanceof RoleSetNote && "N".equals(((RoleSetNote) note).getMessage())
                                    && root.getSelected() == task
                                    && view.findItemsByToken(first.asReadOnly()).orElseThrow() == task.asReadOnly()
                                    && view.findItemsByToken(second.asReadOnly()).orElseThrow() == note.asReadOnly(),
                            "Identity keys resolve even when their declarations follow the set, and projected aliases remain live");
                } else {
                    check(root.getItems().isEmpty() && root.getKeys().isEmpty() && root.getSelected() == null,
                            "The typed-by-value fixture omits mutable reference keys");
                }
            }
        }
        var plain = directory.resolve(author + "-keyed-unordered-plain.json");
        if (writing) {
            var exact = UnorderedKeyedExactFolder.newBuilder()
                    .withItems(UnorderedKeyedExactItem.newBuilder().withKey("one").withMessage("M").build()).build();
            VALUES.writeValue(plain.toFile(), exact);
        }
        check("M".equals(VALUES.readValue(plain.toFile(), UnorderedKeyedExactFolder.class)
                        .findItemsByKey("one").orElseThrow().getMessage()),
                "The metadata-free scalar-keyed set remains portable");
    }

    private static ObjectMapper switchMapper(String format) {
        switch (format) {
            case "xml": return new XmlMapper();
            case "yaml": return new YAMLMapper();
            default: return new ObjectMapper();
        }
    }

    private static UnorderedKeyedFolder keyedUnorderedSnapshot(boolean references) {
        var root = UnorderedKeyedFolder.newBuilder()
                .withTagged(KeyedTask.newBuilder().withKey("one").withTaskId("S").build()).build();
        if (references) {
            var first = roleKey("same");
            var second = roleKey("same");
            var task = RoleSetTask.newBuilder().withTaskId("T").withToken(first).build();
            var note = RoleSetNote.newBuilder().withMessage("N").withToken(second).build();
            root.getItems().addAll(List.of(task, note));
            root.getKeys().addAll(List.of(first, second));
            root.setSelected(task);
        }
        return root;
    }

    private static void verifyKeyedUnorderedEdits() throws IOException {
        var first = roleKey("same");
        var second = roleKey("same");
        var next = roleKey("next");
        var task = RoleSetTask.newBuilder().withToken(first).build();
        var note = RoleSetNote.newBuilder().withToken(second).build();
        var duplicate = RoleSetNote.newBuilder().withToken(first).build();
        var source = UnorderedKeyedFolder.newBuilder().withItems(task).build();
        var target = UnorderedKeyedFolder.newBuilder().withItems(note).build();
        var previous = RoleSetFolder.newBuilder().withUnordered(duplicate).build();
        var view = target.asReadOnly();
        var departures = new java.util.ArrayList<Boolean>();
        source.vmf().changes().addListener(edit -> {
            if (edit.propertyName().equals("items")) {
                check(edit.referenceSetChange().isPresent() && source.findItemsByToken(first).isEmpty()
                                && target.findItemsByToken(first).orElseThrow() == task,
                        "Pending transfer exposes the staged key in the destination");
                rejects(IllegalArgumentException.class, () -> task.setToken(second));
                departures.add(true);
            }
        });
        check(target.getItems().add(task) && !target.getItems().add(task) && source.getItems().isEmpty()
                        && departures.size() == 1 && first.equals(second)
                        && target.findItemsByToken(first).orElseThrow() == task
                        && target.findItemsByToken(second).orElseThrow() == note
                        && view.findItemsByToken(second.asReadOnly()).orElseThrow() == note.asReadOnly()
                        && view.getItems().contains(task.asReadOnly()),
                "Unordered keys follow transfer and compare model keys by identity");
        rejects(IllegalArgumentException.class, () -> target.getItems().add(duplicate));
        rejects(IllegalArgumentException.class, () -> task.setToken(second));
        rejects(IllegalArgumentException.class, () -> task.setToken(null));
        check(task.getToken() == first && previous.getUnordered().contains(duplicate),
                "Rejected writes preserve membership and the current key");

        // The JVM builder stages a colliding iterable, then rejects it during build.
        var staged = UnorderedKeyedFolder.newBuilder().withItems(task, duplicate);
        rejects(IllegalArgumentException.class, staged::build);
        check(target.getItems().contains(task) && previous.getUnordered().contains(duplicate),
                "An invalid staged set leaves both children with their previous owners");

        task.setToken(next);
        check(target.findItemsByToken(first).isEmpty()
                        && view.findItemsByToken(next.asReadOnly()).orElseThrow() == task.asReadOnly()
                        && target.getItems().remove(task.asReadOnly()) && view.findItemsByToken(next.asReadOnly()).isEmpty(),
                "The view observes successful key edits and accepts held aliases for removal");
        var appendTarget = UnorderedKeyedFolder.newBuilder().withItems(duplicate).build();
        var addable = RoleSetTask.newBuilder().withToken(next).build();
        var colliding = RoleSetTask.newBuilder().withToken(first).build();
        var appendSource = UnorderedKeyedFolder.newBuilder().withItems(colliding, addable).build();
        rejects(IllegalArgumentException.class, () -> UnorderedKeyedFolder.newBuilder().appendCollections(true)
                .withItems(colliding, addable).applyTo(appendTarget));
        check(appendTarget.getItems().size() == 1 && appendSource.getItems().size() == 2,
                "Colliding builder append leaves existing and proposed members in place");
        UnorderedKeyedFolder.newBuilder().appendCollections(true).withItems(addable).applyTo(appendTarget);
        check(appendTarget.findItemsByToken(next).orElseThrow() == addable && appendSource.getItems().size() == 1,
                "A valid builder append transfers only the new member");

        var exact = VALUES.readValue("{\"items\":[{\"key\":\"one\",\"message\":\"M\"}]}", UnorderedKeyedExactFolder.class);
        check("M".equals(exact.findItemsByKey("one").orElseThrow().getMessage()),
                "A concrete scalar-keyed set reads a metadata-free document");
        rejectsJson(UnorderedKeyedExactFolder.class, VALUES, "{\"items\":[{\"key\":\"same\"},{\"key\":\"same\"}]}");
        try {
            VALUES.writeValueAsString(keyedUnorderedSnapshot(true));
            throw new AssertionError("A populated mutable identity-keyed set requires reference mode");
        } catch (JsonMappingException expected) {
            check(expected.getMessage().contains("Model reference key"), "Typed values explain the profile boundary");
        }
        var invalid = REFERENCES.readTree(REFERENCES.writeValueAsString(keyedUnorderedSnapshot(true)));
        var items = invalid.get("items");
        ((com.fasterxml.jackson.databind.node.ObjectNode) items.get(1)).set("token", items.get(0).get("token"));
        rejectsJson(UnorderedKeyedFolder.class, REFERENCES, invalid.toString());
    }

    private static void rejectsJson(Class<?> target, ObjectMapper mapper, String document) throws IOException {
        try {
            mapper.readValue(document, target);
            throw new AssertionError("Expected duplicate keys to be rejected");
        } catch (JsonMappingException | IllegalArgumentException expected) {
            // Both checked wire errors and checked collection edits can report a duplicate key.
        }
    }


    private static void verifyKeyRepairFromOwnershipCallback() {
        var anchor = RoleKey.newInstance();
        var owner = KeyRepairWorkspace.newBuilder().withEarlier(anchor).build();
        var first = KeyRepairItem.newBuilder().withKey("same");
        var second = KeyRepairItem.newBuilder().withKey("same");
        var staged = KeyRepairWorkspace.newBuilder().withEarlier(anchor).withLater(first, second);
        var departures = new java.util.concurrent.atomic.AtomicInteger();
        owner.vmf().changes().addListener(edit -> {
            if (edit.propertyName().equals("earlier")) {
                check(owner.getEarlier().isEmpty(), "The earlier child has left its source");
                second.withKey("fixed");
                departures.incrementAndGet();
            }
        });

        var built = staged.build();
        check(departures.get() == 1 && built.getEarlier().get(0) == anchor
                        && built.findLaterByKey("same").orElseThrow() == built.getLater().get(0)
                        && built.findLaterByKey("fixed").orElseThrow() == built.getLater().get(1),
                "An earlier source callback can repair a later child's staged key");
    }

    private static void verifyKeyedGetterRoleEdits() {
        var source = KeyedFolder.newInstance();
        var destination = KeyedFolder.newInstance();
        var task = KeyedTask.newBuilder().withKey("task").build();
        var note = KeyedNote.newBuilder().withKey("note").build();
        var duplicate = KeyedNote.newBuilder().withKey("task").build();
        source.getEntries().add(task);
        destination.getEntries().add(note);
        var other = KeyedFolder.newInstance();
        other.getEntries().add(duplicate);
        var view = destination.asReadOnly();
        destination.getEntries().add(task);
        check(source.findEntriesByKey("task").isEmpty() && destination.findEntriesByKey("task").orElseThrow() == task
                && view.findEntriesByKey("task").orElseThrow() == task.asReadOnly(),
                "Lookup follows actual transfer and preserves the projected role identity");
        rejects(IllegalArgumentException.class, () -> destination.getEntries().add(duplicate));
        check(other.findEntriesByKey("task").orElseThrow() == duplicate, "Collision rejection preserves the preceding owner");
        rejects(IllegalArgumentException.class, () -> task.setKey("note"));
        rejects(IllegalArgumentException.class, () -> task.setKey(null));
        check("task".equals(task.getKey()), "Invalid key edits preserve the current value");
        task.setKey("renamed");
        check(view.findEntriesByKey("task").isEmpty() && view.findEntriesByKey("renamed").orElseThrow() == task.asReadOnly(),
                "Projected lookup follows a live key change");
        task.vmf().reflect().propertyByName("key").orElseThrow().unset();
        var noteKey = note.vmf().reflect().propertyByName("key").orElseThrow();
        rejects(IllegalArgumentException.class, noteKey::unset);
        check(destination.findEntriesByKey("reset").orElseThrow() == task
                && destination.findEntriesByKey("note").orElseThrow() == note, "Reset obeys the same attached key constraints");
        destination.getEntries().remove(note);
        noteKey.unset();
        check("reset".equals(note.getKey()), "Detached reset can return the declared default");
        var alias = view.findEntriesByKey("reset").orElseThrow();
        check(destination.getEntries().contains(alias) && destination.getEntries().indexOf(alias) == 0
                && view.getEntries().contains(alias) && destination.getEntries().remove(alias)
                && view.findEntriesByKey("reset").isEmpty(), "A held read-only alias can select and remove the keyed entry");
    }


    private static void verifyGenericOwnedGetterRole() throws IOException {
        var task = TaskItem.newBuilder().withLabel("generic").withCaption("base").withTaskId("T").build();
        GenericFolder<TaskItem> source = TaskFolder.newInstance();
        var destination = TaskFolder.newInstance();
        source.getEntries().add(task);
        destination.setHead(task);
        destination.setActive(task);
        check(source.getEntries().isEmpty() && destination.getHead() == task && "base".equals(((AssetData) task).getCaption()),
                "A bounded owning role transfers a concrete secondary-role child");
        check(destination.asReadOnly().getHead() == task.asReadOnly()
                        && destination.asReadOnly().getActive() == destination.asReadOnly().getHead(),
                "The generic owner projects the same concrete child and reference");
        var refs = REFERENCES.readValue(REFERENCES.writeValueAsString(destination), TaskFolder.class);
        var values = VALUES.readValue(VALUES.writeValueAsString(destination), TaskFolder.class);
        check(refs.getActive() == refs.getHead() && "base".equals(refs.getHead().getCaption())
                        && values.getActive() == null && "T".equals(values.getHead().getTaskId()),
                "The bounded owner retains concrete values and restores shared references only in reference-aware snapshots");
    }


    private static void verifyMixedGetterBuilderImports() {
        var accent = ColorToken.newBuilder().withCode("blue").build();
        var former = ColorToken.newBuilder().withCode("amber").build();
        var scores = new LinkedHashMap<String, Long>();
        scores.put("shared", 9L);
        scores.put("empty", null);
        BadgeTraits source = FixedBadge.newBuilder().withName(null).withAccent(accent).withSwatches(accent)
                .withMeasures(3, 4).withTags("source", null, "source").withScores(scores).build();
        for (boolean append : List.of(false, true)) {
            var target = MutableBadge.newBuilder().withName("old").withOrigin("target").withAccent(former)
                    .withSwatches(former).withMeasures(2).withTags("previous")
                    .withScores(java.util.Map.of("old", 1L, "shared", 2L)).build();
            var view = target.asReadOnly();
            var changes = new java.util.ArrayList<String>();
            target.vmf().changes().addListener(change -> changes.add(change.propertyName()), false);

            var staged = MutableBadge.newBuilder().withOrigin("staged").appendCollections(append).applyFrom(source);
            check(changes.isEmpty() && "old".equals(view.getName()) && view.getAccent() == former,
                    "Importing a mixed getter role has no effects on the destination");
            var built = staged.build();
            check(built.getName() == null && "staged".equals(built.getOrigin()) && built.getAccent() == accent
                    && built.getSwatches().iterator().next() == accent && built.getTags().equals(source.getTags())
                    && built.getMeasures().equals(source.getMeasures()) && built.getScores().equals(scores),
                    "Build copies the staged values and preserves the immutable reference alias");

            staged.applyTo(target);
            check(view.getName() == null && "staged".equals(view.getOrigin()) && view.getAccent() == accent
                    && view.getSwatches().contains(accent) && view.getSwatches().contains(former) == append,
                    "The live view sees scalar changes and reference membership using the original identities");
            check(view.getMeasures().equals(append ? List.of(2, 3, 4) : List.of(3, 4))
                    && view.getTags().equals(append ? Arrays.asList("previous", "source", null, "source") : source.getTags())
                    && Long.valueOf(9).equals(view.getScores().get("shared")) && view.getScores().containsKey("empty")
                    && view.getScores().get("empty") == null && view.getScores().containsKey("old") == append,
                    "ApplyTo follows append/replace and preserves duplicate, null and present-null entries");
            check(changes.equals(append ? List.of("accent", "measures", "name", "origin", "scores", "swatches", "tags")
                    : List.of("accent", "measures", "measures", "name", "origin", "scores", "scores",
                        "swatches", "swatches", "tags", "tags")), "Builder writes use the existing observable storage");
        }
    }

    private static void verifyMixedImmutableMerge() {
        var amber = ColorToken.newBuilder().withCode("amber").build();
        var blue = ColorToken.newBuilder().withCode("blue").build();
        var template = MutableBadge.newBuilder().withName("editable").withOrigin("lab").withTags("existing")
                .withAccent(amber).withSwatches(amber).build();
        var overlay = FixedBadge.newBuilder().withCategory("overlay-only").withName("fixed").withTags("append")
                .withAccent(blue).withSwatches(blue).build();
        var merged = (MutableBadge) ModelDiff.merge((VObject) template, overlay);
        check(merged != template && "fixed".equals(merged.getName()) && "lab".equals(merged.getOrigin())
                        && merged.getTags().equals(List.of("existing", "append")),
                "an immutable overlay replaces set scalars and appends values to the mutable template type");
        check(merged.getAccent() == blue && merged.getSwatches().contains(amber) && merged.getSwatches().contains(blue)
                        && merged.getSwatches().size() == 2 && merged.vmf().reflect().propertyByName("category").isEmpty(),
                "the merge shares immutable identities and ignores fields only the overlay declares");
        check("editable".equals(template.getName()) && template.getAccent() == amber
                        && template.getTags().equals(List.of("existing")) && overlay.getTags().equals(List.of("append")),
                "the inputs remain independent of the merged model");

        var cleared = (MutableBadge) ModelDiff.merge((VObject) template, FixedBadge.newBuilder().withOrigin(null).build());
        check("editable".equals(cleared.getName()) && cleared.getOrigin() == null,
                "immutable default scalars are omitted while an explicit null can replace a non-null value");

        check(ModelDiff.merge((VObject) overlay, MutableBadge.newInstance()) == overlay,
                "an immutable template returns its existing identity when no overlay value is applied");
        check(ModelDiff.merge(overlay, FixedBadge.newBuilder().build()) == overlay,
                "an immutable overlay with only defaults also makes no writes");
        rejects(UnsupportedOperationException.class, () -> ModelDiff.merge((VObject) overlay,
                MutableBadge.newBuilder().withSwatches(blue).build()));
        rejects(RuntimeException.class, () -> ModelDiff.merge(overlay, overlay));
        rejects(UnsupportedOperationException.class, () -> ModelDiff.merge((VObject) overlay,
                MutableBadge.newBuilder().withTags("append").build()));
    }

    private static void write(Path exchange) throws IOException {
        var blue = ColorToken.newBuilder().withCode("blue").build();
        var scores = new LinkedHashMap<String, Long>();
        scores.put("high", 9L);
        scores.put("empty", null);
        var fixed = FixedBadge.newBuilder().withCategory("archive").withOrigin(null)
                .withAccent(blue).withSwatches(blue).withTags("fixed", null, "fixed")
                .withMeasures(3, 4).withScores(scores).build();
        var mutable = MutableBadge.newBuilder().withName("in-progress").withOrigin("lab")
                .withAccent(blue).withSwatches(blue).withTags("draft", null, "draft")
                .withMeasures(1, 2).withScores(scores).build();
        verifyTraitEdits(mutable, fixed);

        var references = BadgeShowcase.newBuilder().withOwned(mutable).withSelected(fixed)
                .withChoices(Arrays.asList(fixed, null, mutable, fixed)).build();
        REFERENCES.writeValue(exchange.resolve("java-reference.json").toFile(), references);
        var values = BadgeShowcase.newBuilder().withSelected(fixed)
                .withChoices(Arrays.asList(fixed, null, fixed)).build();
        VALUES.writeValue(exchange.resolve("java-values.json").toFile(), values);
    }

    private static void verifyTraitEdits(MutableBadge mutable, FixedBadge fixed) {
        BadgeTraits mutableTrait = mutable;
        BadgeTraits fixedTrait = fixed;
        SourceTraits mutableSource = mutable;
        SourceTraits fixedSource = fixed;
        FixedBadgeBase base = fixed;
        ReadOnlyBadgeTraits view = mutable.asReadOnly();
        check("in-progress".equals(mutableTrait.getName()) && "lab".equals(mutableSource.getOrigin())
                && "draft".equals(fixedTrait.getName()) && fixedSource.getOrigin() == null
                && "archive".equals(base.getCategory()), "Shared getters, concrete base, builders, and defaults");
        check(!hasSetter(BadgeTraits.class) && !hasSetter(FixedBadge.class) && hasSetter(MutableBadge.class),
                "The mutable implementation generates a setter while the shared and frozen contracts expose a getter");
        check(!property(fixed, "name").isSet() && "draft".equals(property(fixed, "name").getDefault())
                && property(fixed, "origin").isSet() && "warehouse".equals(property(fixed, "origin").getDefault())
                && "display_name".equals(property(fixed, "name").annotationByKey("vmf:jackson:rename").orElseThrow().getValue()),
                "Immutable reflection retains inherited alias, default and explicit null override");

        mutable.setName("live");
        check("live".equals(view.getName()), "Concrete setter updates shared projection");
        property(mutable, "name").set("reflected");
        mutableTrait.getTags().add("live");
        mutableTrait.getScores().put("later", 5L);
        check("reflected".equals(view.getName()) && "live".equals(view.getTags().get(3))
                && Long.valueOf(5).equals(view.getScores().get("later")), "Reflective and collection edits update the live projection");

        rejects(RuntimeException.class, () -> property(fixed, "name").set("invalid"));
        rejects(RuntimeException.class, () -> property(view, "name").set("invalid"));
        rejects(UnsupportedOperationException.class, () -> fixedTrait.getTags().clear());
        rejects(UnsupportedOperationException.class, () -> fixedTrait.getMeasures().clear());
        rejects(UnsupportedOperationException.class, () -> fixedTrait.getScores().clear());
        rejects(UnsupportedOperationException.class, () -> fixedTrait.getSwatches().clear());
        rejects(UnsupportedOperationException.class, () -> view.getTags().clear());
        rejects(UnsupportedOperationException.class, () -> view.getScores().clear());
    }

    private static void verify(Path exchange, String author) throws IOException {
        var refFile = exchange.resolve(author + "-reference.json").toFile();
        var refWire = REFERENCES.readTree(refFile);
        check(refWire.findValuesAsText("@vmf-type").contains("fixed-badge")
                && !refWire.findValuesAsText("@vmf-id").isEmpty() && refWire.findValue("display_name") != null,
                "Reference profile uses polymorphic types, identities and inherited field name");
        var restored = REFERENCES.readValue(refFile, BadgeShowcase.class);
        check(restored.getOwned().size() == 1 && restored.getChoices().size() == 4
                && restored.getSelected() instanceof FixedBadge && restored.getChoices().get(1) == null,
                "Mixed reference graph restores owned mutable model, exact immutable subtype and null slot");
        var mutable = restored.getOwned().get(0);
        var fixed = (FixedBadge) restored.getSelected();
        check(restored.getChoices().get(0) == fixed && restored.getChoices().get(3) == fixed
                && restored.getChoices().get(2) == mutable && fixed.getAccent() == mutable.getAccent()
                && fixed.getSwatches().contains(fixed.getAccent()) && mutable.getSwatches().contains(mutable.getAccent()),
                "Trait-typed references retain immutable, mutable and nested immutable aliases");
        check("reflected".equals(mutable.getName()) && "lab".equals(mutable.getOrigin())
                && mutable.getTags().equals(Arrays.asList("draft", null, "draft", "live"))
                && mutable.getMeasures().equals(List.of(1, 2)) && Long.valueOf(5).equals(mutable.getScores().get("later")),
                "Mutable trait fields and collections round-trip");
        var view = restored.asReadOnly();
        check(view.getSelected() == fixed && view.getChoices().get(2) == view.getOwned().get(0)
                && view.getChoices().get(1) == null, "Mixed read-only projection preserves aliases and projects mutable objects");
        check(property(restored, "selected").getType().getDescriptor().getName().equals(BadgeTraits.class.getName()),
                "Reflection retains the shared contract as the declared reference type");
        verifyFixed(fixed);

        var valueFile = exchange.resolve(author + "-values.json").toFile();
        var valueWire = VALUES.readTree(valueFile);
        check(valueWire.findValue("@vmf-id") == null
                && valueWire.path("selected").path("@vmf-type").asText().equals("fixed-badge"),
                "By-value profile writes the subtype tag without graph identities");
        var byValue = VALUES.readValue(valueFile, BadgeShowcase.class);
        check(byValue.getSelected() instanceof FixedBadge && byValue.getChoices().size() == 3
                && byValue.getChoices().get(1) == null && byValue.getOwned().isEmpty(), "Immutable by-value shape and subtype");
        var inline = (FixedBadge) byValue.getSelected();
        verifyFixed(inline);
        check(inline != byValue.getChoices().get(0) && byValue.getChoices().get(0) != byValue.getChoices().get(2)
                && !inline.getSwatches().contains(inline.getAccent()), "Inline aliases are materialized as independent values");
    }

    private static void verifyFixed(FixedBadge fixed) {
        check("archive".equals(fixed.getCategory()) && "draft".equals(fixed.getName()) && fixed.getOrigin() == null
                && "blue".equals(fixed.getAccent().getCode()) && fixed.getSwatches().size() == 1
                && "blue".equals(fixed.getSwatches().iterator().next().getCode())
                && fixed.getTags().equals(Arrays.asList("fixed", null, "fixed"))
                && fixed.getMeasures().equals(List.of(3, 4)) && Long.valueOf(9).equals(fixed.getScores().get("high"))
                && fixed.getScores().containsKey("empty") && fixed.getScores().get("empty") == null,
                "Immutable base and trait fields, null/default distinction, and inherited collection shapes");
    }

    private static boolean hasSetter(Class<?> type) {
        return Arrays.stream(type.getMethods()).anyMatch(method -> method.getName().equals("setName"));
    }

    private static Property property(VObject owner, String name) {
        return owner.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static ObjectMapper mapper(boolean references) {
        return new ObjectMapper().registerModule(
                VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                        .withModelWireTypeAliases(BadgeShowcase.class).withReferences(references));
    }

    private static void rejects(Class<? extends RuntimeException> expected, Runnable edit) {
        try {
            edit.run();
        } catch (RuntimeException failure) {
            if (expected.isInstance(failure)) return;
            throw failure;
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
