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
package eu.mihosoft.vmf.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A checked scalar constraint shared by VMF code generators. */
public final class PropertyConstraint {
    public enum Kind {
        RANGE, LENGTH, PATTERN
    }

    private final Kind kind;
    private final String min;
    private final String max;
    private final boolean minInclusive;
    private final boolean maxInclusive;
    private final int minLength;
    private final int maxLength;
    private final String pattern;
    private final String javaPattern;
    private final String schemaPattern;
    private final Map<String, List<String>> patternBindings;

    private PropertyConstraint(Kind kind, String min, boolean minInclusive, String max, boolean maxInclusive,
                               int minLength, int maxLength, String pattern, String javaPattern, String schemaPattern,
                               Map<String, List<String>> patternBindings) {
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.minInclusive = minInclusive;
        this.maxInclusive = maxInclusive;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.pattern = pattern;
        this.javaPattern = javaPattern;
        this.schemaPattern = schemaPattern;
        this.patternBindings = patternBindings;
    }

    static PropertyConstraint range(String min, boolean minInclusive, String max, boolean maxInclusive) {
        return new PropertyConstraint(Kind.RANGE, min, minInclusive, max, maxInclusive, 0, -1, null, null, null, Map.of());
    }

    static PropertyConstraint length(int min, int max) {
        return new PropertyConstraint(Kind.LENGTH, null, true, null, true, min, max, null, null, null, Map.of());
    }

    static PropertyConstraint pattern(String source, String javaPattern, String schemaPattern) {
        return pattern(source, javaPattern, schemaPattern, Map.of());
    }

    static PropertyConstraint pattern(String source, String javaPattern, String schemaPattern,
                                      Map<String, List<String>> bindings) {
        return new PropertyConstraint(Kind.PATTERN, null, true, null, true, 0, -1, source, javaPattern, schemaPattern,
                bindings.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> entry.getValue().stream().distinct().collect(Collectors.toUnmodifiableList()))));
    }

    PropertyConstraint mergeBindings(PropertyConstraint duplicate) {
        if (duplicate.patternBindings.isEmpty()) return this;
        var merged = new LinkedHashMap<>(patternBindings);
        duplicate.patternBindings.forEach((target, bindings) -> merged.merge(target, bindings,
                (current, additional) -> Stream.concat(current.stream(), additional.stream()).collect(Collectors.toList())));
        return pattern(pattern, javaPattern, schemaPattern, merged);
    }

    public Kind getKind() {
        return kind;
    }

    /** The normalized decimal minimum of a range, or null for no lower bound. */
    public String getMin() {
        return min;
    }

    /** The normalized decimal maximum of a range, or null for no upper bound. */
    public String getMax() {
        return max;
    }

    public boolean isMinInclusive() {
        return minInclusive;
    }

    public boolean isMaxInclusive() {
        return maxInclusive;
    }

    public int getMinLength() {
        return minLength;
    }

    /** An inclusive bound in Unicode code points, or -1 for no upper bound. */
    public int getMaxLength() {
        return maxLength;
    }

    /** The pattern as written in the model. */
    public String getPattern() {
        return pattern;
    }

    /** The portable pattern with strict end anchors, or null for a Java-only pattern. */
    public String getSchemaPattern() {
        return schemaPattern;
    }

    public boolean isPortable() {
        return kind != Kind.PATTERN || schemaPattern != null;
    }

    /** Explicit counterparts declared for a target's native regex dialect. */
    public List<String> getPatternBindings(String target) {
        return patternBindings.getOrDefault(target, List.of());
    }

    String getJavaPattern() {
        return javaPattern;
    }
}
