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
package eu.mihosoft.vmf.typescript;

import eu.mihosoft.vmf.core.PropertyConstraint;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * Prepares the {@code __vmfValidateInto} method of a TypeScript class in Java VMF's order: the checks of every
 * effective property in property order (multiplicity first, then the declared value constraints), then the contained
 * children, pushed onto the pending stack last first so that they are validated first to last.
 */
final class TypeScriptValidationRenderModel {
    private TypeScriptValidationRenderModel() {
    }

    /**
     * Adds the validation of a mutable or immutable class to its render data. A class with a base class declares the
     * method only if its checks differ from the inherited ones, for example because it adds or redeclares
     * properties; the method always covers all effective properties and never calls the base class.
     */
    static void prepare(Type type, Type base, Map<String, Object> result) {
        List<String> checks = new ArrayList<>();
        List<String> children = new ArrayList<>();
        statements(type, checks, children);
        List<String> statements = new ArrayList<>(checks);
        statements.addAll(children);
        result.put("emitValidation", base == null || !statements(base).equals(statements));
        result.put("validationModifier", base == null ? "" : "override ");
        result.put("validationParameters", (checks.isEmpty() ? "_issues" : "issues") + ": vmf.ValidationIssue[], "
                + (statements.isEmpty() ? "_path: string, _modelPath: string" : "path: string, modelPath: string")
                + ", " + (children.isEmpty() ? "_pending" : "pending") + ": vmf.ValidationTask[]");
        result.put("validation", List.copyOf(statements));
    }

    private static List<String> statements(Type type) {
        List<String> statements = new ArrayList<>();
        List<String> children = new ArrayList<>();
        statements(type, statements, children);
        statements.addAll(children);
        return statements;
    }

    private static void statements(Type type, List<String> checks, List<String> children) {
        for (Property property : type.getEffectiveProperties()) {
            String name = TypeScriptRenderModel.propertyName(type, property);
            String arguments = "issues, path, modelPath, " + TypeScriptRenderModel.quoted(name) + ", ";
            addMultiplicity(property, name, arguments, checks);
            ValueType.Kind kind = property.getValueType().getKind();
            if (kind != ValueType.Kind.PARAMETER) {
                for (PropertyConstraint constraint : property.getConstraints()) {
                    checks.add(constraint(kind, constraint, arguments + "this." + name));
                }
            }
            if (property.getKind() == Property.Kind.CONTAINMENT) {
                children.add(0, child(property, name, children.size()));
            }
        }
    }

    /** Like Java VMF, checks the lower bound of every property and the upper bound of collections. */
    private static void addMultiplicity(Property property, String name, String arguments, List<String> checks) {
        boolean collection = property.isCollection();
        if (!collection && property.getValueType().isPrimitive()) {
            return;
        }
        int lower = property.getLowerBound();
        int upper = property.getUpperBound();
        boolean checkUpper = upper == 0 || collection && upper >= 0;
        if (lower == 0 && !checkUpper) {
            return;
        }
        String count = collection ? "this." + name + ".count" : "this." + name + " === null ? 0 : 1";
        checks.add("vmf.checkMultiplicity(" + arguments + count + ", " + lower + ", " + (checkUpper ? upper : -1) + ");");
    }

    private static String constraint(ValueType.Kind kind, PropertyConstraint constraint, String arguments) {
        switch (constraint.getKind()) {
            case RANGE: {
                String bounds = decimal(constraint.getMin()) + ", " + constraint.isMinInclusive() + ", "
                        + decimal(constraint.getMax()) + ", " + constraint.isMaxInclusive();
                if (kind == ValueType.Kind.FLOAT32 || kind == ValueType.Kind.FLOAT64) {
                    return "vmf.checkFloatRange(" + arguments + ", " + (kind == ValueType.Kind.FLOAT32) + ", "
                            + limit(kind, constraint.getMin(), constraint.isMinInclusive(), true) + ", "
                            + limit(kind, constraint.getMax(), constraint.isMaxInclusive(), false) + ", " + bounds + ");";
                }
                return "vmf.checkRange(" + arguments + ", " + bounds + ");";
            }
            case LENGTH:
                return "vmf.checkLength(" + arguments + ", " + constraint.getMinLength() + ", "
                        + constraint.getMaxLength() + ");";
            case PATTERN:
                if (constraint.isPortable()) {
                    return "vmf.checkPattern(" + arguments + ", " + TypeScriptRenderModel.quoted(constraint.getSchemaPattern())
                            + ", " + TypeScriptRenderModel.quoted(constraint.getPattern()) + ");";
                }
                // PortableCapabilities requires exactly one TypeScript binding for a Java-only pattern.
                return "vmf.checkBoundPattern(" + arguments + ", "
                        + TypeScriptRenderModel.quoted(constraint.getPatternBindings("typescript").get(0)) + ", "
                        + TypeScriptRenderModel.quoted(constraint.getPattern()) + ");";
            default:
                throw new IllegalStateException("Unsupported TypeScript validation constraint " + constraint.getKind());
        }
    }

