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

import eu.mihosoft.vmf.core.codegen.ResolvedModel.MetadataAnnotation;

import java.util.List;
import java.util.stream.Collectors;

final class CSharpAnnotationRenderModel {
    private CSharpAnnotationRenderModel() {
    }

    static String reflectionInitializer(List<MetadataAnnotation> annotations) {
        return reflectionInitializer(annotations, "");
    }

    static String reflectionInitializer(List<MetadataAnnotation> annotations, String additionalMember) {
        if (annotations.isEmpty()) return additionalMember.isEmpty() ? "" : " { " + additionalMember + " }";
        String entries = annotations.stream().map(annotation -> "new("
                        + CSharpSnapshotRenderModel.literal(annotation.getKey()) + ", "
                        + CSharpSnapshotRenderModel.literal(annotation.getValue()) + ")")
                .collect(Collectors.joining(", "));
        return " { Annotations = new global::Vmf.Runtime.ModelAnnotation[] { " + entries + " }"
                + (additionalMember.isEmpty() ? "" : ", " + additionalMember) + " }";
    }
}
