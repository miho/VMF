package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.genericbounds.DirectLabelSelection;
import eu.mihosoft.vmf.conformance.genericbounds.LabelBook;
import eu.mihosoft.vmf.conformance.genericbounds.LabelSlot;
import eu.mihosoft.vmf.conformance.genericbounds.LabelView;
import eu.mihosoft.vmf.conformance.genericbounds.MutableQualifiedLabel;
import eu.mihosoft.vmf.conformance.genericbounds.MutableStringQualifiedView;
import eu.mihosoft.vmf.conformance.genericbounds.NamedAppliedSelection;
import eu.mihosoft.vmf.conformance.genericbounds.NamedMutableAppliedSelection;
import eu.mihosoft.vmf.conformance.genericbounds.PermanentLabel;
import eu.mihosoft.vmf.conformance.genericbounds.PermanentQualifiedLabel;
import eu.mihosoft.vmf.conformance.genericbounds.PermanentSlot;
import eu.mihosoft.vmf.conformance.genericbounds.QualifiedView;
import eu.mihosoft.vmf.conformance.genericbounds.ReadOnlyLabelSlot;
import eu.mihosoft.vmf.conformance.genericbounds.ReadOnlyLabelView;
import eu.mihosoft.vmf.conformance.genericbounds.ReadOnlyQualifiedView;
import eu.mihosoft.vmf.conformance.genericbounds.ReadOnlyStringQualifiedView;
import eu.mihosoft.vmf.conformance.genericbounds.ReadOnlyWorkingLabel;
import eu.mihosoft.vmf.conformance.genericbounds.StringQualifiedView;
import eu.mihosoft.vmf.conformance.genericbounds.WorkingLabel;
import eu.mihosoft.vmf.conformance.genericbounds.WorkingQualifiedLabel;
import eu.mihosoft.vmf.conformance.genericbounds.WorkingSlot;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.Type;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class GenericBoundConformance {
    private GenericBoundConformance() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
        var directory = Path.of(args[1]);
        boolean write = args[0].equals("write");
        var output = directory.resolve("java-generic-bounds.json").toFile();
        if (write) {
            Files.createDirectories(directory);
            var book = specimen();
            verify(book);
            mapper.writeValue(output, book);
        } else {
            output = directory.resolve("csharp-generic-bounds.json").toFile();
        }
        check(mapper.readTree(output).findValue("active_label") != null, "inherited wire name");
        verify(mapper.readValue(output, LabelBook.class));
        for (boolean activeIsPermanent : new boolean[] {false, true}) {
            var direct = directory.resolve((write ? "java" : "csharp") + "-direct-label-"
                    + (activeIsPermanent ? "permanent" : "working") + ".json").toFile();
            if (write) {
                var selection = directSpecimen(activeIsPermanent);
                verifyDirect(selection, activeIsPermanent);
                mapper.writeValue(direct, selection);
            }
            check(mapper.readTree(direct).has("active_label"), "Direct argument uses the inherited wire name");
            verifyDirect(mapper.readValue(direct, DirectLabelSelection.class), activeIsPermanent);
        }
        var applied = directory.resolve((write ? "java" : "csharp") + "-named-applied-label.json").toFile();
        if (write) {
            var selection = namedAppliedSpecimen();
            verifyNamedApplied(selection);
            mapper.writeValue(applied, selection);
        }
        verifyNamedApplied(mapper.readValue(applied, NamedAppliedSelection.class));
        verifyGenericRoleMetadata();
        verifyMutableOnlyApplication();
        verifyTraitReferenceImports();
        System.out.println("Java verified closed getter-only bounds, live and immutable aliases, and reference snapshots.");
    }

    private static void verifyGenericRoleMetadata() {
        var role = QualifiedView.type();
        var working = WorkingQualifiedLabel.newBuilder().withQualifier("working").build();
        var permanent = PermanentQualifiedLabel.newBuilder().withQualifier("permanent").build();
        var declarations = WorkingQualifiedLabel.type().reflect().allTypes().stream()
                .filter(type -> type.isModelSubtypeOf(role)).map(Type::getName).sorted().collect(Collectors.toList());
        check(declarations.equals(List.of(MutableQualifiedLabel.class.getName(), MutableStringQualifiedView.class.getName(),
                PermanentQualifiedLabel.class.getName(), StringQualifiedView.class.getName(), WorkingQualifiedLabel.class.getName())),
                "The open generic role discovers its concrete and interface-only specializations");
        for (var value : List.<VObject>of(working, working.asReadOnly(), permanent, permanent.asReadOnly())) {
            var reflection = value.vmf().reflect();
            var qualifier = reflection.propertyByName("qualifier").orElseThrow();
            check(reflection.type().isModelSubtypeOf(role)
                    && qualifier.getType().getDescriptor().getName().equals(String.class.getName())
                    && qualifier.get().equals(value instanceof PermanentQualifiedLabel ? "permanent" : "working")
                    && qualifier.annotationByKey("vmf:property:containment-info").orElseThrow().getValue().equals("none"),
                    "Mutable and immutable role implementations expose the closed inherited field on their views");
        }
    }


    private static LabelBook specimen() {
        var permanent = PermanentLabel.newBuilder().withText("stable").build();
        var twin = PermanentLabel.newBuilder().withText("stable").build();
        var working = WorkingLabel.newBuilder().withText("live").withRevision("draft").build();
        var archived = PermanentSlot.newBuilder().withCurrent(permanent).withChoices(permanent, twin)
                .withHistory(Arrays.asList(permanent, null, permanent)).build();
        var active = WorkingSlot.newBuilder().withCurrent(working).withChoices(working)
                .withHistory(Arrays.asList(working, null, working)).build();
        return LabelBook.newBuilder().withPermanent(archived).withWorking(active).withOwned(working).build();
    }

    private static DirectLabelSelection directSpecimen(boolean activeIsPermanent) {
        var working = WorkingLabel.newBuilder().withText("live").build();
        var stable = PermanentLabel.newBuilder().withText("stable").build();
        var twin = PermanentLabel.newBuilder().withText("stable").build();
        return DirectLabelSelection.newBuilder().withOwned(working).withChoices(working, stable, twin)
                .withHistory(Arrays.asList(working, null, stable, working))
                .withCurrent(activeIsPermanent ? stable : working).build();
    }

    private static void verifyDirect(DirectLabelSelection selection, boolean activeIsPermanent) {
        LabelSlot<LabelView> applied = selection;
        ReadOnlyLabelSlot<ReadOnlyLabelView> projected = selection.asReadOnly();
        var working = selection.getOwned().get(0);
        var stable = applied.getHistory().get(2);
        check(stable instanceof PermanentLabel && applied.getHistory().get(0) == working
                && applied.getHistory().get(1) == null && applied.getHistory().get(3) == working,
                "Nullable positional copies preserve mutable and immutable aliases");
        check(applied.getChoices().size() == 3 && applied.getChoices().contains(working) && applied.getChoices().contains(stable)
                && applied.getChoices().stream().anyMatch(label -> label != stable && label.equals(stable)),
                "An identity set holds mutable, immutable, and distinct value-equal members");
        check(applied.getCurrent() == (activeIsPermanent ? stable : working)
                && projected.getCurrent() == (activeIsPermanent ? stable : working.asReadOnly()),
                "Scalar and projected references use the same graph target");
        check(projected.getHistory().get(0) == working.asReadOnly() && projected.getHistory().get(2) == stable
                && projected.getChoices().contains(working.asReadOnly()) && projected.getChoices().contains(stable),
                "The read-only projection shares the live mutable view and immutable objects");
        working.setText("edited");
        check(projected.getHistory().get(0).getText().equals("edited"), "The getter-only view stays live");
        working.setText("live");
        check(DirectLabelSelection.type().superTypes().get(0).getDescriptor().getArguments().get(0).getName()
                .equals(LabelView.class.getName()), "Reflection retains the exact declared trait argument");
    }

    private static NamedAppliedSelection namedAppliedSpecimen() {
        var working = WorkingQualifiedLabel.newBuilder().withText("mutable").withQualifier("m").build();
        var permanent = PermanentQualifiedLabel.newBuilder().withText("immutable").withQualifier("i").build();
        return NamedAppliedSelection.newBuilder().withOwned(working).withCurrent(working)
                .withChoices(working, permanent).withHistory(working, permanent).build();
    }

    private static void verifyNamedApplied(NamedAppliedSelection selection) {
        LabelSlot<StringQualifiedView> applied = selection;
        ReadOnlyLabelSlot<ReadOnlyStringQualifiedView> view = selection.asReadOnly();
        ReadOnlyQualifiedView<String> closedGetter = view.getCurrent();
        var working = selection.getOwned().get(0);
        var permanent = applied.getHistory().get(1);
        check(applied.getCurrent() == working && closedGetter == working.asReadOnly()
                && applied.getHistory().get(0) == working, "The named application retains ownership and scalar identity");
        check(permanent instanceof PermanentQualifiedLabel && applied.getChoices().contains(working)
                && applied.getChoices().contains(permanent) && view.getHistory().get(1) == permanent
                && "i".equals(permanent.getQualifier()), "The inline immutable instance and its closed scalar are shared");
        working.setQualifier("edited");
        check("edited".equals(closedGetter.getQualifier()), "The String-valued read-only getter stays live");
        working.setQualifier("m");
    }

    private static void verifyTraitReferenceImports() {
        var working = WorkingLabel.newBuilder().withText("working").build();
        var permanent = PermanentLabel.newBuilder().withText("stable").build();
        var source = DirectLabelSelection.newBuilder().withOwned(working).withCurrent(working)
                .withChoices(working, permanent).withHistory(Arrays.asList(working, null, permanent, working)).build();
        LabelSlot<LabelView> applied = source;
        var staged = DirectLabelSelection.newBuilder().appendCollections(false).applyFrom(applied);
        applied.getHistory().clear();

        var target = DirectLabelSelection.newBuilder().withHistory(permanent).withChoices(permanent).build();
        var history = target.getHistory();
        var choices = target.getChoices();
        staged.applyTo(target);
        check(history == target.getHistory() && choices == target.getChoices() && history.size() == 4
                && history.get(0) == working && history.get(1) == null && history.get(2) == permanent
                && history.get(3) == working, "Copying the generic history keeps positional aliases and storage");
        check(choices.size() == 2 && choices.contains(working) && choices.contains(permanent)
                && target.getCurrent() == working && target.asReadOnly().getCurrent() == working.asReadOnly(),
                "A closed getter-only bound keeps live and immutable identities");
        check(target.getOwned().isEmpty() && source.getOwned().get(0) == working,
                "Import through the generic base does not stage subtype ownership");
        var moved = staged.applyFrom(source).build();
        check(moved.getOwned().get(0) == working && moved.getCurrent() == working && source.getOwned().isEmpty()
                && moved.getHistory().isEmpty() && moved.getChoices().contains(permanent),
                "Import through the concrete type transfers ownership with its aliases");
    }

    private static void verifyMutableOnlyApplication() {
        var working = MutableQualifiedLabel.newBuilder().withQualifier("only").build();
        var selection = NamedMutableAppliedSelection.newBuilder().withOwned(working).withCurrent(working)
                .withChoices(working).build();
        LabelSlot<MutableStringQualifiedView> applied = selection;
        ReadOnlyQualifiedView<String> view = selection.asReadOnly().getCurrent();
        check(applied.getCurrent() == working && applied.getChoices().contains(working)
                && view == working.asReadOnly() && "only".equals(view.getQualifier()),
                "The mutable-only closure retains its typed contract and aliases");
    }

    private static void verify(LabelBook book) {
        LabelSlot<PermanentLabel> permanent = book.getPermanent();
        LabelSlot<WorkingLabel> working = book.getWorking();
        ReadOnlyLabelSlot<PermanentLabel> fixedView = book.getPermanent().asReadOnly();
        ReadOnlyLabelSlot<ReadOnlyWorkingLabel> liveView = book.getWorking().asReadOnly();
        ReadOnlyLabelSlot<? extends ReadOnlyLabelView> baseView = working.asReadOnly();

        var stable = permanent.getCurrent();
        check(fixedView.getCurrent() == stable && fixedView.getHistory().get(0) == stable
                && fixedView.getHistory().get(1) == null && fixedView.getHistory().get(2) == stable,
                "immutable read-only identity and positional references");
        check(fixedView.getChoices().size() == 2 && fixedView.getChoices().contains(stable)
                && fixedView.getChoices().stream().anyMatch(choice -> choice != stable && choice.equals(stable)),
                "the set preserves distinct equal immutable identities");

        var note = book.getOwned().get(0);
        check(working.getCurrent() == note && liveView.getCurrent() == note.asReadOnly()
                && liveView.getCurrent() == baseView.getCurrent() && liveView.getHistory().get(0) == liveView.getCurrent()
                && liveView.getHistory().get(1) == null && liveView.getHistory().get(2) == liveView.getCurrent(),
                "mutable aliases share one live projection");
        var originalRevision = note.getRevision();
        note.setRevision("approved");
        check("approved".equals(liveView.getCurrent().getRevision()), "mutable projected value is live");
        note.setRevision(originalRevision);

        check(PermanentSlot.type().superTypes().get(0).getDescriptor().getArguments().get(0).getName()
                .equals(PermanentLabel.class.getName()), "immutable closed type argument reflection");
        check(WorkingSlot.type().superTypes().get(0).getDescriptor().getArguments().get(0).getName()
                .equals(WorkingLabel.class.getName()), "mutable closed type argument reflection");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
