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
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Prepares C# validation calls in Java VMF's effective property order. */
final class CSharpValidationRenderModel {
    private CSharpValidationRenderModel() {
    }

    static List<Map<String, Object>> checks(Type type) {
        List<Map<String, Object>> checks = new ArrayList<>();
        for (Property property : type.getEffectiveProperties()) {
            String name = CSharpRenderModel.propertyName(type, property);
            String path = "path + " + CSharpSnapshotRenderModel.literal(name);
            String modelPath = "modelPath + " + CSharpSnapshotRenderModel.literal("/" + property.getName());
            addMultiplicity(property, name, path, modelPath, checks);
            var kind = property.getValueType().getKind();
            if (kind != ValueType.Kind.PARAMETER) {
                for (PropertyConstraint constraint : property.getConstraints()) {
                    addConstraint(kind, constraint, name, path, modelPath, checks);
                }
            }
        }
        return List.copyOf(checks);
    }

    private static void addMultiplicity(Property property, String name, String path, String modelPath,
                                        List<Map<String, Object>> checks) {
        boolean collection = property.isCollection();
        if (!collection && property.getValueType().isPrimitive()) {
            return;
        }
        int lower = property.getLowerBound();
        int upper = property.getUpperBound();
        boolean checkLower = lower > 0;
        boolean checkUpper = upper == 0 || collection && upper >= 0;
        if (!checkLower && !checkUpper) {
            return;
        }

        String count = collection ? name + ".Count" : name + " is null ? 0 : 1";
        if (CSharpRenderModel.isOrdinaryContainment(property)
                || collection && property.getKind() == Property.Kind.REFERENCE && !property.isUnique()) {
            count = "global::System.Linq.Enumerable.Count(" + name + ", item => item is not null)";
        }
        String variable = "count" + checks.size();
        List<String> conditions = new ArrayList<>();
        if (checkLower) {
            conditions.add(variable + " < " + lower);
        }
        if (checkUpper) {
            conditions.add(variable + " > " + upper);
        }
        String expected = checkLower && checkUpper ? "between " + lower + " and " + upper
                : checkLower ? "at least " + lower : "at most " + upper;
        checks.add(Map.of("multiplicity", true, "path", path, "modelPath", modelPath,
                "variable", variable, "count", count,
                "condition", String.join(" || ", conditions), "expected", expected));
    }

    private static void addConstraint(ValueType.Kind valueType, PropertyConstraint constraint,
                                      String name, String path, String modelPath,
                                      List<Map<String, Object>> checks) {
        String method;
        String arguments;
        switch (constraint.getKind()) {
            case RANGE:
                method = "CheckRange";
                arguments = decimalBound(constraint.getMin()) + ", " + constraint.isMinInclusive() + ", "
                        + decimalBound(constraint.getMax()) + ", " + constraint.isMaxInclusive();
                if (valueType == ValueType.Kind.FLOAT32 || valueType == ValueType.Kind.FLOAT64) {
                    method = "CheckFloatingRange";
                    arguments = CSharpFloatingRange.ranks(valueType, constraint) + ", " + arguments;
                }
                break;
            case LENGTH:
                method = "CheckLength";
                arguments = constraint.getMinLength() + ", " + constraint.getMaxLength();
                break;
            case PATTERN:
                if (constraint.isPortable()) {
                    method = "CheckPattern";
                    arguments = CSharpSnapshotRenderModel.literal(constraint.getSchemaPattern());
                } else {
                    method = "CheckBoundPattern";
                    arguments = CSharpSnapshotRenderModel.literal(constraint.getPatternBindings("csharp").get(0)) + ", "
                            + CSharpSnapshotRenderModel.literal(constraint.getPattern());
                }
                break;
            default:
                throw new IllegalStateException("Unsupported C# validation constraint " + constraint.getKind());
        }
        checks.add(Map.of("multiplicity", false, "method", method,
                "arguments", name + ", " + arguments + ", " + path + ", " + modelPath + ", issues"));
    }

    private static String decimalBound(String bound) {
        return bound == null ? "null" : CSharpSnapshotRenderModel.literal(bound);
    }
}
