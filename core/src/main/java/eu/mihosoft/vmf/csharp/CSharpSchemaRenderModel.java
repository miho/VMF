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
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CollectionDefault;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Projects portable property details into native Draft-07 schema metadata. */
final class CSharpSchemaRenderModel {
    private static final String ANNOTATION_PREFIX = "vmf:jackson:schema:";

    private CSharpSchemaRenderModel() {
    }

    static String propertyMetadata(Property property) {
        var options = new ArrayList<String>();
        if (property.getLowerBound() > 0) options.add("lowerBound: " + property.getLowerBound());
        if (property.isCollection() && property.getUpperBound() >= 0 || property.getUpperBound() == 0) {
            options.add("upperBound: " + property.getUpperBound());
        }
        property.getEntryKey().ifPresent(key -> options.add("keyPropertyName: " + quote(key.getPropertyName())));
        if (property.getKind() == Property.Kind.REFERENCE && !property.getOppositeName().isEmpty()) {
            options.add("restoresOpposite: true");
        }
        property.getScalarDefault().ifPresent(value -> options.add("defaultJson: " + quote(jsonValue(value.getValue()))));
        property.getCollectionDefault().ifPresent(value -> {
            options.add("defaultJson: " + quote(collectionDefault(property, value)));
            int size = value.getValues().size();
            boolean requiresPresence = size < property.getLowerBound()
                    || property.getUpperBound() >= 0 && size > property.getUpperBound();
            options.add("requiresPresence: " + requiresPresence);
        });
        boolean executable = CSharpCapabilities.bindsExecutableDefault(property)
                || CSharpCapabilities.hasManagedCollectionDefault(property) || CSharpCapabilities.hasReferenceSetDefault(property);
        if (executable) options.add("executableDefault: true");

        if (property.getConstraints().stream().anyMatch(PropertyConstraint::isPortable)) {
            options.add("assertionsJson: " + quote(constraints(property)));
            if (!executable && invalidDefault(property)) options.add("requiresPresence: true");
        }
        String annotations = annotations(property);
        if (!annotations.equals("{}")) options.add("annotationsJson: " + quote(annotations));

        return options.isEmpty() ? "" : ", schema: new(" + String.join(", ", options) + ")";
    }

    private static String constraints(Property property) {
        var keywords = new LinkedHashMap<String, String>();
        var additionalAssertions = new ArrayList<String>();
        for (PropertyConstraint constraint : property.getConstraints()) {
            if (!constraint.isPortable()) continue;
            var assertion = assertion(property, constraint);
            boolean conflict = assertion.entrySet().stream().anyMatch(entry ->
                    keywords.containsKey(entry.getKey()) && !keywords.get(entry.getKey()).equals(entry.getValue()));
            if (conflict) {
                additionalAssertions.add(jsonObject(assertion, ""));
            } else {
                keywords.putAll(assertion);
            }
        }
        if (!additionalAssertions.isEmpty()) {
            keywords.put("allOf", "[" + String.join(",", additionalAssertions) + "]");
        }
        return jsonObject(keywords, "");
    }

    private static Map<String, String> assertion(Property property, PropertyConstraint constraint) {
        var keywords = new LinkedHashMap<String, String>();
        switch (constraint.getKind()) {
            case RANGE:
                if (constraint.getMin() != null) {
                    keywords.put(constraint.isMinInclusive() ? "minimum" : "exclusiveMinimum", decimal(constraint.getMin()));
                }
                if (constraint.getMax() != null) {
                    keywords.put(constraint.isMaxInclusive() ? "maximum" : "exclusiveMaximum", decimal(constraint.getMax()));
                }
                var type = property.getValueType();
                if (type.getKind() == ValueType.Kind.FLOAT32 || type.getKind() == ValueType.Kind.FLOAT64) {
                    keywords.put("type", type.isPrimitive() || property.getLowerBound() > 0 ? "\"number\"" : "[\"number\",\"null\"]");
                }
                break;
            case LENGTH:
                if (constraint.getMinLength() > 0) keywords.put("minLength", Integer.toString(constraint.getMinLength()));
                if (constraint.getMaxLength() < Integer.MAX_VALUE) {
                    keywords.put("maxLength", Integer.toString(constraint.getMaxLength()));
                }
                break;
            case PATTERN:
                keywords.put("pattern", quote(constraint.getSchemaPattern()));
                break;
            default:
                throw new IllegalStateException("Unknown schema constraint " + constraint.getKind());
        }
        return keywords;
    }

