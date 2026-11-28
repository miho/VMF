// The model cases of the Java lane (../java/perf/ModelScenarios.java), written against the TypeScript code
// generated from the same model interface: the same graphs, operation counts and checks of the final state.
// The TypeScript target has no sort and no transactions, and no reflective setter.
import type { ModelChange } from "@vmf/runtime";
import { TreeNode } from "./build/generated/eu/mihosoft/vmftest/performance/model.g.js";

const SCALARS = 64;
const SIBLINGS = 32;
const DEPTH = 6;

/** `run` is timed; `verify` checks the final state and the delivered events afterwards. */
export interface Batch {
    run(iterations: number): void;
    verify(iterations: number): void;
}

/** One case: `setup` builds a fresh model graph for a batch of `operations` operations. */
export interface Scenario {
    name: string;
    operations: number;
    setup(): Batch;
}

export const cases: Scenario[] = [
    { name: "typed-scalar-get-set", operations: 65_536, setup: () => scalarAccess(false) },
    // Reads and writes the property by its name (node[name]).
    { name: "reflective-scalar-get-set", operations: 16_384, setup: () => scalarAccess(true) },
    { name: "containment-reparent", operations: 511, setup: () => reparent(false) },
    { name: "containment-reparent-observed", operations: 255, setup: () => reparent(true) },
    // Java's Collections.swap on a containment list; the TypeScript list offers move().
    { name: "containment-swap", operations: 1_023, setup: swap },
    { name: "opposite-reference-replace", operations: 8_191, setup: replaceReference },
    { name: "root-scalar-event", operations: 8_192, setup: () => scalarEvents(false) },
    { name: "deep-scalar-event", operations: 8_192, setup: () => scalarEvents(true) },
    { name: "deep-list-event", operations: 4_095, setup: listEvents },
    { name: "recursive-listener-lifecycle", operations: 64, setup: listenerLifecycle },
    // Starts a recording, makes two edits and stops it.
    { name: "native-record-publish", operations: 127, setup: record },
    { name: "native-undo-redo", operations: 512, setup: undoRedo },
    { name: "clone-linked-graph", operations: 64, setup: cloneGraph },
];

function scalarAccess(reflect: boolean): Batch {
    const nodes: TreeNode[] = [];
    for (let i = 0; i < SCALARS; i++) nodes.push(node("scalar-" + i, i));
    const name = "value";
    return {
        run(iterations) {
            if (reflect) {
                for (let i = 0; i < iterations; i++) {
                    const target = nodes[i & (SCALARS - 1)] as unknown as Record<string, number>;
                    target[name] = target[name] + 1;
                }
            } else {
                for (let i = 0; i < iterations; i++) {
                    const target = nodes[i & (SCALARS - 1)];
                    target.value = target.value + 1;
                }
            }
        },
        verify(iterations) {
            for (let i = 0; i < nodes.length; i++) {
                const writes = Math.floor((iterations + SCALARS - 1 - i) / SCALARS);
                require(nodes[i].value === i + writes, "Scalar state differs");
            }
        },
    };
}

function reparent(observed: boolean): Batch {
    const root = node("root", 0);
    const left = node("left", 0);
    const right = node("right", 0);
    root.children.add(left);
    root.children.add(right);
    const leftChildren = children(left);
    const rightChildren = children(right);
    const moved = node("moved", 0);
    const leaf = chain(moved);
    const middle = SIBLINGS / 2;
    left.children.insert(middle, moved);
    let listEvents = 0;
    let leafEvents = 0;
    const unsubscribe = observed ? root.vmfChanges.addListener(change => {
        if (change.kind !== "set" && (change.source === left || change.source === right)) listEvents++;
        if (change.source === leaf) leafEvents++;
    }, "recursive") : undefined;
    return {
        run(iterations) {
            for (let i = 0; i < iterations; i++) {
                const destination = (i & 1) === 0 ? right : left;
                destination.children.insert(middle, moved);
            }
        },
        verify(iterations) {
            const back = (iterations & 1) === 0;
            (back ? leftChildren : rightChildren).splice(middle, 0, moved);
            verifyChildren(left, leftChildren);
            verifyChildren(right, rightChildren);
            let descendant = moved;
            for (let depth = 0; depth < DEPTH; depth++) {
                require(descendant.children.count === 1, "Moved subtree lost a descendant");
                const child = descendant.children.at(0);
                require(child.parent === descendant, "Moved descendant lost its parent");
                descendant = child;
            }
            require(descendant === leaf, "Moved subtree lost its original leaf");
            if (unsubscribe !== undefined) {
                require(listEvents === 2 * iterations,
                    `Expected two containment notifications per operation, got ${listEvents} for ${iterations}`);
                leaf.value = 1;
                require(leafEvents === 1, "Moved descendant must remain observed exactly once");
                unsubscribe();
                leaf.value = 2;
                require(leafEvents === 1, "Unsubscribed listener still observed changes");
            }
        },
    };
}

