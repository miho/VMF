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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/** Checks property constraints and prepares their generated runtime descriptors. */
final class ConstraintDeclarations {
    private static final Set<Class<?>> NUMERIC = Set.of(byte.class, short.class, int.class, long.class,
            float.class, double.class, Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class);
    private static final java.util.regex.Pattern QUANTIFIER = java.util.regex.Pattern.compile("\\{[0-9]+(?:,[0-9]*)?}");

    private ConstraintDeclarations() {}

    static List<String> expressions(ModelType owner, Method getter, Class<?> valueType) {
        Class<?> raw = getter.getReturnType();
        boolean many = raw.isArray() || Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)
                || raw.getName().equals("eu.mihosoft.vcollections.VMap");
        Set<String> expressions = new LinkedHashSet<>();
        for (Method declaration : owner.genericTypes().propertyDeclarations(getter.getName())) {
            Range range = declaration.getAnnotation(Range.class);
            if (range != null) {
                checkType(owner, declaration, many, valueType, NUMERIC, "@Range", "a numeric scalar");
                expressions.add(rangeExpression(declaration, range));
            }
            Length length = declaration.getAnnotation(Length.class);
            if (length != null) {
                checkType(owner, declaration, many, valueType, Set.of(String.class), "@Length", "a String scalar");
                if (length.min() < 0 || length.max() < -1 || length.max() >= 0 && length.max() < length.min()) {
                    throw invalid(declaration, "@Length has invalid bounds");
                }
                expressions.add("ValueConstraint.StringLength(" + length.min() + ", " + length.max() + ")");
            }
            Pattern pattern = declaration.getAnnotation(Pattern.class);
            if (pattern != null) {
                checkType(owner, declaration, many, valueType, Set.of(String.class), "@Pattern", "a String scalar");
                expressions.add(patternExpression(declaration, pattern));
            }
        }
        return List.copyOf(expressions);
    }

    private static void checkType(ModelType owner, Method declaration, boolean many, Class<?> valueType,
                                  Set<Class<?>> allowed, String annotation, String expected) {
        // An interface-only generic property is validated again after concrete binding.
        if (many || !(valueType == null ? owner.isInterfaceOnly() : allowed.contains(valueType))) {
            throw invalid(declaration, annotation + " requires " + expected + " in " + owner.getFullTypeName());
        }
    }

    private static String rangeExpression(Method declaration, Range range) {
        try {
            String min = decimal(range.min());
            String max = decimal(range.max());
            if (min.isEmpty() && max.isEmpty()) throw invalid(declaration, "@Range needs at least one bound");
            if (!min.isEmpty() && !max.isEmpty()) {
                int order = new BigDecimal(min).compareTo(new BigDecimal(max));
                if (order > 0 || order == 0 && (!range.minInclusive() || !range.maxInclusive())) {
                    throw invalid(declaration, "@Range has an empty interval");
                }
            }
            return "ValueConstraint.NumericRange(" + quote(min) + ", " + range.minInclusive()
                    + ", " + quote(max) + ", " + range.maxInclusive() + ")";
        } catch (NumberFormatException exception) {
            throw invalid(declaration, "@Range bounds must be finite decimal numbers");
        }
    }

    private static String decimal(String value) {
        return value.isEmpty() ? value : new BigDecimal(value).toString();
    }

    private static String patternExpression(Method declaration, Pattern pattern) {
        String expression = pattern.value();
        try {
            java.util.regex.Pattern.compile(expression);
        } catch (PatternSyntaxException exception) {
            throw invalid(declaration, "@Pattern is invalid: " + exception.getDescription());
        }
        if (!pattern.portable()) {
            return "ValueConstraint.StringPattern(" + quote(expression) + ", " + quote(expression) + ", null)";
        }

        List<Integer> endAnchors = portableEndAnchors(declaration, expression);
        StringBuilder javaExpression = new StringBuilder(expression);
        StringBuilder schemaExpression = new StringBuilder(expression);
        for (int i = endAnchors.size() - 1; i >= 0; i--) {
            int index = endAnchors.get(i);
            // ECMAScript $ is strict without multiline mode. Java also accepts a final line break.
            javaExpression.replace(index, index + 1, "\\z");
            schemaExpression.replace(index, index + 1, "(?![\\s\\S])");
        }
        return "ValueConstraint.StringPattern(" + quote(expression) + ", " + quote(javaExpression.toString())
                + ", " + quote(schemaExpression.toString()) + ")";
    }

    private static List<Integer> portableEndAnchors(Method declaration, String expression) {
        List<Integer> endAnchors = new ArrayList<>();
        boolean inClass = false;
        for (int i = 0; i < expression.length(); i++) {
            char token = expression.charAt(i);
            if (token < ' ' || token > '~') throw nonportable(declaration);
            char next = i + 1 < expression.length() ? expression.charAt(i + 1) : '\0';
            if (token == '\\') {
                String escapes = inClass ? "\\[]{}()^$.+*?|/dwnrtf-" : "\\[]{}()^$.+*?|/dwnrtf";
                if (escapes.indexOf(next) < 0) throw nonportable(declaration);
                i++;
            } else if (inClass) {
                if (token == '[' || token == '&' && next == '&') throw nonportable(declaration);
                if (token == ']') inClass = false;
            } else if (token == '[') {
                if (next == '^') throw nonportable(declaration);
                inClass = true;
            } else if (token == '{') {
                var quantifier = QUANTIFIER.matcher(expression).region(i, expression.length());
                if (!quantifier.lookingAt()) throw nonportable(declaration);
                i = quantifier.end() - 1;
                if (i + 1 < expression.length() && expression.charAt(i + 1) == '+') throw nonportable(declaration);
            } else if (token == '(' && next == '?') {
                if (!expression.startsWith("(?:", i)) throw nonportable(declaration);
                i += 2;
            } else if (token == '.' || token == ']' || token == '}' || "+*?".indexOf(token) >= 0 && next == '+') {
                throw nonportable(declaration);
            } else if (token == '$' || token == '^') {
                if ("+*?{".indexOf(next) >= 0) throw nonportable(declaration);
                if (token == '$') endAnchors.add(i);
            }
        }
        return endAnchors;
    }

    private static IllegalArgumentException nonportable(Method declaration) {
        return invalid(declaration, "@Pattern is outside the portable subset; use portable = false for a Java-only regex");
    }

    private static IllegalArgumentException invalid(Method declaration, String message) {
        return GenericModelTypes.unsupportedProperty(declaration, message);
    }

    private static String quote(String value) {
        return "\"" + StringUtil.escapeJavaStyleString(value, false) + "\"";
    }
}
