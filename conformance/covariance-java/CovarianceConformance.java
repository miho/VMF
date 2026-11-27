package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.covariance.ClassifiedItem;
import eu.mihosoft.vmf.conformance.covariance.ClassifiedPickTrait;
import eu.mihosoft.vmf.conformance.covariance.ColorTag;
import eu.mihosoft.vmf.conformance.covariance.ExactPick;
import eu.mihosoft.vmf.conformance.covariance.FrozenChoice;
import eu.mihosoft.vmf.conformance.covariance.FrozenColorChoice;
import eu.mihosoft.vmf.conformance.covariance.FrozenPickTrait;
import eu.mihosoft.vmf.conformance.covariance.FrozenTraitChoice;
import eu.mihosoft.vmf.conformance.covariance.NarrowPick;
import eu.mihosoft.vmf.conformance.covariance.PickTrait;
import eu.mihosoft.vmf.conformance.covariance.PickableItem;
import eu.mihosoft.vmf.conformance.covariance.ReadOnlyPickTrait;
import eu.mihosoft.vmf.conformance.covariance.ReaffirmedPick;
import eu.mihosoft.vmf.conformance.covariance.ReferenceShowcase;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class CovarianceConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private CovarianceConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        var exchange = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(exchange);
            var specimen = specimen();
            verifyTypedAccess(specimen);
            verifyMergeCompatibility();
            verifyBuilderTransfer();
            var path = exchange.resolve("java-covariance.json");
            MAPPER.writeValue(path.toFile(), specimen);
            verifyWire(path);
            System.out.println("Java verified narrowed mutable and immutable model-reference getters.");
        } else {
            verifyWire(exchange.resolve("csharp-covariance.json"));
            System.out.println("Java restored C# covariant references with their concrete types and wire aliases.");
        }
    }

    private static void verifyBuilderTransfer() {
        var classified = ClassifiedItem.newBuilder().withCode("picked").withCategory("type").build();
        PickTrait compatible = ExactPick.newBuilder().withCurrent(classified).build();
        var staged = NarrowPick.newBuilder().applyFrom(compatible);
        check(staged.build().getCurrent() == classified, "Covariant import builds the same reference");

        var target = NarrowPick.newInstance();
        var view = target.asReadOnly();
        staged.applyTo(target);
        check(target.getCurrent() == classified && view.getCurrent() == classified.asReadOnly(),
                "The covariant builder edits the shared live slot");

        var broad = PickableItem.newBuilder().withCode("broad").build();
        PickTrait incompatible = ExactPick.newBuilder().withCurrent(broad).build();
        try {
            staged.applyFrom(incompatible);
            throw new AssertionError("Covariant import accepted a broad reference");
        } catch (ClassCastException expected) {
            check(staged.build().getCurrent() == classified, "A failed cast preserves the previous staged reference");
        }

        staged.applyFrom((PickTrait) ExactPick.newInstance()).applyTo(target);
        check(target.getCurrent() == null && view.getCurrent() == null, "Import explicitly clears the selected slot");
        check(ExactPick.newBuilder().applyFrom(compatible).build().getCurrent() == classified,
                "Concrete promotion retains the ancestor's getter value");
    }

    private static ReferenceShowcase specimen() {
        var broad = PickableItem.newBuilder().withCode("broad").build();
        var classified = ClassifiedItem.newBuilder().withCode("narrow").withCategory("special").build();
        var color = ColorTag.newBuilder().withTitle("status").withColor("green").build();
        return ReferenceShowcase.newBuilder().withItems(broad, classified)
                .withExact(ExactPick.newBuilder().withCurrent(broad).build())
                .withNarrow(NarrowPick.newBuilder().withCurrent(classified).build())
                .withReaffirmed(ReaffirmedPick.newBuilder().withCurrent(classified).build())
                .withFrozen(FrozenColorChoice.newBuilder().withCurrent(color).build())
                .withTraitFrozen(FrozenTraitChoice.newBuilder().withCurrent(color).build()).build();
    }

    private static void verifyTypedAccess(ReferenceShowcase showcase) {
        var narrow = showcase.getNarrow();
        PickTrait trait = narrow;
        ReadOnlyPickTrait wideView = narrow.asReadOnly();
        var classified = narrow.getCurrent();
        check(classified == trait.getCurrent() && classified.asReadOnly() == narrow.asReadOnly().getCurrent()
                && classified.asReadOnly() == wideView.getCurrent(), "Both base contracts expose the narrowed value");
        check(Arrays.stream(PickTrait.class.getMethods()).noneMatch(method -> method.getName().equals("setCurrent"))
                && Arrays.stream(NarrowPick.class.getMethods()).noneMatch(method -> method.getName().equals("setCurrent")
                        && method.getParameterTypes()[0] == PickableItem.class), "A broad mutable setter is absent on the narrowed owner");
        var field = trait.vmf().reflect().propertyByName("current").orElseThrow();
        check(field.getType().getName().equals(ClassifiedItem.class.getName()), "Reflection exposes the narrowed model target");
        var edits = new ArrayList<Object>();
        var observer = narrow.vmf().changes().addPropertyListener("current", change ->
                edits.add(change.propertyChange().orElseThrow().newValue()));
        try {
            try {
                field.set(showcase.getExact().getCurrent());
                throw new AssertionError("Reflection accepted the broad target");
            } catch (ClassCastException expected) {
                check(narrow.getCurrent() == classified && edits.isEmpty(), "A rejected edit does not change the slot or publish an event");
            }
            narrow.setCurrent(null);
            field.set(classified);
            check(edits.equals(Arrays.asList(null, classified)) && trait.getCurrent() == classified
                    && wideView.getCurrent() == classified.asReadOnly(), "Successful edits share one live slot");
        } finally {
            observer.unsubscribe();
        }
        FrozenChoice frozen = showcase.getFrozen();
        var specialized = (FrozenColorChoice) frozen;
        check(frozen.getCurrent() == specialized.getCurrent() && specialized.asReadOnly() == specialized
                && frozen.vmf().reflect().propertyByName("current").orElseThrow().getType().getName().equals(ColorTag.class.getName()),
                "The immutable subtype keeps one narrowed value visible through its primary base");
        ClassifiedPickTrait refined = showcase.getReaffirmed();
        check(refined.getCurrent() == classified && ((PickTrait) refined).getCurrent() == classified
                && ((ReadOnlyPickTrait) showcase.getReaffirmed().asReadOnly()).getCurrent() == classified.asReadOnly(),
                "An implementation can reaffirm an abstract trait's narrower slot");
        FrozenPickTrait frozenTrait = showcase.getTraitFrozen();
        check(frozenTrait.getCurrent() == specialized.getCurrent() && frozenTrait == showcase.getTraitFrozen().asReadOnly(),
                "A direct immutable implementation narrows the getter-only trait and retains reference identity");
    }

    private static void verifyMergeCompatibility() {
        var original = ClassifiedItem.newBuilder().withCode("original").withCategory("kept").build();
        var incoming = ClassifiedItem.newBuilder().withCode("incoming").withCategory("replacement").build();
        var template = NarrowPick.newBuilder().withCurrent(original).build();
        var overlay = ExactPick.newBuilder().withCurrent(incoming).build();
        var merged = (NarrowPick) ModelDiff.merge((PickTrait) template, overlay);
        check(merged != template && merged.getCurrent() != incoming && merged.getCurrent().getCode().equals("incoming")
                        && merged.getCurrent().getCategory().equals("replacement"),
                "Merge copies the concrete value from a broadly declared slot into the narrower template");
        check(merged.asReadOnly().getCurrent() == merged.getCurrent().asReadOnly()
                        && template.getCurrent() == original && overlay.getCurrent() == incoming,
                "The narrowed read-only view is live and the two inputs keep their identities");

        var field = overlay.vmf().reflect().propertyByName("current").orElseThrow();
        field.setDefault(incoming);
        overlay.setCurrent(null);
        check(((NarrowPick) ModelDiff.merge((PickTrait) template, overlay)).getCurrent() == null
                        && template.getCurrent() == original,
                "A selected null also fits the narrower reference");

        var incompatible = PickableItem.newBuilder().withCode("too broad").build();
        overlay.setCurrent(incompatible);
        try {
            ModelDiff.merge((PickTrait) template, overlay);
            throw new AssertionError("A reference outside the narrowed contract cannot be applied");
        } catch (ClassCastException expected) {
            check(template.getCurrent() == original && overlay.getCurrent() == incompatible,
                    "A rejected merge leaves both input graphs intact");
        }
    }

    private static void verifyWire(Path file) throws IOException {
        var tree = MAPPER.readTree(file.toFile());
        check(tree.path("exact").has("old_pick") && tree.path("narrow").has("preferred") && !tree.path("narrow").has("old_pick")
                && tree.path("reaffirmed").has("reaffirmed_pick") && tree.path("traitFrozen").has("trait_color")
                && tree.path("frozen").has("color_tag") && !tree.path("frozen").has("old_tag"), "Outward snapshots use effective aliases");
        var showcase = MAPPER.readValue(file.toFile(), ReferenceShowcase.class);
        check(showcase.getItems().size() == 2 && showcase.getExact().getCurrent() == showcase.getItems().get(0)
                && showcase.getNarrow().getCurrent() == showcase.getItems().get(1)
                && List.of("special", "narrow").equals(List.of(showcase.getNarrow().getCurrent().getCategory(),
                        showcase.getNarrow().getCurrent().getCode())), "Mutable targets restore as references to the same contained identities");
        check(showcase.getFrozen() instanceof FrozenColorChoice && showcase.getFrozen().getCurrent() instanceof ColorTag
                && "green".equals(((FrozenColorChoice) showcase.getFrozen()).getCurrent().getColor()),
                "The typed immutable snapshot restores the narrowed subclass and its exact reference");
        verifyTypedAccess(showcase);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