    private static String annotations(Property property) {
        var keywords = new LinkedHashMap<String, String>();
        for (String value : property.getAnnotationValues(ANNOTATION_PREFIX + "constraint")) {
            int separator = value.indexOf('=');
            if (separator < 0) break;
            String key = value.substring(0, separator);
            String text = value.substring(separator + 1);
            if (!key.isBlank() && !text.isBlank()) keywords.put(key, annotationValue(text));
        }
        for (String key : List.of("description", "title", "format")) {
            String value = annotation(property, key);
            if (value != null) keywords.put(key, quote(value));
        }
        String order = annotation(property, "propertyOrder");
        if (order != null) {
            try {
                keywords.put("propertyOrder", Integer.toString(Integer.parseInt(order)));
            } catch (NumberFormatException invalid) {
                return quote("Schema propertyOrder must be an integer.");
            }
        }
        String unique = annotation(property, "uniqueItems");
        if (unique != null) keywords.put("uniqueItems", Boolean.toString(Boolean.parseBoolean(unique)));
        String injection = annotation(property, "inject");
        return jsonObject(keywords, injection == null ? "" : injection.trim());
    }

    private static String annotation(Property property, String keyword) {
        return property.getAnnotationValues(ANNOTATION_PREFIX + keyword).stream().findFirst().orElse(null);
    }

    private static String annotationValue(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.toString(Boolean.parseBoolean(value));
        }
        try {
            return decimal(value.trim());
        } catch (NumberFormatException ignored) {
            return quote(value);
        }
    }

    private static String jsonObject(Map<String, String> fields, String extra) {
        String content = fields.entrySet().stream().map(field -> quote(field.getKey()) + ":" + field.getValue())
                .collect(Collectors.joining(","));
        return "{" + content + (extra.isEmpty() ? "" : (content.isEmpty() ? "" : ",") + extra) + "}";
    }

    private static String collectionDefault(Property property, CollectionDefault value) {
        if (property.getKind() != Property.Kind.MAP) {
            return value.getValues().stream().map(member -> jsonValue(member.getValue()))
                    .collect(Collectors.joining(",", "[", "]"));
        }

        boolean stringKeys = property.getKeyType().getKind() == ValueType.Kind.STRING;
        var entries = new ArrayList<String>();
        for (int index = 0; index < value.getKeys().size(); index++) {
            String key = jsonValue(value.getKeys().get(index).getValue());
            String member = jsonValue(value.getValues().get(index).getValue());
            entries.add(stringKeys ? key + ":" + member : "{\"key\":" + key + ",\"value\":" + member + "}");
        }
        return (stringKeys ? "{" : "[") + String.join(",", entries) + (stringKeys ? "}" : "]");
    }

    private static String jsonValue(Object value) {
        if (value instanceof String || value instanceof Character
                || value instanceof Double && !Double.isFinite((Double) value)
                || value instanceof Float && !Float.isFinite((Float) value)) {
            return quote(value.toString());
        }
        return value == null ? "null" : value.toString();
    }

    private static String decimal(String value) {
        return new BigDecimal(value).toString();
    }

    private static boolean invalidDefault(Property property) {
        Object value = property.getScalarDefault().map(item -> item.getValue()).orElseGet(() -> {
            if (!property.getValueType().isPrimitive()) return null;
            switch (property.getValueType().getKind()) {
                case CHAR:
                    return '\0';
                case BOOLEAN:
                    return false;
                default:
                    return 0;
            }
        });
        if (value == null) return false;
        return property.getConstraints().stream().filter(PropertyConstraint::isPortable)
                .anyMatch(constraint -> !accepts(constraint, value));
    }

    private static boolean accepts(PropertyConstraint constraint, Object value) {
        switch (constraint.getKind()) {
            case RANGE:
                if (!(value instanceof Number)
                        || value instanceof Double && !Double.isFinite((Double) value)
                        || value instanceof Float && !Float.isFinite((Float) value)) return false;
                var number = new BigDecimal(value.toString());
                return (constraint.getMin() == null || number.compareTo(new BigDecimal(constraint.getMin()))
                        >= (constraint.isMinInclusive() ? 0 : 1))
                        && (constraint.getMax() == null || number.compareTo(new BigDecimal(constraint.getMax()))
                        <= (constraint.isMaxInclusive() ? 0 : -1));
            case LENGTH:
                String text = value.toString();
                int size = text.codePointCount(0, text.length());
                return size >= constraint.getMinLength() && size <= constraint.getMaxLength();
            case PATTERN:
                return Pattern.compile(constraint.getPattern()).matcher(value.toString()).find();
            default:
                return true;
        }
    }

    private static String quote(String value) {
        return CSharpSnapshotRenderModel.literal(value);
    }
}