function swapChildren(parent: TreeNode, low: number, high: number): void {
    parent.children.move(low, high);
    parent.children.move(high - 1, low);
}

function swap(): Batch {
    const parent = node("parent", 0);
    const initial = children(parent);
    return {
        run(iterations) {
            for (let i = 0; i < iterations; i++) swapChildren(parent, 4, 27);
        },
        verify(iterations) {
            if ((iterations & 1) !== 0) [initial[4], initial[27]] = [initial[27], initial[4]];
            verifyChildren(parent, initial);
        },
    };
}

function replaceReference(): Batch {
    const source = node("source", 0);
    const first = node("first", 0);
    const second = node("second", 0);
    source.link = first;
    return {
        run(iterations) {
            for (let i = 0; i < iterations; i++) source.link = (i & 1) === 0 ? second : first;
        },
        verify(iterations) {
            const back = (iterations & 1) === 0;
            const linked = back ? first : second;
            const released = back ? second : first;
            require(source.link === linked && linked.linkedFrom === source && released.linkedFrom === null,
                "Opposite reference differs");
        },
    };
}

class Count {
    value = 0;
    unexpected = 0;

    observe(change: ModelChange, target: TreeNode, set: boolean): void {
        if (change.source !== target || (change.kind === "set") !== set) this.unexpected++;
        this.value++;
    }

    verify(expected: number): void {
        require(this.value === expected && this.unexpected === 0,
            `Wrong event count, source, or type: ${this.value} of ${expected}, ${this.unexpected} unexpected`);
    }
}

function scalarEvents(deep: boolean): Batch {
    const root = node("root", 0);
    const leaf = chain(root);
    const target = deep ? leaf : root;
    const received = new Count();
    const unsubscribe = root.vmfChanges.addListener(change => received.observe(change, target, true),
        deep ? "recursive" : "local");
    return {
        run(iterations) {
            for (let i = 1; i <= iterations; i++) target.value = i;
        },
        verify(iterations) {
            require(target.value === iterations, "Incorrect scalar state");
            received.verify(iterations);
            unsubscribe();
        },
    };
}

function listEvents(): Batch {
    const root = node("root", 0);
    const leaf = chain(root);
    leaf.tags.add("first");
    const received = new Count();
    const unsubscribe = root.vmfChanges.addListener(change => received.observe(change, leaf, false), "recursive");
    return {
        run(iterations) {
            for (let i = 0; i < iterations; i++) leaf.tags.set(0, (i & 1) === 0 ? "second" : "first");
        },
        verify(iterations) {
            require(leaf.tags.count === 1 && leaf.tags.at(0) === ((iterations & 1) === 0 ? "first" : "second"),
                "Incorrect list state");
            received.verify(iterations);
            unsubscribe();
        },
    };
}

function listenerLifecycle(): Batch {
    const root = node("root", 0);
    const leaves: TreeNode[] = [];
    tree(root, 3, leaves);
    const leaf = leaves[0];
    const received = new Count();
    return {
        run(iterations) {
            for (let i = 1; i <= iterations; i++) {
                const unsubscribe = root.vmfChanges.addListener(change => received.observe(change, leaf, true),
                    "recursive");
                leaf.value = i;
                unsubscribe();
            }
        },
        verify(iterations) {
            leaf.value = iterations + 1;
            received.verify(iterations);
        },
    };
}

