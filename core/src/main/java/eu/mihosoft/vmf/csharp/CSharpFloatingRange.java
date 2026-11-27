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
package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.PropertyConstraint;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.math.BigDecimal;
import java.util.function.LongFunction;

/** Lowers Java decimal comparisons to inclusive, ordered IEEE-754 ranks. */
final class CSharpFloatingRange {
    private CSharpFloatingRange() {
    }

    static String ranks(ValueType.Kind kind, PropertyConstraint constraint) {
        long maxRank;
        LongFunction<String> decimalText;
        switch (kind) {
            case FLOAT32:
                maxRank = Float.floatToRawIntBits(Float.MAX_VALUE);
                decimalText = rank -> Float.toString(Float.intBitsToFloat(singleBits((int) rank)));
                break;
            case FLOAT64:
                maxRank = Double.doubleToRawLongBits(Double.MAX_VALUE);
                decimalText = rank -> Double.toString(Double.longBitsToDouble(doubleBits(rank)));
                break;
            default:
                throw new IllegalArgumentException("Floating-point range requires a float or double type");
        }
        long lower = limit(maxRank, decimalText, constraint.getMin(), constraint.isMinInclusive(), true);
        long upper = limit(maxRank, decimalText, constraint.getMax(), constraint.isMaxInclusive(), false);
        return lower + "L, " + upper + "L";
    }

    private static int singleBits(int rank) {
        return rank < 0 ? Integer.MIN_VALUE - rank : rank;
    }

    private static long doubleBits(long rank) {
        return rank < 0 ? Long.MIN_VALUE - rank : rank;
    }

    private static long limit(long maxRank, LongFunction<String> decimalText, String bound,
                              boolean inclusive, boolean lowerLimit) {
        if (bound == null) {
            return lowerLimit ? -maxRank : maxRank;
        }

        BigDecimal limit = new BigDecimal(bound);
        long low = -maxRank;
        long high = maxRank;
        while (low <= high) {
            // The finite rank interval may span more than Long.MAX_VALUE.
            long middle = low + ((high - low) >>> 1);
            int comparison = new BigDecimal(decimalText.apply(middle)).compareTo(limit);
            if (comparison < 0 || (comparison == 0 && inclusive != lowerLimit)) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        // Adjacent ranks outside the finite interval represent impossible bounds.
        return lowerLimit ? low : high;
    }
}
