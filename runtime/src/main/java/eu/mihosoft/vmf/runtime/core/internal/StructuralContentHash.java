/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vcollections.VList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Hashes the same raw fields as generated structural equality. */
public final class StructuralContentHash {
    private static final int CYCLIC_ROUNDS = 5;
    private static final ThreadLocal<Set<VObjectInternal>> ACTIVE = ThreadLocal.withInitial(
            () -> Collections.newSetFromMap(new IdentityHashMap<>()));

    private StructuralContentHash() { }

    public static int hash(VObjectInternal value) {
        var active = ACTIVE.get();
        // An opaque external value can call its owning model's public hashCode.
        if (!active.add(value)) return 0;
        try {
            return new Graph(value).hash();
        } finally {
            active.remove(value);
        }
    }

    /** Receives raw participating fields from generated code, without materializing lazy lists. */
    public static final class Fields {
        private final Graph graph;
        private final VObjectInternal value;
        private int localHash;
        private List<Relation> relations;

        private Fields(Graph graph, VObjectInternal value) {
            this.graph = graph;
            this.value = value;
            localHash = value.getClass().hashCode();
        }

        public void value(Object field) {
            if (field instanceof VObjectInternal) {
                addLocal(0);
                addRelation(new int[]{graph.index((VObjectInternal) field)}, true);
            } else if (field instanceof VList<?>) {
                list((VList<?>) field, true);
            } else {
                addLocal(Objects.hashCode(field));
            }
        }

        public void list(List<?> members, boolean ordered) {
            int scalarHash = 0;
            int[] targets = null;
            if (members != null) {
                scalarHash = members.size();
                int position = 0;
                for (Object member : members) {
                    if (ordered) scalarHash *= 31;
                    if (member instanceof VObjectInternal) {
                        if (targets == null) {
                            targets = new int[members.size()];
                            Arrays.fill(targets, -1);
                        }
                        targets[position] = graph.index((VObjectInternal) member);
                    } else {
                        scalarHash += Objects.hashCode(member);
                    }
                    position++;
                }
            }
            // Generated equality equates an uninitialized list to an initialized empty list.
            addLocal(scalarHash);
            if (targets != null) addRelation(targets, ordered);
        }

        private void addLocal(int hash) {
            localHash = 31 * localHash + hash;
        }

        private void addRelation(int[] targets, boolean ordered) {
            if (relations == null) relations = new ArrayList<>();
            relations.add(new Relation(targets, ordered));
        }

        private int hash(int[] hashes) {
            int result = localHash;
            if (relations != null) {
                for (Relation relation : relations) result = 31 * result + relation.hash(hashes);
            }
            return result;
        }
    }

    private static final class Relation {
        private final int[] targets;
        private final boolean ordered;

        private Relation(int[] targets, boolean ordered) {
            this.targets = targets;
            this.ordered = ordered;
        }

        private int hash(int[] hashes) {
            int result = 0;
            for (int target : targets) {
                if (ordered) result *= 31;
                if (target >= 0) result += hashes[target];
            }
            return result;
        }
    }

    private static final class Graph {
        private final IdentityHashMap<VObjectInternal, Integer> indices = new IdentityHashMap<>(4);
        private final List<Fields> nodes = new ArrayList<>();

        private Graph(VObjectInternal root) { index(root); }

        private int index(VObjectInternal value) {
            var mutable = value._vmf_getMutableObject();
            if (mutable instanceof VObjectInternal) value = (VObjectInternal) mutable;
            Integer known = indices.get(value);
            if (known != null) return known;
            int index = nodes.size();
            indices.put(value, index);
            nodes.add(new Fields(this, value));
            return index;
        }

        private int hash() {
            for (int index = 0; index < nodes.size(); index++) {
                Fields node = nodes.get(index);
                node.value._vmf_collectHashFields(node);
            }
            if (nodes.get(0).relations == null) return nodes.get(0).localHash;
            int[] hashes = new int[nodes.size()];
            if (hashInPostorder(hashes)) return hashes[0];
            return hashCyclic(hashes);
        }

        private boolean hashInPostorder(int[] hashes) {
            // 0 is unseen, 1 is an active ancestor, and 2 is complete.
            byte[] states = new byte[nodes.size()];
            var pending = new ArrayDeque<Integer>();
            pending.push(0);
            while (!pending.isEmpty()) {
                int visit = pending.pop();
                if (visit < 0) {
                    int index = ~visit;
                    hashes[index] = nodes.get(index).hash(hashes);
                    states[index] = 2;
                } else if (states[visit] == 0) {
                    states[visit] = 1;
                    pending.push(~visit);
                    var relations = nodes.get(visit).relations;
                    if (relations != null) {
                        for (Relation relation : relations) {
                            for (int target : relation.targets) {
                                if (target >= 0) pending.push(target);
                            }
                        }
                    }
                } else if (states[visit] == 1) {
                    return false;
                }
            }
            return true;
        }

        private int hashCyclic(int[] hashes) {
            // A fixed neighborhood is invariant to alias sharing and repeated equivalent
            // identities in a cycle. A cutoff at the first repeated identity is not.
            for (int index = 0; index < hashes.length; index++) hashes[index] = nodes.get(index).localHash;
            int[] next = new int[hashes.length];
            for (int round = 0; round < CYCLIC_ROUNDS; round++) {
                for (int index = 0; index < hashes.length; index++) next[index] = nodes.get(index).hash(hashes);
                int[] previous = hashes;
                hashes = next;
                next = previous;
            }
            return hashes[0];
        }
    }
}