function record(): Batch {
    const root = node("root", 0);
    const leaf = chain(root);
    leaf.tags.add("first");
    const changes = root.vmfChanges;
    return {
        run(iterations) {
            for (let i = 1; i <= iterations; i++) {
                changes.clear();
                changes.start();
                leaf.value = i;
                leaf.tags.set(0, (i & 1) === 0 ? "first" : "second");
                changes.stop();
            }
        },
        verify(iterations) {
            require(changes.history.length === 2, `Expected two recorded edits, got ${changes.history.length}`);
            require(leaf.value === iterations && leaf.tags.at(0) === ((iterations & 1) === 0 ? "first" : "second"),
                "Recording did not retain the scalar and list edits");
        },
    };
}

function undoRedo(): Batch {
    const root = node("root", 0);
    const initial = children(root);
    const changes = root.vmfChanges;
    changes.start();
    root.value = 3;
    swapChildren(root, 4, 27);
    changes.stop();
    const history = changes.history;
    require(history.length === 3, `Undo/redo requires a scalar and two list edits, got ${history.length}`);
    let undoResults = 0;
    return {
        run(iterations) {
            for (let i = 0; i < iterations; i++) {
                for (let index = history.length - 1; index >= 0; index--) history[index].undo();
                if (root.value === 0 && root.children.at(4) === initial[4]) undoResults++;
                for (let index = 0; index < history.length; index++) history[index].redo();
            }
        },
        verify(iterations) {
            require(undoResults === iterations && root.value === 3,
                `Undo or redo has the wrong state: ${undoResults} of ${iterations}`);
            [initial[4], initial[27]] = [initial[27], initial[4]];
            verifyChildren(root, initial);
        },
    };
}

function cloneGraph(): Batch {
    const root = node("root", 0);
    const leaves: TreeNode[] = [];
    tree(root, 3, leaves);
    for (let i = 0; i < leaves.length; i++) leaves[i].link = leaves[(i + 17) % leaves.length];
    let last: TreeNode | undefined;
    return {
        run(iterations) {
            for (let i = 0; i < iterations; i++) last = root.deepCopy();
        },
        verify() {
            const mapped = new Map<TreeNode, TreeNode>();
            collectClones(root, last!, null, mapped);
            require(mapped.size === 85, "Expected an 85-node clone");
            for (const [source, clone] of mapped) {
                require(clone.link === (source.link === null ? null : mapped.get(source.link))
                    && clone.linkedFrom === (source.linkedFrom === null ? null : mapped.get(source.linkedFrom)),
                    "Clone has a missing or shared reference");
            }
        },
    };
}

function collectClones(source: TreeNode, clone: TreeNode, parent: TreeNode | null, mapped: Map<TreeNode, TreeNode>): void {
    require(clone !== source && source.id === clone.id && clone.parent === parent
        && source.children.count === clone.children.count && source.value === clone.value, "Clone has a mismatched node");
    mapped.set(source, clone);
    for (let i = 0; i < source.children.count; i++) {
        collectClones(source.children.at(i), clone.children.at(i), clone, mapped);
    }
}

function chain(root: TreeNode): TreeNode {
    let parent = root;
    for (let i = 0; i < DEPTH; i++) {
        const child = node(parent.id + "-child", 0);
        parent.children.add(child);
        parent = child;
    }
    return parent;
}

function tree(root: TreeNode, remainingDepth: number, leaves: TreeNode[]): void {
    if (remainingDepth === 0) {
        leaves.push(root);
        return;
    }
    for (let i = 0; i < 4; i++) {
        const child = node(root.id + "-" + i, i);
        root.children.add(child);
        tree(child, remainingDepth - 1, leaves);
    }
}

function children(parent: TreeNode): TreeNode[] {
    const result: TreeNode[] = [];
    for (let i = 0; i < SIBLINGS; i++) {
        const child = node(parent.id + "-" + i, i);
        result.push(child);
        parent.children.add(child);
    }
    return result;
}

function node(id: string, value: number): TreeNode {
    const result = new TreeNode();
    result.id = id;
    result.value = value;
    return result;
}

function verifyChildren(parent: TreeNode, expected: TreeNode[]): void {
    const actual = parent.children;
    require(actual.count === expected.length, "Unexpected child count");
    for (let i = 0; i < expected.length; i++) {
        require(actual.at(i) === expected[i] && actual.at(i).parent === parent,
            "Containment identity, order, or parent differs");
    }
}

function require(condition: boolean, message: string): void {
    if (!condition) throw new Error(message);
}
