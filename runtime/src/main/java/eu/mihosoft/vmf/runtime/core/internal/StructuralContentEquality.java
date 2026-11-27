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
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Continues generated structural comparisons on an explicit stack. */
public final class StructuralContentEquality {
    public static final int RECURSIVE_LIMIT = 96;

    private StructuralContentEquality() { }

    /** Continues from a compatible pair already registered by its generated comparison. */
    public static boolean compareEntered(VObjectInternal left, VObjectInternal right) {
        return new Comparison().run(left, right);
    }

    /** Receives one pair of raw generated fields at a time. */
    public static final class Field {
        private Object left;
        private Object right;
        private boolean unordered;

        private Field() { }

        public void value(Object first, Object second) {
            left = first;
            right = second;
            unordered = false;
        }

        public void unordered(List<?> first, List<?> second) {
            value(first, second);
            unordered = true;
        }
    }

    private static final class Comparison {
        private final Field field = new Field();
        private final ArrayDeque<Frame> pending = new ArrayDeque<>();

        private boolean run(VObjectInternal left, VObjectInternal right) {
            pending.push(new ModelFrame(left, right, left._vmf_equalityFieldCount(right), null));
            try {
                while (advance()) {
                    if (!compareField() && !rejectCandidate()) return false;
                }
                return true;
            } finally {
                while (!pending.isEmpty()) pending.pop().leave();
            }
        }

        private boolean advance() {
            while (!pending.isEmpty()) {
                if (pending.peek().next(field)) return true;
                pending.pop().leave();
            }
            return false;
        }

        private boolean rejectCandidate() {
            while (!pending.isEmpty()) {
                if (pending.peek() instanceof UnorderedFrame
                        && ((UnorderedFrame) pending.peek()).rejectCandidate()) return true;
                pending.pop().leave();
            }
            return false;
        }

        private boolean compareField() {
            Object left = field.left;
            Object right = field.right;
            if (left == right) return true;
            if (field.unordered) {
                return compareUnordered((List<?>) left, (List<?>) right);
            }
            if (left instanceof VList<?>) {
                var list = (VList<?>) left;
                if (right == null) return list.isEmpty();
                if (!(right instanceof VList<?>)) return false;
                var other = (VList<?>) right;
                int size = list.size();
                if (size != other.size()) return false;
                if (size != 0) pending.push(new ListFrame(list, other, size));
                return true;
            }
            if (right instanceof VList<?>) return left == null && ((VList<?>) right).isEmpty();
            if (left instanceof VObjectInternal && right instanceof VObjectInternal) {
                return compareModels((VObjectInternal) left, (VObjectInternal) right);
            }
            return Objects.equals(left, right);
        }

        private boolean compareUnordered(List<?> left, List<?> right) {
            int size = left == null ? 0 : left.size();
            if (size != (right == null ? 0 : right.size())) return false;
            if (size != 0) pending.push(new UnorderedFrame(left, right, size));
            return true;
        }

        private boolean compareModels(VObjectInternal left, VObjectInternal right) {
            var first = backing(left);
            var second = backing(right);
            if (first == second) return true;
            int size = first._vmf_equalityFieldCount(second);
            if (size == -1) return left._vmf_equals(right);
            if (size < 0) return false;
            if (size == 0) return true;
            var pair = new VObjectInternal.EqualsPair(first, second);
            if (pair.enter()) pending.push(new ModelFrame(first, second, size, pair));
            return true;
        }

        private static VObjectInternal backing(VObjectInternal value) {
            var backing = value._vmf_getMutableObject();
            return backing instanceof VObjectInternal ? (VObjectInternal) backing : value;
        }
    }

    private interface Frame {
        boolean next(Field field);
        default void leave() { }
    }

    private static final class ModelFrame implements Frame {
        private final VObjectInternal left;
        private final VObjectInternal right;
        private final int size;
        private final VObjectInternal.EqualsPair pair;
        private int index;

        private ModelFrame(VObjectInternal left, VObjectInternal right, int size, VObjectInternal.EqualsPair pair) {
            this.left = left;
            this.right = right;
            this.size = size;
            this.pair = pair;
        }

        @Override public boolean next(Field field) {
            if (index == size) return false;
            left._vmf_prepareEqualityField(index++, right, field);
            return true;
        }

        @Override public void leave() {
            if (pair != null) pair.leave();
        }
    }

    private static final class ListFrame implements Frame {
        private final VList<?> left;
        private final VList<?> right;
        private final int size;
        private int index;

        private ListFrame(VList<?> left, VList<?> right, int size) {
            this.left = left;
            this.right = right;
            this.size = size;
        }

        @Override public boolean next(Field field) {
            if (index == size) return false;
            field.value(left.get(index), right.get(index));
            index++;
            return true;
        }
    }

    /** Mirrors the greedy, one-use-per-member matching in ReferenceContent.unorderedEquals. */
    private static final class UnorderedFrame implements Frame {
        private final Iterator<?> left;
        private final List<?> right;
        private final boolean[] matched;
        private Object child;
        private int candidate;
        private boolean comparing;

        private UnorderedFrame(List<?> left, List<?> right, int size) {
            this.left = left.iterator();
            this.right = right;
            matched = new boolean[size];
            child = this.left.next();
        }

        @Override public boolean next(Field field) {
            if (comparing) {
                matched[candidate] = true;
                if (!left.hasNext()) return false;
                child = left.next();
                candidate = nextUnmatched(0);
            }
            field.value(child, right.get(candidate));
            comparing = true;
            return true;
        }

        private boolean rejectCandidate() {
            candidate = nextUnmatched(candidate + 1);
            comparing = false;
            return candidate < matched.length;
        }

        private int nextUnmatched(int index) {
            while (index < matched.length && matched[index]) index++;
            return index;
        }
    }
}
