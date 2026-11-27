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
 */
package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableMethod;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableType;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Renders portable method signatures and leaves the implementation to a C# partial class. */
final class CSharpBehaviorRenderModel {
    private static final List<String> MEMBERS = List.of("Behavior", "CreateDefaultBehavior",
            "BehaviorOverride", "ReplaceBehavior", "__vmfDefaultBehavior", "__vmfBehaviorOverride",
            "__vmfBehaviorLock", "__vmfGetBehavior");

    private final CSharpRenderModel model;
    private final CSharpCallableSignatures signatures;

    CSharpBehaviorRenderModel(CSharpRenderModel model) {
        this.model = model;
        this.signatures = new CSharpCallableSignatures(model);
    }

    Map<String, Object> prepare(Type owner, Set<String> members) {
        if (owner.getEffectiveMethods().isEmpty() && owner.getConstructionHook().isEmpty()) {
            return Map.of();
        }
        for (String member : MEMBERS) {
            if (!members.add(member)) throw conflict(owner, member);
        }
        String hook = owner.getConstructionHook().map(declaration ->
                CSharpRenderModel.identifier(declaration.getName(), owner.getQualifiedName())).orElse("");
        var methodNames = new HashSet<String>();
        var signatures = new HashSet<String>();
        var methods = new ArrayList<Map<String, String>>();
        for (var method : owner.getEffectiveMethods()) {
            String name = CSharpCallableSignatures.methodName(owner, method);
            boolean objectOverride = CSharpCapabilities.isObjectOverride(owner, method);
            if (name.equals(hook) || (!objectOverride && methodNames.add(name) && !members.add(name))) {
                throw conflict(owner, name);
            }
            String signature = slot(owner, method);
            if (!signatures.add(signature)) throw conflict(owner, signature);
            methods.add(prepareMethod(owner, method, name));
        }
        return Map.of("hook", hook, "hasHook", !hook.isEmpty(), "methods", List.copyOf(methods));
    }

    String slot(Type owner, CallableMethod method) {
        return signatures.slot(owner, method);
    }

    void validateSignatures(Type owner) {
        signatures.validate(owner);
    }

    boolean canWidenResult(Type owner, CallableType result, Type targetOwner, CallableType target) {
        if (typeName(owner, result).equals(typeName(targetOwner, target))) return true;
        if (result.getKind() != CallableType.Kind.VALUE || target.getKind() != CallableType.Kind.VALUE) return false;
        var narrow = result.getValueType();
        var wide = target.getValueType();
        return narrow.getKind() == ValueType.Kind.MODEL && wide.getKind() == ValueType.Kind.MODEL
                && narrow.getArguments().isEmpty() && wide.getArguments().isEmpty()
                && inherits(model.findType(narrow.getModelType()), wide.getModelType(), new HashSet<>());
    }

    private boolean inherits(Type type, String ancestor, Set<Type> visited) {
        return visited.add(type) && (type.getName().equals(ancestor) || type.getSuperTypes().stream()
                .map(model::findType).anyMatch(parent -> inherits(parent, ancestor, visited)));
    }

    Map<String, String> prepareMethod(Type owner, CallableMethod method, String name) {
        String callerType = CSharpRenderModel.withTypeArguments(model.typeName(owner), owner.getTypeParameters().stream()
                .map(parameter -> CSharpRenderModel.identifier(parameter.getName(), owner.getQualifiedName()))
                .collect(Collectors.toList()));
        return prepareMethod(owner, method, name, callerType);
    }

    Map<String, String> prepareMethod(Type owner, CallableMethod method, String name, String callerType) {
        var publicParameters = new ArrayList<String>();
        var bindingParameters = new ArrayList<String>();
        var arguments = new ArrayList<String>();
        bindingParameters.add(callerType + " caller");
        var localNames = new HashSet<String>();
        for (var parameter : method.getParameters()) {
            String localName = CSharpRenderModel.identifier(parameter.getName(), owner.getQualifiedName());
            if (!localNames.add(localName)) throw conflict(owner, name + "." + localName);
            String type = typeName(owner, parameter.getType(), !parameter.isVarArg());
            String declaration = type + " " + localName;
            bindingParameters.add(declaration);
            publicParameters.add(parameter.isVarArg() ? "params " + declaration : declaration);
            arguments.add(localName);
        }
        String parameterArguments = String.join(", ", arguments);
        return Map.of("name", name, "returnType", typeName(owner, method.getReturnType()),
                "parameters", String.join(", ", publicParameters),
                "bindingParameters", String.join(", ", bindingParameters), "parameterArguments", parameterArguments,
                "arguments", arguments.isEmpty() ? "this" : "this, " + parameterArguments,
                "objectOverride", CSharpCapabilities.isObjectOverride(owner, method) ? "override " : "");
    }

    String typeName(Type owner, CallableType type) {
        return typeName(owner, type, true);
    }

    private String typeName(Type owner, CallableType type, boolean nullableArray) {
        switch (type.getKind()) {
            case VOID:
                return "void";
            case VALUE:
                var value = type.getValueType();
                return model.callableValueTypeName(owner, value) + (value.isPrimitive() ? "" : "?");
            case ARRAY:
                return typeName(owner, type.getElementType()) + (nullableArray ? "[]?" : "[]");
            case LIST:
                return "global::System.Collections.Generic.IList<" + typeName(owner, type.getElementType()) + ">?";
            default:
                throw new IllegalStateException("Unsupported C# callable type " + type.getKind());
        }
    }

    private static IllegalArgumentException conflict(Type owner, String member) {
        return new IllegalArgumentException("Cannot generate C# behavior for " + owner.getQualifiedName()
                + ": '" + member + "' conflicts with another C# member or signature");
    }
}
