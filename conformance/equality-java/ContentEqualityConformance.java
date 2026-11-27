package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.equality.EqualityContainer;
import eu.mihosoft.vmf.conformance.equality.EqualityDerived;
import eu.mihosoft.vmf.conformance.equality.EqualityIdentity;
import eu.mihosoft.vmf.conformance.equality.EqualityNode;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

final class ContentEqualityConformance {
    private ContentEqualityConformance() { }

    public static void main(String[] args) {
        equalityPolicies();
        inheritedPropertiesAndViews();
        collectionContent();
        cyclicContent();
        deepAcyclicContent();
        deepUnorderedContent();
        System.out.println("Java verified public and content equality, inheritance, collection structure, and cycles.");
    }

    private static void equalityPolicies() {
        var firstIdentity = identity("match");
        var secondIdentity = identity("match");
        check(firstIdentity.equals(firstIdentity) && !firstIdentity.equals(secondIdentity),
                "INSTANCE uses public object identity");
        equalContent(firstIdentity, secondIdentity, "INSTANCE still exposes structural content equality");
        check(contentHash(firstIdentity) == contentHash(secondIdentity), "INSTANCE content hash follows scalar fields");

        var first = node("root");
        var second = node("root");
        first.setNote("first note");
        second.setNote("different note");
        equalWithHash(first, second, "Ignored scalar field changes neither equality nor hash");
        first.setExternal(firstIdentity);
        second.setExternal(secondIdentity);
        equalWithHash(first, second, "ALL descends into non-owning references to INSTANCE declarations");
        check(new HashSet<>(List.of(first, second)).size() == 1, "Public hash is compatible with nested structural equality");
        secondIdentity.setName("different");
        different(first, second, "ALL observes a changed scalar in a referenced model");

        var left = EqualityContainer.newBuilder().withName("parent").withExternal(firstIdentity)
                .withChild(node("owned")).build();
        var right = EqualityContainer.newBuilder().withName("parent").withExternal(secondIdentity)
                .withChild(node("owned")).build();
        equalWithHash(left, right, "Containment policy ignores non-owning model reference differences");
        right.getChild().setName("changed child");
        different(left, right, "Containment policy descends into owning fields");
        right.getChild().setName("owned");
        right.setName("changed external scalar");
        different(left, right, "Containment policy includes ordinary scalar fields");

        var childLeft = node("inverse");
        var childRight = node("inverse");
        left.getChildren().add(childLeft);
        right.getChildren().add(childRight);
        check(childLeft.getContainer() == left && childRight.getContainer() == right, "Inverse setup");
        equalWithHash(childLeft, childRight, "ALL excludes the inverse container projection");

        var positive = EqualityNode.newBuilder().withReading(0.0).build();
        var negative = EqualityNode.newBuilder().withReading(-0.0).build();
        different(positive, negative, "Boxed signed zero is distinct");
        var nan = EqualityNode.newBuilder().withReading(Double.NaN).build();
        var differentNan = EqualityNode.newBuilder().withReading(Double.longBitsToDouble(0x7ff8000000000001L)).build();
        equalWithHash(nan, differentNan, "Different boxed NaN payloads compare equal");
    }

    private static void inheritedPropertiesAndViews() {
        var left = EqualityDerived.newBuilder().withName("base").withExtra("trait").withNote("ignored one").build();
        var right = EqualityDerived.newBuilder().withName("base").withExtra("trait").withNote("ignored two").build();
        equalWithHash(left, right, "Derived equality includes inherited storage and annotations");
        different(left, node("base"), "Exact generated type is part of equality");
        right.setExtra("changed trait");
        different(left, right, "Secondary interface property participates in derived equality");
        right.setExtra("trait");
        right.setName("changed base");
        different(left, right, "Primary inherited property participates in derived equality");
        right.setName("base");

        var leftView = left.asReadOnly();
        var rightView = right.asReadOnly();
        check(left.equals(rightView) && rightView.equals(left) && leftView.equals(rightView)
                && leftView.hashCode() == rightView.hashCode() && leftView.hashCode() == left.hashCode(),
                "Public ALL equality and hash compare read-only views by underlying content");
        check(left.vmf().content().equals(rightView), "Mutable content comparison handles a read-only peer");
        equalWithHash(leftView, rightView, "Content equality and hashing work through read-only projections");
        equalWithHash(left, rightView, "Content comparisons are symmetric across mutable and read-only types");
        var owner = identity("owner");
        var ownerView = owner.asReadOnly();
        check(ownerView.equals(ownerView) && !owner.equals(ownerView) && !ownerView.equals(owner),
                "INSTANCE views have their own public object identity");
        equalContent(ownerView, owner, "INSTANCE projections expose the underlying model content");
        check(contentHash(ownerView) == contentHash(owner), "Projection and backing content hashes match");
    }

