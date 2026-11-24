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
package eu.mihosoft.vmf.runtime.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A declared type name and its ordered type arguments. Names identify declarations
 * in the model's type vocabulary. This immutable value requires no class loader.
 */
public final class TypeDescriptor {
    private final String declaringType;
    private final String name;
    private final List<TypeDescriptor> arguments;

    private TypeDescriptor(String name, List<TypeDescriptor> arguments, String declaringType) {
        this.declaringType = declaringType;
        this.name = Objects.requireNonNull(name, "name");
        if (name.isEmpty() || name.chars().anyMatch(c -> Character.isWhitespace(c) || c == '<' || c == '>' || c == ',')) {
            throw new IllegalArgumentException("Invalid type name: " + name);
        }
        this.arguments = List.copyOf(arguments);
    }

    /** Constructs a type with the supplied arguments in declaration order. */
    public static TypeDescriptor of(String name, TypeDescriptor... arguments) {
        return new TypeDescriptor(name, List.of(arguments), null);
    }

    /** A type variable identified by its owning declaration and local name. */
    public static TypeDescriptor parameter(String declaringType, String name) {
        Objects.requireNonNull(declaringType, "declaringType");
        if (declaringType.isBlank()) throw new IllegalArgumentException("Empty declaring type");
        return new TypeDescriptor(name, List.of(), declaringType);
    }

    /** For a type variable, returns the declaration that introduced it. */
    public Optional<String> getDeclaringType() { return Optional.ofNullable(declaringType); }

    /** Returns the declaration name without arguments. */
    public String getName() { return name; }

    /** Returns the immutable argument list, empty for a simple type. */
    public List<TypeDescriptor> getArguments() { return arguments; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof TypeDescriptor)) return false;
        TypeDescriptor that = (TypeDescriptor) other;
        return name.equals(that.name) && arguments.equals(that.arguments) && Objects.equals(declaringType, that.declaringType);
    }

    @Override public int hashCode() { return Objects.hash(name, arguments, declaringType); }

    @Override public String toString() {
        if (arguments.isEmpty()) return name;
        return arguments.stream().map(Object::toString).collect(Collectors.joining(", ", name + "<", ">"));
    }

    /** Reads the fully qualified type names emitted by existing model generators. */
    public static TypeDescriptor fromTypeName(String typeName) {
        Objects.requireNonNull(typeName, "typeName");
        if (typeName.indexOf('<') < 0) return of(typeName);
        Parser parser = new Parser(typeName);
        TypeDescriptor type = parser.readType();
        if (parser.index != typeName.length()) throw parser.invalid();
        return type;
    }

    private static final class Parser {
        final String text;
        int index;
        Parser(String text) { this.text = text; }

        TypeDescriptor readType() {
            int start = index;
            while (index < text.length() && "<,>".indexOf(text.charAt(index)) < 0) index++;
            String name = text.substring(start, index).trim();
            if (!take('<')) return of(name);
            List<TypeDescriptor> args = new ArrayList<>();
            do {
                args.add(readType());
            } while (take(','));
            if (!take('>')) throw invalid();
            return new TypeDescriptor(name, args, null);
        }

        boolean take(char c) {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
            if (index >= text.length() || text.charAt(index) != c) return false;
            index++;
            return true;
        }

        IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid type at position " + index + ": " + text);
        }
    }
}
