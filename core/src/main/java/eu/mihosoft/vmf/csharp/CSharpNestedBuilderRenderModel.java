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
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.Map;

/** Describes deferred inputs that generated mutable and immutable builders share. */
final class CSharpNestedBuilderRenderModel {
    private static final String RUNTIME = "global::Vmf.Runtime.";
    private static final String GENERIC = "global::System.Collections.Generic.";
    private static final String LINQ = "global::System.Linq.Enumerable.";

    private CSharpNestedBuilderRenderModel() {
    }

    static Map<String, String> render(CSharpRenderModel source, Property property, String name) {
        if (property.getKind() == Property.Kind.MAP || property.getValueType().getKind() != ValueType.Kind.MODEL) {
            return Map.of();
        }
        String builder = RUNTIME + "IModelBuilder<" + source.propertyValueTypeName(property) + ">";
        String field = "__vmfNested" + name;
        if (!property.isCollection()) {
            return Map.of("field", field, "type", builder + "?", "input", builder + "?",
                    "suffix", "Builder", "stage", "value", "build", field + ".Build()");
        }
        String element = builder + (property.allowsNullElements() ? "?" : "");
        String selector = property.allowsNullElements() ? "builder?.Build()"
                : "(builder ?? throw new global::System.ArgumentException(\"Null builder in the modeled collection.\")).Build()";
        return Map.of("field", field, "type", element + "[]?", "input", GENERIC + "IEnumerable<" + element + ">",
                "suffix", "Builders", "stage", LINQ + "ToArray(value)",
                "build", LINQ + "ToArray(" + LINQ + "Select(" + field + ", static builder => " + selector + "))");
    }
}
