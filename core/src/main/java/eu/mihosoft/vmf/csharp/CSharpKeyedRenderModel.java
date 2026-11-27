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
 * Computing and Visualization in Science, 2013, 16(4), 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.ResolvedModel.EntryKey;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;

import java.util.Map;

/** Lowers the same keyed relationship names for native lookup, read-only views, and builders. */
final class CSharpKeyedRenderModel {
    private CSharpKeyedRenderModel() {
    }

    static String lookupName(Type owner, Property property, EntryKey key) {
        return "Find" + CSharpRenderModel.propertyName(owner, property) + "By" + keyMember(key);
    }

    static String selector(CSharpRenderModel source, Property property, EntryKey key) {
        String member = "child." + keyMember(key);
        if (key.usesIdentity() && source.usesGetterContract(property.getValueType())) {
            member = "ModelSnapshot.UnwrapReadOnly(" + member + ")";
        }
        return "static child => " + member;
    }

    static Map<String, String> lookup(CSharpRenderModel source, Type owner, Property property, EntryKey key) {
        return lookup(source, owner, property, key, false);
    }

    static Map<String, String> lookup(CSharpRenderModel source, Type owner, Property property, EntryKey key,
                                      boolean hidesInherited) {
        String keyType = source.typeName(key.getValueType());
        String name = CSharpRenderModel.propertyName(owner, property);
        return Map.of("name", lookupName(owner, property, key), "keyType", keyType,
                "modifier", hidesInherited ? "new " : "",
                "returnType", source.typeName(property.getValueType()) + "?",
                "expression", name + ".FindByKey<" + keyType + ">(key)");
    }

    private static String keyMember(EntryKey key) {
        return CSharpRenderModel.identifier(key.getPropertyName(), key.getRelationshipId());
    }
}
