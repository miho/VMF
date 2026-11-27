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

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Prepares logical property metadata and live read-only accessors for C# reflection. */
final class CSharpReflectionRenderModel {
    private CSharpReflectionRenderModel() {
    }

    static List<Map<String, Object>> prepare(CSharpRenderModel source, List<Type> types) {
        return types.stream().filter(type -> !type.isInterfaceOnly())
                .map(type -> prepareType(source, type)).collect(Collectors.toUnmodifiableList());
    }

    private static Map<String, Object> prepareType(CSharpRenderModel source, Type type) {
        String runtimeType = source.typeName(type);
        String readOnlyType = type.isImmutable() ? runtimeType : "IReadOnly" + runtimeType;
        List<String> fields = type.getEffectiveProperties().stream()
                .map(property -> field(source, type, runtimeType, readOnlyType, property))
                .collect(Collectors.toUnmodifiableList());
        return Map.of("name", runtimeType, "fields", fields,
                "annotations", CSharpAnnotationRenderModel.reflectionInitializer(type.getAnnotations()),
                "projection", type.isImmutable() ? "null" : "static model => ((" + runtimeType + ")model).AsReadOnly()",
                "validateLocal", "static (model, issues) => ((" + runtimeType + ")model).ValidateLocal(issues, \"\", \"\")");
    }

    private static String field(CSharpRenderModel source, Type type, String runtimeType,
                                String readOnlyType, Property property) {
        String member = CSharpRenderModel.propertyName(type, property);
        String name = CSharpSnapshotRenderModel.literal(property.getName());
        var metadata = new LinkedHashMap<String, String>();
        if (property.getLowerBound() != 0) {
            metadata.put("lowerBound", Integer.toString(property.getLowerBound()));
        }
        metadata.put("upperBound", Integer.toString(property.getUpperBound()));
        if (property.isOrdered()) metadata.put("ordered", "true");
        if (property.isUnique()) metadata.put("unique", "true");
        if (property.isIgnoredForEquals() || property.getKind() == Property.Kind.CONTAINER) {
            metadata.put("participatesInContentEquality", "false");
        }
        property.getEntryKey().ifPresent(key ->
                metadata.put("keyPropertyName", CSharpSnapshotRenderModel.literal(key.getPropertyName())));
        if (!property.getDeclaringType().equals(type.getName())) {
            metadata.put("declaringType",
                    CSharpSnapshotRenderModel.literal(source.findType(property.getDeclaringType()).getQualifiedName()));
        }
        if (!type.isImmutable() && CSharpRenderModel.sharesShallowSequence(property)) {
            metadata.put("shareSequence", "static (source, copy) => ((" + runtimeType + ")copy)."
                    + CSharpRenderModel.shallowShareMethod(member) + "((" + runtimeType + ")source)");
        }
        if (property.getKind() == Property.Kind.CONTAINER) {
            String valueType = source.typeName(property.getValueType());
            metadata.put("inverseType", "typeof(" + valueType + ")");
            String access = "((" + runtimeType + ")owner)." + member;
            metadata.put("inverseGet", "static owner => " + access);
            if (!property.isGetterOnly()) {
                metadata.put("inverseSet", "static (owner, value) => " + access + " = (" + valueType + "?)value");
            }
        }
        String options = metadata.entrySet().stream().map(entry -> ", " + entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining());
        var initializers = new ArrayList<String>();
        if (CSharpCapabilities.bindsExecutableDefault(property) || CSharpCapabilities.hasManagedCollectionDefault(property)) {
            initializers.add("DeclaredDefaultFactory = static () => "
                    + runtimeType + "." + CSharpExecutableDefaultRenderModel.accessor(member) + "()");
        }
        if (property.isIgnoredForToString()) initializers.add("IgnoreToString = true");
        return "new(" + name + ", static owner => ((" + readOnlyType + ")owner)." + member + options + ")"
                + CSharpAnnotationRenderModel.reflectionInitializer(property.getAnnotations(), String.join(", ", initializers));
    }
}
