package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;
import eu.mihosoft.vmf.conformance.values.KeyedAssignment;
import eu.mihosoft.vmf.conformance.values.KeyedInventory;
import eu.mihosoft.vmf.conformance.values.KeyedItem;
import eu.mihosoft.vmf.conformance.values.KeyedPart;
import eu.mihosoft.vmf.conformance.values.ReadOnlyArchiveNode;
import eu.mihosoft.vmf.conformance.values.ValueLabel;
import eu.mihosoft.vmf.conformance.values.ValueSelection;

import java.util.Arrays;

public final class DeepCopyConformance {
    private DeepCopyConformance() {}

    public static void main(String[] args) {
        verifyGraph();
        verifyKeys();
        verifyScalarStorageAndImmutableValues();
        System.out.println("Java verified native deep copy, aliases, cycle identity, and read-only routing.");
    }

    private static void verifyGraph() {
        var root = node("root");
        var child = node("child");
        var inset = node("inset");
        var detached = node("detached");
        root.getChildren().add(child);
        root.setInset(inset);
        root.setAnchor(detached);
        detached.setAnchor(root);
        root.getFeatured().addAll(Arrays.asList(child, inset));
        root.getPeers().add(detached);
        root.getTrail().addAll(Arrays.asList(child, null, detached, child, root));

        ArchiveNode copy = root.vmf().content().deepCopy();
        var newChild = copy.getChildren().get(0);
        var newInset = copy.getInset();
        var newDetached = copy.getAnchor();
        check(copy != root && newChild != child && newInset != inset && newDetached != detached,
                "all mutable nodes, including detached reference targets, have fresh identity");
        check(newChild.getParent() == copy && newInset.getInsetOf() == copy && newDetached.getParent() == null,
                "containment and inverse ownership are recreated on the copied graph");
        check(newDetached.getAnchor() == copy && copy.getFeatured().get(0) == newChild
                && copy.getFeatured().get(1) == newInset && copy.getPeers().contains(newDetached),
                "reference cycle, ordered aliases, and unordered reference memberships use copied identities");
        var trail = copy.getTrail();
        check(trail.size() == 5 && trail.get(0) == newChild && trail.get(1) == null && trail.get(2) == newDetached
                && trail.get(3) == newChild && trail.get(4) == copy, "nullable, repeated, and self-reference slots survive");
        newDetached.setName("local");
        check("detached".equals(detached.getName()) && child.getParent() == root, "copy does not edit its source graph");

        var readOnly = root.asReadOnly();
        ReadOnlyArchiveNode readOnlyCopy = readOnly.vmf().content().deepCopy();
        check(readOnlyCopy != readOnly && !(readOnlyCopy instanceof ArchiveNode)
                && "detached".equals(readOnlyCopy.getAnchor().getName()) && readOnlyCopy.getTrail().size() == 5,
                "copying a read-only root returns a read-only independent graph");
        root.setName("edited");
        check("root".equals(readOnlyCopy.getName()), "read-only copy is not a view of original storage");
        ArchiveNode detachedCopy = child.vmf().content().deepCopy();
        check(detachedCopy.getParent() == null && child.getParent() == root, "copying a child does not copy its inverse owner");
    }

    private static void verifyKeys() {
        var inventory = KeyedInventory.newInstance();
        var part = KeyedPart.newBuilder().withName("bolt").build();
        var assignment = KeyedAssignment.newBuilder().withPart(part).withCount(5).build();
        var product = KeyedItem.newBuilder().withSku("sku-a").withQuantity(2).build();
        inventory.getParts().add(part);
        inventory.getAssignments().add(assignment);
        inventory.getProducts().add(product);
        KeyedInventory copy = inventory.vmf().content().deepCopy();
        var newPart = copy.getParts().get(0);
        var newAssignment = copy.getAssignments().get(0);
        var newProduct = copy.findProductsBySku("sku-a").orElseThrow();
        check(newPart != part && newAssignment != assignment && newAssignment.getPart() == newPart
                && copy.findAssignmentsByPart(newPart).orElseThrow() == newAssignment
                && copy.findAssignmentsByPart(part).isEmpty() && newProduct != product && newProduct.getQuantity() == 2,
                "copy binds scalar and identity keys to independent entries");
    }

    private static void verifyScalarStorageAndImmutableValues() {
        var profile = CollectionProfile.newInstance();
        profile.getMessages().addAll(Arrays.asList("ready", null));
        profile.getLevels().addAll(Arrays.asList(0.0, -0.0, Double.NaN));
        profile.getLimits().put("pending", null);
        profile.getMarkers().put(0.0, '+');
        profile.getMarkers().put(-0.0, '-');
        CollectionProfile copy = profile.vmf().content().deepCopy();
        check(copy.getMessages() != profile.getMessages() && copy.getMessages().equals(profile.getMessages())
                && copy.getLevels().size() == 3 && copy.getLevels().contains(Double.NaN)
                && copy.getLimits().containsKey("pending") && copy.getLimits().get("pending") == null
                && copy.getMarkers().get(0.0) == '+' && copy.getMarkers().get(-0.0) == '-',
                "scalar sequence, set, and map storage preserve values and nullable slots");
        copy.getMessages().clear();
        copy.getLimits().clear();
        check(profile.getMessages().size() == 2 && profile.getLimits().containsKey("pending"), "copy owns separate collections");

        var defaults = DefaultSettings.newInstance();
        defaults.setName(null);
        defaults.setLimit(null);
        DefaultSettings defaultsCopy = defaults.vmf().content().deepCopy();
        check(defaultsCopy.getName() == null && defaultsCopy.getLimit() == null
                && Double.doubleToRawLongBits(defaultsCopy.getBaseline()) == Long.MIN_VALUE,
                "cleared defaults and declared signed zero survive copying");

        var shared = ValueLabel.newBuilder().withText("shared").build();
        var selection = ValueSelection.newInstance();
        selection.setCurrent(shared);
        selection.getSelected().add(shared);
        ValueSelection copySelection = selection.vmf().content().deepCopy();
        check(copySelection != selection && copySelection.getCurrent() == shared && copySelection.getSelected().contains(shared)
                && shared.vmf().content().<ValueLabel>deepCopy() == shared, "immutable values are shared by identity");
    }

    private static ArchiveNode node(String name) {
        return ArchiveNode.newBuilder().withName(name).build();
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