    private static String decimal(String bound) {
        return bound == null ? "null" : TypeScriptRenderModel.quoted(bound);
    }

    /**
     * The pending children of one containment property. The member path continues with the TypeScript member name,
     * the model path with the model property name as a JSON pointer segment.
     */
    private static String child(Property property, String name, int number) {
        String pointer = "/" + property.getName().replace("~", "~0").replace("/", "~1");
        String member = "this." + name;
        if (property.isCollection()) {
            return "for (let index = " + member + ".count - 1; index >= 0; index--) {\n"
                    + "            pending.push([" + member + ".at(index), path + " + TypeScriptRenderModel.quoted(name + "[")
                    + " + index + \"].\", modelPath + " + TypeScriptRenderModel.quoted(pointer + "/") + " + index]);\n"
                    + "        }";
        }
        String child = "child" + number;
        return "const " + child + " = " + member + ";\n"
                + "        if (" + child + " !== null) {\n"
                + "            pending.push([" + child + ", path + " + TypeScriptRenderModel.quoted(name + ".")
                + ", modelPath + " + TypeScriptRenderModel.quoted(pointer) + "]);\n"
                + "        }";
    }

    /**
     * The lowest (or highest) float or double that satisfies a decimal bound, as a TypeScript number. Like Java's
     * {@code NumericRange}, a value is compared by its decimal representation ({@code Float.toString} or
     * {@code Double.toString}), which orders the finite values like their bits; a binary search over these ranks
     * finds the limit. An unsatisfiable bound yields an infinity, which no finite value passes.
     */
    private static String limit(ValueType.Kind kind, String bound, boolean inclusive, boolean lower) {
        if (bound == null) {
            return lower ? "Number.NEGATIVE_INFINITY" : "Number.POSITIVE_INFINITY";
        }
        boolean single = kind == ValueType.Kind.FLOAT32;
        long maxRank = single ? Float.floatToRawIntBits(Float.MAX_VALUE) : Double.doubleToRawLongBits(Double.MAX_VALUE);
        LongFunction<String> decimal = single
                ? rank -> Float.toString(Float.intBitsToFloat(singleBits(rank)))
                : rank -> Double.toString(Double.longBitsToDouble(doubleBits(rank)));
        BigDecimal limit = new BigDecimal(bound);
        long low = -maxRank;
        long high = maxRank;
        while (low <= high) {
            // The finite rank interval may span more than Long.MAX_VALUE.
            long middle = low + ((high - low) >>> 1);
            int comparison = new BigDecimal(decimal.apply(middle)).compareTo(limit);
            if (comparison < 0 || (comparison == 0 && inclusive != lower)) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        long rank = lower ? low : high;
        double value = single ? Float.intBitsToFloat(singleBits(rank)) : Double.longBitsToDouble(doubleBits(rank));
        if (Double.isInfinite(value)) {
            return value > 0 ? "Number.POSITIVE_INFINITY" : "Number.NEGATIVE_INFINITY";
        }
        // Double.toString writes enough digits to identify the value, so TypeScript reads the same number.
        return Double.toString(value);
    }

    private static int singleBits(long rank) {
        return rank < 0 ? Integer.MIN_VALUE - (int) rank : (int) rank;
    }

    private static long doubleBits(long rank) {
        return rank < 0 ? Long.MIN_VALUE - rank : rank;
    }
}