    private static void collectionContent() {
        var first = node("list");
        var second = node("list");
        equalWithHash(first, second, "Fresh empty sequences compare equally");
        // VMF initializes VList storage lazily. A getter may change the backing field without changing the model content.
        second.getLinks();
        equalWithHash(first, second, "Initialized and uninitialized empty sequences have equal content");

        var shared = node("entry");
        first.getLinks().addAll(Arrays.asList(shared, null, shared));
        second.getLinks().addAll(Arrays.asList(node("entry"), null, node("entry")));
        equalWithHash(first, second, "Ordinary sequences compare positions, nulls, and content independent of alias sharing");
        second.getLinks().move(1, 0);
        different(first, second, "Moving a null slot changes ordered content");

        var left = node("set");
        var right = node("set");
        var repeatedContent = node("same");
        left.getMembers().addAll(List.of(repeatedContent, node("different"), node("same")));
        right.getMembers().addAll(List.of(node("different"), node("same"), node("same")));
        check(left.getMembers().size() == 3 && left.getMembers().contains(repeatedContent)
                && !left.getMembers().contains(node("same")), "Native identity set has distinct equivalent instances");
        equalWithHash(left, right, "Unordered sets compare equal content with the same multiplicities regardless of order");
        right.getMembers().clear();
        right.getMembers().addAll(List.of(node("different"), node("different"), node("same")));
        different(left, right, "Unordered content requires matching multiplicities, not only matching unique values");
    }

    private static void cyclicContent() {
        var self = node("cycle");
        self.setNext(self);
        var anotherSelf = node("cycle");
        anotherSelf.setNext(anotherSelf);
        equalWithHash(self, anotherSelf, "Equivalent self-cycles terminate in both equality and hash");

        var head = node("cycle");
        var tail = node("cycle");
        head.setNext(tail);
        tail.setNext(head);
        equalWithHash(self, head, "Cyclic content equality ignores the number of repeated equivalent instances");
        check(new HashSet<>(List.of(self, head)).size() == 1, "Public hash is compatible with cyclic structural equality");

        self.getMembers().add(self);
        head.getMembers().add(tail);
        tail.getMembers().add(head);
        equalWithHash(self, head, "Mixed ordered and unordered cycles can represent the same state");
        tail.setName("changed");
        different(self, head, "A mismatched field inside a cycle is still compared");
        tail.setName("cycle");
        equalContent(self, head, "A failed mixed-cycle comparison leaves no state for the next call");
    }

    private static void deepAcyclicContent() {
        var left = node("root");
        var right = node("root");
        var first = left;
        var second = right;
        for (int index = 0; index < 20_000; index++) {
            var firstNext = EqualityNode.newInstance();
            var secondNext = EqualityNode.newInstance();
            if (index % 3 == 0) {
                first.setNext(firstNext);
                second.setNext(secondNext);
            } else if (index % 3 == 1) {
                first.getLinks().addAll(Arrays.asList(null, firstNext));
                second.getLinks().addAll(Arrays.asList(null, secondNext));
            } else {
                first.getMembers().add(firstNext);
                second.getMembers().add(secondNext);
            }
            first = firstNext;
            second = secondNext;
        }
        equalWithHash(left, right, "Deep acyclic equality and hashing do not grow the call stack");
        second.setName("changed tail");
        different(left, right, "Deep acyclic equality includes distant participating fields");
        check(contentHash(left) != contentHash(right), "Acyclic hash includes distant participating fields");
    }

    private static void deepUnorderedContent() {
        var left = node("unordered root");
        var right = node("unordered root");
        var first = left;
        var second = right;
        for (int index = 0; index < 180; index++) {
            var firstNext = EqualityNode.newInstance();
            var secondNext = EqualityNode.newInstance();
            first.getMembers().add(firstNext);
            second.getMembers().add(secondNext);
            first = firstNext;
            second = secondNext;
        }
        var leftOne = node("candidate");
        var leftTwo = node("candidate");
        var rightOne = node("candidate");
        var rightTwo = node("candidate");
        leftOne.setNext(left);
        leftTwo.setNext(left);
        rightOne.setNext(right);
        rightTwo.setNext(right);
        leftOne.setReading(1.0);
        leftTwo.setReading(2.0);
        rightOne.setReading(1.0);
        rightTwo.setReading(2.0);
        first.getMembers().addAll(List.of(leftOne, leftTwo));
        second.getMembers().addAll(List.of(rightTwo, rightOne));
        equalWithHash(left, right, "Deep unordered comparison retries a failed cyclic candidate");
        rightOne.setReading(3.0);
        different(left, right, "Deep unordered comparison exhausts unsuccessful matches");
        rightOne.setReading(1.0);
        equalWithHash(left, right, "Failed unordered comparison does not retain tentative pairs");
    }

    private static EqualityNode node(String name) {
        return EqualityNode.newBuilder().withName(name).build();
    }

    private static EqualityIdentity identity(String name) {
        return EqualityIdentity.newBuilder().withName(name).build();
    }

    private static void equalContent(VObject left, VObject right, String description) {
        check(left.vmf().content().equals(right) && right.vmf().content().equals(left), description);
    }

    private static void equalWithHash(VObject left, VObject right, String description) {
        equalContent(left, right, description);
        check(left.equals(right) && right.equals(left) && left.hashCode() == right.hashCode()
                && contentHash(left) == contentHash(right), description);
    }

    private static void different(VObject left, VObject right, String description) {
        check(!left.equals(right) && !right.equals(left)
                && !left.vmf().content().equals(right) && !right.vmf().content().equals(left), description);
    }

    private static int contentHash(VObject value) {
        return value.vmf().content().hashCode();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
