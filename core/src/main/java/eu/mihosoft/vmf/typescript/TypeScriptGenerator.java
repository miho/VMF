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

import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.io.Resource;
import eu.mihosoft.vmf.core.io.ResourceSet;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** TypeScript source backend for the portable VMF model. */
public final class TypeScriptGenerator {
    private static final String MODEL_TEMPLATE = "eu/mihosoft/vmf/typescript/model.vm";

    private TypeScriptGenerator() {
    }

    /**
     * Generates and writes the TypeScript model module from Java VMF declarations.
     *
     * @param output generated source destination
     * @param declarations Java VMF model definitions
     * @throws IOException if the generated source cannot be written
     * @throws IllegalArgumentException if the model requires unsupported TypeScript features
     */
    public static void generate(ResourceSet output, Class<?>... declarations) throws IOException {
        Objects.requireNonNull(output, "output");
        ResolvedModel model = ResolvedModel.from(Model.newInstance(declarations), declarations);
        PortableCapabilities.validate(model, "TypeScript");
        TypeScriptRenderModel prepared = new TypeScriptRenderModel(model);

        StringWriter rendered = new StringWriter();
        VelocityContext context = new VelocityContext();
        context.put("types", prepared.getTypes());
        context.put("supertypeDeclarations", prepared.getSupertypeDeclarations());
        context.put("registrations", prepared.getSnapshots().getRegistrations());
        context.put("aliases", prepared.getSnapshots().getAliases());
        Engine.INSTANCE.getTemplate(MODEL_TEMPLATE, StandardCharsets.UTF_8.name()).merge(context, rendered);

        // Do not open output until model checks and strict template rendering succeed.
        String source = rendered.toString().replace("\r\n", "\n");
        try (Resource resource = output.open(prepared.getOutputPath())) {
            PrintWriter writer = resource.open();
            writer.write(source);
            if (writer.checkError()) {
                throw new IOException("Could not write " + prepared.getOutputPath());
            }
        }
    }

    private static final class Engine {
        private static final VelocityEngine INSTANCE = create();

        private static VelocityEngine create() {
            VelocityEngine engine = new VelocityEngine();
            engine.setProperty("resource.loaders", "classpath");
            engine.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
            engine.setProperty("runtime.strict_mode.enable", true);
            engine.setProperty("parser.space_gobbling", "lines");
            engine.init();
            return engine;
        }
    }
}
