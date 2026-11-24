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
package eu.mihosoft.vmf.runtime.core;

import java.util.Objects;

/** Value describing a property's cardinality, ordering and uniqueness in the model. */
public final class Multiplicity {
    /** The upper bound used for unlimited collection sizes. */
    public static final int UNBOUNDED = -1;

    private static final Multiplicity OPTIONAL_SCALAR = new Multiplicity(0, 1, false, true);
    private static final Multiplicity REQUIRED_SCALAR = new Multiplicity(1, 1, false, true);
    private static final Multiplicity SEQUENCE = new Multiplicity(0, UNBOUNDED, true, false);
    private static final Multiplicity SET = new Multiplicity(0, UNBOUNDED, false, true);
    private static final Multiplicity ORDERED_SET = new Multiplicity(0, UNBOUNDED, true, true);
    private final int lower;
    private final int upper;
    private final boolean ordered;
    private final boolean unique;

    private Multiplicity(int lower, int upper, boolean ordered, boolean unique) {
        if (lower < 0 || upper < UNBOUNDED || (upper != UNBOUNDED && lower > upper))
            throw new IllegalArgumentException("Invalid multiplicity " + lower + ".." + upper);
        this.lower = lower;
        this.upper = upper;
        this.ordered = ordered;
        this.unique = unique;
    }

    /** Creates a multiplicity with inclusive lower and upper bounds. */
    public static Multiplicity of(int lower, int upper, boolean ordered, boolean unique) {
        if (upper == 1 && !ordered && unique) {
            if (lower == 0) return OPTIONAL_SCALAR;
            if (lower == 1) return REQUIRED_SCALAR;
        }
        if (lower == 0 && upper == UNBOUNDED) {
            if (ordered) return unique ? ORDERED_SET : SEQUENCE;
            if (unique) return SET;
        }
        return new Multiplicity(lower, upper, ordered, unique);
    }

    /** Minimum number of values in a complete model. */
    public int getLower() { return lower; }
    /** Maximum number of values, or {@link #UNBOUNDED}. */
    public int getUpper() { return upper; }
    /** Whether positions are part of the model contract. */
    public boolean isOrdered() { return ordered; }
    /** Whether a collection admits each member at most once. Map keys are unique. */
    public boolean isUnique() { return unique; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof Multiplicity)) return false;
        Multiplicity m = (Multiplicity) other;
        return lower == m.lower && upper == m.upper && ordered == m.ordered && unique == m.unique;
    }
    @Override public int hashCode() { return Objects.hash(lower, upper, ordered, unique); }

    @Override public String toString() {
        return lower + ".." + (upper == UNBOUNDED ? "*" : upper) + (ordered ? " ordered" : " unordered")
                + (unique ? " unique" : " nonunique");
    }
}
