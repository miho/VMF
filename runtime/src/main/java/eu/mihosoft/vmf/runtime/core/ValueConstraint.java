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

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/** An immutable constraint on the present value of a scalar model property. */
public abstract class ValueConstraint {
    private ValueConstraint() {}

    public abstract String getCode();

    /** Tests a property value. Multiplicity determines whether null is permitted. */
    public abstract boolean isSatisfiedBy(Object value);

    /** A numeric bound represented exactly as a decimal. */
    public static final class NumericRange extends ValueConstraint {
        private final BigDecimal min;
        private final BigDecimal max;
        private final boolean minInclusive;
        private final boolean maxInclusive;

        public NumericRange(String min, boolean minInclusive, String max, boolean maxInclusive) {
            this.min = min.isEmpty() ? null : new BigDecimal(min);
            this.max = max.isEmpty() ? null : new BigDecimal(max);
            this.minInclusive = minInclusive;
            this.maxInclusive = maxInclusive;
        }

        public Optional<BigDecimal> getMin() { return Optional.ofNullable(min); }
        public Optional<BigDecimal> getMax() { return Optional.ofNullable(max); }
        public boolean isMinInclusive() { return minInclusive; }
        public boolean isMaxInclusive() { return maxInclusive; }

        @Override public String getCode() { return "vmf.range"; }

        @Override public boolean isSatisfiedBy(Object value) {
            if (value == null) return true;
            if (!(value instanceof Number)) return false;
            if (value instanceof Double && !Double.isFinite((Double) value)) return false;
            if (value instanceof Float && !Float.isFinite((Float) value)) return false;
            // Match the decimal rendition used for boxed numeric values on the Jackson wire.
            BigDecimal decimal = new BigDecimal(value.toString());
            return (min == null || decimal.compareTo(min) >= (minInclusive ? 0 : 1))
                    && (max == null || decimal.compareTo(max) <= (maxInclusive ? 0 : -1));
        }

        @Override public String toString() {
            String lower = min == null ? "" : (minInclusive ? ">= " : "> ") + min;
            String upper = max == null ? "" : (maxInclusive ? "<= " : "< ") + max;
            return "a finite number " + lower + (min != null && max != null ? " and " : "") + upper;
        }
    }

    /** An inclusive number of Unicode code points. */
    public static final class StringLength extends ValueConstraint {
        private final int min;
        private final int max;

        public StringLength(int min, int max) {
            this.min = min;
            this.max = max;
        }

        public int getMin() { return min; }
        /** Returns -1 if there is no maximum. */
        public int getMax() { return max; }

        @Override public String getCode() { return "vmf.length"; }

        @Override public boolean isSatisfiedBy(Object value) {
            if (value == null) return true;
            if (!(value instanceof String)) return false;
            String text = (String) value;
            int length = text.codePointCount(0, text.length());
            return length >= min && (max < 0 || length <= max);
        }

        @Override public String toString() {
            return min + ".." + (max < 0 ? "*" : max) + " Unicode code points";
        }
    }

    /** A compiled regular expression with JSON Schema search semantics. */
    public static final class StringPattern extends ValueConstraint {
        private final String expression;
        private final Pattern javaPattern;
        private final String schemaPattern;

        public StringPattern(String expression, String javaExpression, String schemaPattern) {
            this.expression = expression;
            this.javaPattern = Pattern.compile(javaExpression);
            this.schemaPattern = schemaPattern;
        }

        public String getExpression() { return expression; }
        public Optional<String> getSchemaPattern() { return Optional.ofNullable(schemaPattern); }

        @Override public String getCode() { return "vmf.pattern"; }

        @Override public boolean isSatisfiedBy(Object value) {
            return value == null || value instanceof String && javaPattern.matcher((String) value).find();
        }

        @Override public String toString() {
            return "a match for /" + expression + "/";
        }
    }
}
