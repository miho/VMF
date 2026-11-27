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

import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;

import java.util.List;
import java.util.Map;

/** Target types and construction rules shared by the scalar collection renderers. */
final class CSharpScalarCollections {
    private static final String RUNTIME = "global::Vmf.Runtime.";
    private static final String GENERIC = "global::System.Collections.Generic.";

    private CSharpScalarCollections() {
    }

    static boolean isScalarCollection(Property property) {
        return property.getKind() == Property.Kind.MAP
                || property.getKind() == Property.Kind.SCALAR && property.isCollection();
    }

    static String valueType(CSharpRenderModel source, Property property) {
        String scalar = source.typeName(property.getValueType());
        return property.allowsNullElements() || source.usesUnboundedStorageArgument(property, false) ? scalar + "?" : scalar;
    }

    static String keyType(CSharpRenderModel source, Property property) {
        String scalar = source.typeName(property.getKeyType());
        return source.usesUnboundedStorageArgument(property, true) ? scalar + "?" : scalar;
    }

    static String snapshotKind(Property property) {
        if (property.getKind() == Property.Kind.MAP) {
            return "ScalarMap";
        }
        if (!property.isOrdered()) {
            return "ScalarSet";
        }
        return property.isUnique() ? "OrderedScalarSet" : "ScalarList";
    }

    static Map<String, Object> mutableMember(CSharpRenderModel source, String ownerType, Property property, String name) {
        String initializer = "new(" + constructorArguments(property) + ", "
                + source.collectionChangeSource(property, ownerType, name);
        if (source.usesScalarListForImmutableValues(property)) {
            initializer += ", storesImmutableModels: true";
        } else if (source.needsImmutableArgumentCheck(property)) {
            initializer += ", storesImmutableModels: " + source.immutableArgumentCheck(property);
        } else if (source.isUnboundObject(property.getValueType())) {
            initializer += ", storesImmutableModels: false, untypedObjectElements: true";
        }
        initializer += ")";
        return Map.of("name", name, "type", mutableType(source, property), "initializer", initializer,
                "containment", false, "container", false, "initialized", true);
    }

    static String mutableType(CSharpRenderModel source, Property property) {
        String kind = snapshotKind(property);
        String implementation = kind.equals("OrderedScalarSet") ? "ScalarList" : kind;
        return CSharpRenderModel.withTypeArguments(RUNTIME + implementation, typeArguments(source, property));
    }

    static Map<String, String> readOnlyMember(CSharpRenderModel source, Property property, String name) {
        String contract;
        if (property.getKind() == Property.Kind.MAP) {
            contract = GENERIC + "IReadOnlyDictionary";
        } else if (property.isOrdered()) {
            contract = RUNTIME + "IReadOnlyScalarList";
        } else {
            contract = RUNTIME + "IReadOnlyScalarSet";
        }
        String type = CSharpRenderModel.withTypeArguments(contract, typeArguments(source, property));
        if (property.getKind() == Property.Kind.MAP || CSharpRenderModel.sharesShallowSequence(property)) {
            return Map.of("name", name, "type", type, "initializer", "",
                    "getter", "=> model." + name + ".AsReadOnly();");
        }
        return Map.of("name", name, "type", type, "initializer", "model." + name + ".AsReadOnly()", "getter", "{ get; }");
    }

    private static List<String> typeArguments(CSharpRenderModel source, Property property) {
        String value = valueType(source, property);
        if (property.getKind() == Property.Kind.MAP) {
            return List.of(keyType(source, property), value);
        }
        return List.of(value);
    }

    static int storageUpperBound(Property property) {
        // Legacy nullable scalar lists use declared bounds for validation and schema only.
        return property.getKind() == Property.Kind.SCALAR && property.allowsNullElements()
                ? -1 : property.getUpperBound();
    }

    static String constructorArguments(Property property) {
        String upper = Integer.toString(storageUpperBound(property));
        if (property.isOrdered() && (property.getKind() == Property.Kind.SCALAR || property.getKind() == Property.Kind.REFERENCE)) {
            return upper + ", unique: " + property.isUnique() + ", allowNull: " + property.allowsNullElements();
        }
        return upper;
    }
}
