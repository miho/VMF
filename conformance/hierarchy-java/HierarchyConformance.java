package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.hierarchy.CalibratedTool;
import eu.mihosoft.vmf.conformance.hierarchy.CatalogItem;
import eu.mihosoft.vmf.conformance.hierarchy.CatalogLabel;
import eu.mihosoft.vmf.conformance.hierarchy.ChoiceItem;
import eu.mihosoft.vmf.conformance.hierarchy.ChoiceQueue;
import eu.mihosoft.vmf.conformance.hierarchy.ConcretePart;
import eu.mihosoft.vmf.conformance.hierarchy.DualChoice;
import eu.mihosoft.vmf.conformance.hierarchy.ItemIndex;
import eu.mihosoft.vmf.conformance.hierarchy.ItemSelection;
import eu.mihosoft.vmf.conformance.hierarchy.LeftParts;
import eu.mihosoft.vmf.conformance.hierarchy.NamedItem;
import eu.mihosoft.vmf.conformance.hierarchy.OpenChoice;
import eu.mihosoft.vmf.conformance.hierarchy.OpenChoiceQueue;
import eu.mihosoft.vmf.conformance.hierarchy.OpenParts;
import eu.mihosoft.vmf.conformance.hierarchy.OpenPartsCatalog;
import eu.mihosoft.vmf.conformance.hierarchy.PartsCatalog;
import eu.mihosoft.vmf.conformance.hierarchy.PartsWorkbench;
import eu.mihosoft.vmf.conformance.hierarchy.RankedChoice;
import eu.mihosoft.vmf.conformance.hierarchy.RankedChoiceQueue;
import eu.mihosoft.vmf.conformance.hierarchy.RankedDualChoice;
import eu.mihosoft.vmf.conformance.hierarchy.RankedSelection;
import eu.mihosoft.vmf.conformance.hierarchy.RatedItem;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyCalibratedTool;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyChoiceQueue;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyConcretePart;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyLeftParts;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyRatedItem;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyRankedChoice;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyRightParts;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlySecondaryChoice;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyToolBackup;
import eu.mihosoft.vmf.conformance.hierarchy.RightParts;
import eu.mihosoft.vmf.conformance.hierarchy.SecondaryChoice;
import eu.mihosoft.vmf.conformance.hierarchy.ToolBackup;
import eu.mihosoft.vmf.conformance.hierarchy.TraitCatalog;
import eu.mihosoft.vmf.conformance.hierarchy.VersionedChoice;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class HierarchyConformance {
    private static final ObjectMapper VALUES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private HierarchyConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Usage: HierarchyConformance (write|read) <interchange-directory>");
        }
        Path directory = Path.of(args[1]);
        String producer = args[0].equals("write") ? "java" : "csharp";
        var traitsFile = directory.resolve(producer + "-traits.json").toFile();
        var traitReferencesFile = directory.resolve(producer + "-trait-references.json").toFile();
        var diamondFile = directory.resolve(producer + "-diamond.json").toFile();
        var openDiamondFile = directory.resolve(producer + "-open-diamond.json").toFile();
        var openChoiceFile = directory.resolve(producer + "-open-choice.json").toFile();
        var queueFile = directory.resolve(producer + "-choice-queue.json").toFile();
        if (args[0].equals("write")) {
            Files.createDirectories(directory);
            var catalog = TraitCatalog.newBuilder().withTitle("stock").withItems(
                    item("gear", "G-1", 3), item("bolt", "B-2", 4)).build();
            VALUES.writeValue(traitsFile, catalog);
            ItemIndex index = catalog;
            index.setSelected(catalog.getItems().get(0));
            REFERENCES.writeValue(traitReferencesFile, catalog);
            var motor = ConcretePart.newBuilder().withName("motor").withNumber(101).build();
            var drive = ConcretePart.newBuilder().withName("drive").withNumber(112).build();
            REFERENCES.writeValue(diamondFile, PartsCatalog.newBuilder().withHighlighted(true).withSection("north")
                    .withParts(motor, drive).withFeatured(drive).build());
            var openMotor = ConcretePart.newBuilder().withName("motor").withNumber(101).build();
            var openDrive = ConcretePart.newBuilder().withName("drive").withNumber(112).build();
            REFERENCES.writeValue(openDiamondFile, OpenPartsCatalog.newBuilder().withHighlighted(true).withSection("north")
                    .withParts(openMotor, openDrive).withFeatured(openDrive).build());
            var selectedChoice = RankedChoice.newBuilder().withName("copper").withRank(13).build();
            REFERENCES.writeValue(openChoiceFile, RankedSelection.newBuilder().withVersion(9).withLocked(true)
                    .withEntry(selectedChoice).withSelected(selectedChoice).build());
            var queued = RankedChoice.newBuilder().withName("gauge").withRank(17).build();
            var queue = RankedChoiceQueue.newBuilder().withVersion(5).withEntry(queued).build();
            queue.getQueue().addAll(Arrays.asList(queued, null, queued));
            REFERENCES.writeValue(queueFile, queue);
        }
        DefaultPrecedenceConformance.verify(VALUES, directory, producer);
        var traitJson = VALUES.readTree(traitsFile);
        check(traitJson.path("items").path(0).path("label").asText().equals("gear")
                && !traitJson.path("items").path(0).has("name") && !traitJson.has("selected"),
                "compatible traits share a single aliased wire field and the plain selection stays absent");
        checkTraits(VALUES.treeToValue(traitJson, TraitCatalog.class));
        checkTraitReference(REFERENCES.readValue(traitReferencesFile, TraitCatalog.class));
        checkDiamond(REFERENCES.readValue(diamondFile, PartsCatalog.class));
        checkOpenDiamond(REFERENCES.readValue(openDiamondFile, OpenPartsCatalog.class));
        var openChoiceWire = REFERENCES.readTree(openChoiceFile);
        check(openChoiceWire.has("chosen") && !openChoiceWire.has("selected"),
                "The open secondary contributes one aliased wire declaration");
        checkOpenChoices(REFERENCES.treeToValue(openChoiceWire, RankedSelection.class));
        checkDualChoice();
        checkChoiceQueue(REFERENCES.readValue(queueFile, RankedChoiceQueue.class));
        ShipmentConformance.verify(args[0].equals("write"), directory);
        MixedManifestConformance.verify(args[0].equals("write"), directory, REFERENCES);
        System.out.println("Java verified trait and generic diamond assignments, containment and wire identities from "
                + (producer.equals("java") ? "Java" : "C#") + ".");
    }

    private static void checkOpenDiamond(OpenPartsCatalog catalog) {
        OpenParts<ConcretePart> open = catalog;
        LeftParts<ConcretePart> left = open;
        RightParts<ConcretePart> right = open;
        ReadOnlyLeftParts<ReadOnlyConcretePart> leftView = catalog.asReadOnly();
        ReadOnlyRightParts<ReadOnlyConcretePart> rightView = catalog.asReadOnly();
        var parts = left.getParts();
        check(parts == right.getParts() && leftView.getParts() == rightView.getParts() && leftView == rightView
                        && parts.size() == 2 && right.getFeatured() == parts.get(1) && leftView.getParts().get(0).getNumber() == 101,
                "Both generic branches share one ordered owning set and its exact projected type");
        var moved = parts.get(0);
        check(((VObjectInternal) moved)._vmf_getContainer() == catalog && !right.getParts().add(moved),
                "The common set owns its item and rejects duplicate identity through either branch");
        var bench = PartsWorkbench.newBuilder().withLabel("transfer").build();
        OpenParts<ConcretePart> receiver = bench;
        receiver.getParts().add(moved);
        check(receiver.getParts().get(0) == moved && left.getParts().size() == 1 && leftView.getParts().size() == 1
                        && ((VObjectInternal) moved)._vmf_getContainer() == bench && receiver.asReadOnly() == bench.asReadOnly(),
                "A named owner can inherit the diamond through its secondary and receive the exact transferred child");
        right.setFeatured(null);
        check(left.getFeatured() == null && leftView.getFeatured() == null,
                "Writes through one overlapping reference appear in both projections");
    }

    private static void checkDualChoice() {
        var choice = RankedChoice.newBuilder().withRank(17).build();
        var tool = CalibratedTool.newBuilder().withSize(3).withCalibration(8).build();
        var named = RankedDualChoice.newBuilder().withSelected(choice).withBackup(tool).build();
        DualChoice<RankedChoice, CalibratedTool> open = named;
        SecondaryChoice<RankedChoice> primary = open;
        ToolBackup<CalibratedTool> secondary = open;
        ReadOnlySecondaryChoice<ReadOnlyRankedChoice> primaryView = named.asReadOnly();
        ReadOnlyToolBackup<ReadOnlyCalibratedTool> secondaryView = named.asReadOnly();
        check(primary.getSelected() == choice && secondary.getBackup() == tool && primaryView == secondaryView
                        && primaryView.getSelected().getRank() == 17 && secondaryView.getBackup().getCalibration() == 8,
                "Two generic data roles retain independent parameter types and share the read-only projection");
        secondary.setBackup(null);
        check(open.getBackup() == null && secondaryView.getBackup() == null && primary.getSelected() == choice,
                "An edit through the generic secondary leaves the generic primary slot intact");
    }

    private static void checkChoiceQueue(RankedChoiceQueue queue) {
        OpenChoiceQueue<RankedChoice> open = queue;
        ChoiceQueue<RankedChoice> secondary = open;
        ReadOnlyChoiceQueue<ReadOnlyRankedChoice> view = queue.asReadOnly();
        var slots = view.getQueue();
        var selected = queue.getEntry();
        check(open.getVersion() == 5 && open.getQueue() == secondary.getQueue() && secondary.asReadOnly() == view
                        && secondary.getQueue().get(0) == selected && secondary.getQueue().get(2) == selected
                        && slots.size() == 3 && slots.get(0) == selected.asReadOnly() && slots.get(2) == slots.get(0)
                        && slots.get(1) == null && slots.get(0).getRank() == 17,
                "The generic secondary sequence preserves both repeated identity and null slots in the exact typed view");
        secondary.getQueue().set(1, selected);
        check(slots.get(1) == slots.get(0) && open.getQueue().equals(List.of(selected, selected, selected)),
                "A secondary mutation remains visible through the already-open collection view");
    }

    private static void checkOpenChoices(RankedSelection ranked) {
        var initial = ChoiceItem.newBuilder().withName("scope").build();
        var closed = ItemSelection.newBuilder().withVersion(4).withSelected(initial).build();
        OpenChoice<ChoiceItem> open = closed;
        SecondaryChoice<ChoiceItem> secondary = open;
        VersionedChoice primary = open;
        var openView = open.asReadOnly();
        check(openView == secondary.asReadOnly() && secondary.getSelected() == initial && primary.getVersion() == 4,
                "The open composite exposes both bases and one read-only projection");
        var next = ChoiceItem.newBuilder().withName("next").build();
        secondary.setSelected(next);
        primary.setVersion(7);
        check(open.getSelected() == next && openView.getSelected() == next.asReadOnly() && openView.getVersion() == 7,
                "Both typed base aliases update the existing projection");

        OpenChoice<RankedChoice> openRanked = ranked;
        SecondaryChoice<RankedChoice> rankedRole = openRanked;
        ReadOnlySecondaryChoice<ReadOnlyRankedChoice> rankedView = ranked.asReadOnly();
        check(ranked.isLocked() && ranked.getVersion() == 9 && rankedRole.getSelected() == ranked.getEntry()
                        && rankedRole.asReadOnly() == rankedView && rankedView.getSelected() == ranked.getEntry().asReadOnly()
                        && rankedView.getSelected().getRank() == 13,
                "The ranked closure retains subtype, wire identity, and the exact read-only alias");
        rankedRole.setSelected(null);
        check(ranked.getSelected() == null && rankedView.getSelected() == null && ranked.getEntry().getRank() == 13,
                "Clearing a secondary reference does not remove the separately owned entry");
    }

    private static void checkTraits(TraitCatalog catalog) {
        CatalogLabel labels = catalog;
        ItemIndex index = catalog;
        check("stock".equals(labels.getTitle()) && index.getItems().size() == 2 && index.getSelected() == null,
                "secondary trait retains its owned collection and no selection");
        labels.setTitle("updated stock");
        check("updated stock".equals(catalog.getTitle()), "primary trait writes the concrete slot");
        var first = index.findItemsByName("gear").orElseThrow();
        var second = index.findItemsByName("bolt").orElseThrow();
        check(first == index.getItems().get(0) && second == index.getItems().get(1)
                && first.getCode().equals("G-1") && first.getRating() == 3
                && second.getCode().equals("B-2") && second.getRating() == 4, "secondary keyed trait retains list order and data");
        check(((VObjectInternal) first)._vmf_getContainer() == catalog, "secondary trait storage participates in actual containment");
        check(catalog.asReadOnly().findItemsByName("gear").orElseThrow() == first.asReadOnly(), "readonly inherited lookup preserves identity");

        NamedItem named = first;
        RatedItem rated = first;
        ReadOnlyRatedItem readOnly = first.asReadOnly();
        var edits = new ArrayList<Change>();
        var subscription = first.vmf().changes().addLocalListener(edits::add);
        try {
            named.setName("spindle");
            check(rated.getName().equals("spindle") && readOnly.getName().equals("spindle"), "the secondary view reads primary writes");
            rated.setName("wheel");
            check(named.getName().equals("wheel") && index.findItemsByName("wheel").orElseThrow() == first,
                    "the primary view and keyed lookup read secondary writes");
            reject(() -> rated.setName("bolt"));
            reject(() -> named.setName(null));
            check(first.getName().equals("wheel") && edits.size() == 2
                    && edits.stream().allMatch(change -> change.object() == first && change.propertyName().equals("name")),
                    "both setter paths share one guard and one logical notification");
        } finally {
            subscription.unsubscribe();
        }
        check(first.vmf().reflect().properties().stream().map(property -> property.getName()).collect(Collectors.toList())
                .equals(List.of("code", "name", "rating")), "diamond property reflection has one effective member");
        check(first.vmf().reflect().type().superTypes().stream().map(type -> type.getName()).collect(Collectors.toList())
                .equals(List.of(NamedItem.class.getName(), RatedItem.class.getName())), "reflection preserves parent declaration order");
    }

    private static void checkTraitReference(TraitCatalog catalog) {
        ItemIndex index = catalog;
        var first = index.getItems().get(0);
        RatedItem selected = index.getSelected();
        check(selected instanceof CatalogItem && selected == first && selected.getRating() == 3,
                "an interface-typed reference restores the exact contained subtype and identity");
        var view = catalog.asReadOnly();
        check(view.getSelected() == view.getItems().get(0), "the readonly interface property retains the same projection");
        selected.setRating(17);
        check(first.getRating() == 17 && view.getSelected().getRating() == 17, "both trait aliases mutate and read the same storage");
    }

    private static void checkDiamond(PartsCatalog catalog) {
        LeftParts<ConcretePart> left = catalog;
        RightParts<ConcretePart> right = catalog;
        var parts = left.getParts();
        check(parts == right.getParts() && parts.size() == 2 && left.isHighlighted() && right.getSection().equals("north"),
                "compatible closed branches share storage and keep each trait's own state");
        var motor = parts.get(0);
        var drive = parts.get(1);
        check(motor.getName().equals("motor") && motor.getNumber() == 101 && drive.getName().equals("drive")
                && drive.getNumber() == 112 && left.getFeatured() == drive && right.getFeatured() == drive,
                "named application resolves the model element and reference alias");
        check(((VObjectInternal) motor)._vmf_getContainer() == catalog && ((VObjectInternal) drive)._vmf_getContainer() == catalog,
                "generic storage anchors both contained members in the concrete owner");
        var readOnly = catalog.asReadOnly();
        ReadOnlyLeftParts<ReadOnlyConcretePart> leftView = readOnly;
        ReadOnlyRightParts<ReadOnlyConcretePart> rightView = readOnly;
        right.setFeatured(motor);
        check(left.getFeatured() == motor && leftView.getFeatured() == rightView.getParts().get(0)
                && leftView.getParts() == rightView.getParts(), "the two readonly branches see one live typed projection");
        var properties = catalog.vmf().reflect().properties();
        check(properties.stream().map(property -> property.getName()).collect(Collectors.toList())
                .equals(List.of("featured", "highlighted", "parts", "section")), "closed diamond retains one of each inherited member");
        check(properties.stream().filter(property -> property.getName().equals("parts")).findFirst().orElseThrow()
                .getType().getElementTypeName().orElseThrow().equals(ConcretePart.class.getName()), "effective element descriptor is concrete");
        var supers = catalog.vmf().reflect().type().superTypes();
        check(supers.size() == 2 && supers.get(0).getName().equals(LeftParts.class.getName())
                && supers.get(1).getName().equals(RightParts.class.getName())
                && supers.stream().allMatch(type -> type.getDescriptor().getArguments().get(0).getName().equals(ConcretePart.class.getName())),
                "reflection retains both named direct applications in source order");
    }

    private static CatalogItem item(String name, String code, int rating) {
        return CatalogItem.newBuilder().withName(name).withCode(code).withRating(rating).build();
    }

    private static void reject(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid inherited key assignment was accepted");
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
