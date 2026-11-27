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

import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;
import eu.mihosoft.vmf.core.codegen.TypeScriptCapabilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps external types to TypeScript: {@code java.util.UUID} to {@code string}, {@code java.lang.Object} to
 * {@code unknown}, a union of string literals to an exported type alias named after the stub, and any other
 * mapped type to its name, imported from its module when the stub names one.
 */
final class TypeScriptExternals {
    /** How one external type appears in the generated module. */
    static final class Mapping {
        private final String key;
        private final String typeName;
        private final List<String> literals;
        private final String scalarType;

        private Mapping(String key, String typeName, List<String> literals, String scalarType) {
            this.key = key;
            this.typeName = typeName;
            this.literals = literals;
            this.scalarType = scalarType;
        }

        /** The member name in {@code ModelBindings.externals}: the simple name of the stub. */
        String getKey() { return key; }

        /** The TypeScript type of a value. */
        String getTypeName() { return typeName; }

        /** The literals of an enum-like union, or null. */
        List<String> getLiterals() { return literals; }

        /** The scalar type of the snapshot registry: string, uuid, object or external. */
        String getScalarType() { return scalarType; }

        /** True if documents need a codec from the installed bindings. */
        boolean needsCodec() { return scalarType.equals("external"); }
    }

    private final ResolvedModel model;
    private final Set<String> emittedNames;
    private final Map<String, Mapping> mappings = new LinkedHashMap<>();
    private final Map<String, String> imports = new LinkedHashMap<>();
    private final List<String> declarations = new ArrayList<>();

    /** Reserves the names of aliases and imports in {@code emittedNames}, which holds the generated declarations. */
    TypeScriptExternals(ResolvedModel model, Set<String> emittedNames) {
        this.model = model;
        this.emittedNames = emittedNames;
    }

    Mapping mapping(ValueType type) {
        return mappings.computeIfAbsent(type.getExternalType(), this::resolve);
    }

    /** The imports of the external types used so far. */
    List<String> getImports() {
        var result = new ArrayList<String>();
        imports.forEach((module, names) -> result.add("import type { " + names + " } from "
                + TypeScriptRenderModel.quoted(module) + ";"));
        return result;
    }

    /** The type aliases of the literal unions used so far, each followed by an empty line. */
    List<String> getAliases() {
        return List.copyOf(declarations);
    }

    private Mapping resolve(String source) {
        if (source.equals(TypeScriptCapabilities.UUID)) {
            return new Mapping(simpleName(source), "string", null, "uuid");
        }
        if (TypeScriptCapabilities.isUnboundObject(model, source)) {
            return new Mapping(simpleName(source), "unknown", null, "object");
        }
        String key = simpleName(source);
        String text = TypeScriptCapabilities.mapping(model, source).orElseThrow(() -> invalid(source,
                "needs one " + TypeScriptCapabilities.EXTERNAL_TYPE_ANNOTATION));
        var literals = TypeScriptCapabilities.literals(model, source);
        if (literals.isPresent()) {
            String alias = reserve(key, source);
            declarations.add("/** The values of " + source + ". */");
            declarations.add("export type " + alias + " = " + String.join(" | ", literals.get().stream()
                    .map(TypeScriptRenderModel::quoted).toArray(String[]::new)) + ";");
            declarations.add("");
            return new Mapping(key, alias, literals.get(), "string");
        }
        if (!TypeScriptCapabilities.isTypeName(text)) {
            throw invalid(source, "maps to '" + text + "', which is not a TypeScript type name or literal union");
        }
        var modules = model.externalAnnotations(source).stream()
                .filter(annotation -> annotation.getKey().equals(TypeScriptCapabilities.EXTERNAL_MODULE_ANNOTATION))
                .map(ResolvedModel.MetadataAnnotation::getValue).toArray(String[]::new);
        if (modules.length == 0) {
            // A global type such as Date; a generated declaration of the same name would hide it.
            String root = text.contains(".") ? text.substring(0, text.indexOf('.')) : text;
            if (emittedNames.contains(root)) {
                throw invalid(source, "maps to the global type " + text + ", which a generated declaration hides");
            }
            return new Mapping(key, text, null, "external");
        }
        String alias = importName(text, modules[0], source);
        return new Mapping(key, alias, null, "external");
    }

    /** Imports the type once per module; a name taken by another declaration is imported under an alias. */
    private String importName(String name, String module, String source) {
        String existing = imports.get(module);
        if (existing != null) {
            for (String entry : existing.split(", ")) {
                String[] parts = entry.split(" as ");
                if (parts[0].equals(name)) return parts[parts.length - 1];
            }
        }
        String alias = emittedNames.contains(name) ? reserve("Host" + name, source) : reserve(name, source);
        String entry = alias.equals(name) ? name : name + " as " + alias;
        imports.merge(module, entry, (left, right) -> left + ", " + right);
        return alias;
    }

    private String reserve(String name, String source) {
        String candidate = name;
        for (int index = 2; !emittedNames.add(candidate); index++) {
            candidate = name + index;
            if (index > 100) throw invalid(source, "has no free TypeScript name");
        }
        return candidate;
    }

    private static String simpleName(String source) {
        return source.substring(source.lastIndexOf('.') + 1);
    }

    private static IllegalArgumentException invalid(String source, String reason) {
        return new IllegalArgumentException("Cannot generate TypeScript for the external type " + source + ": "
                + reason);
    }
}
