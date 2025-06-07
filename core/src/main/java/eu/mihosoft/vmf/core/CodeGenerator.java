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
package eu.mihosoft.vmf.core;

import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.io.Resource;
import eu.mihosoft.vmf.core.io.ResourceSet;
import eu.mihosoft.ext.velocity.legacy.VelocityContext;
import eu.mihosoft.ext.velocity.legacy.app.VelocityEngine;
import eu.mihosoft.ext.velocity.legacy.exception.ResourceNotFoundException;
import eu.mihosoft.ext.velocity.legacy.runtime.RuntimeConstants;
import eu.mihosoft.ext.velocity.legacy.runtime.RuntimeServices;
import eu.mihosoft.ext.velocity.legacy.runtime.log.LogChute;
import eu.mihosoft.ext.velocity.legacy.runtime.resource.loader.ClasspathResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default properties for the code generation engine.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
class VMFEngineProperties {

    public static final String VMF_TEMPLATE_PATH = "/eu/mihosoft/vmf/vmtemplates/";
    public static final String VMF_RUNTIME_API_PKG = "eu.mihosoft.vmf.runtime";

    public static final String VCOLL_PKG = "eu.mihosoft.vcollections";

    public static final String VMF_IMPL_PKG_EXT = "impl";
    public static final String VMF_IMPL_CLASS_EXT = "Impl";
    public static final String VMF_CORE_PKG_EXT = "core";
    
    public static void installProperties(VelocityContext ctx) {
        ctx.put("VMF_TEMPLATE_PATH", VMF_TEMPLATE_PATH);
        ctx.put("VMF_RUNTIME_API_PKG", VMF_RUNTIME_API_PKG);

        ctx.put("VMF_IMPL_PKG_EXT", VMF_IMPL_PKG_EXT);
        ctx.put("VMF_IMPL_CLASS_EXT", VMF_IMPL_CLASS_EXT);
        ctx.put("VMF_CORE_PKG_EXT", VMF_CORE_PKG_EXT);
        ctx.put("VCOLL_PKG", VCOLL_PKG);

        ctx.put("newline", "\n");
        ctx.put("StringUtil", StringUtil.class);
    }

}

/**
 * Code generator that generates Java code for the specified model instance.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
public class CodeGenerator {

    private static final String TEMPLATE_PATH = "/eu/mihosoft/vmf/vmtemplates/";
    private VelocityEngine engine;

    public CodeGenerator(VelocityEngine engine) {
        this.engine = engine;
    }

    /**
     * Constructor. Creates a new generator instance and initializes the velocity engine
     * used by the code generator.
     */
    public CodeGenerator() {
        try {
            this.engine = createDefaultEngine();
        } catch (Exception e) {
            throw new RuntimeException("Could not initiate default velocity engine.", e);
        }
    }

    /**
     * Generates template code for the specified template.
     * @param templateName template to use for code generation
     * @param ctx velocity context (contains model instance etc.)
     * @param out writer to use for code generation
     * @throws IOException if the code generation fails due to I/O related problems
     */
    protected void mergeTemplate(String templateName, VelocityContext ctx, Writer out) throws IOException {
        String path = resolveTemplatePath(templateName);
        engine.mergeTemplate(path, "UTF-8", ctx, out);
    }

    /**
     * Returns the full template path for the specified template name.
     * @param templateName name of the template
     * @return the full template path for the specified template name, e.g., {@code "mytemplate" } translates into {@code "${TEMPLATE_PATH}mytemplate.vm"}
     */
    static String resolveTemplatePath(String templateName) {
        return TEMPLATE_PATH + templateName + ".vm";
    }

    /**
     * Creates a velocity engine with all necessary defaults required by this code generator.
     * @return new velocity engine
     */
    static VelocityEngine createDefaultEngine() throws Exception{
        VelocityEngine engine = new VelocityEngine();

        engine.setProperty(RuntimeConstants.RESOURCE_LOADER, "vmf");
        engine.setProperty("vmf.resource.loader.instance", new VMFResourceLoader());
        VMFDefaultLogger.registerVelocityDefaultLogger(engine);

        return engine;
    }

    /**
     * Generates code for the specified model definition.
     * @param set resource-set that shall be used for code generation
     * @param classes model definition interfaces
     * @throws IOException if the code generation fails due to I/O related problems
     */
    public void generate(ResourceSet set, Class<?>... classes) throws IOException {
        Model model = Model.newInstance(classes);

        String packageName = generateModelTypeClasses(set, model);

        generateModelTypeVMFApiClasses(set, model, packageName);
    }

    /**
     * Generates model type api code, e.g., switches, listeners and cloneable interface for
     * the specified model definition.
     *
     * @param set resource-set that shall be used by this generator
     * @param model model to use as generator source
     * @param packageName package name of the generated code
     * @throws IOException if the code generation fails due to I/O related problems
     */
    private void generateModelTypeVMFApiClasses(ResourceSet set, Model model, String packageName) throws IOException {
        String[] packageComponents = packageName.split("\\.");
        String modelSwitchName = packageComponents[packageComponents.length-1];

        modelSwitchName =  modelSwitchName.substring(0, 1).toUpperCase() + modelSwitchName.substring(1);
        try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(packageName + ".SwitchFor"+modelSwitchName + "Model"))) {
            Writer out = res.open();
            generateVMFModelSwitchInterface(out, packageName, modelSwitchName, model);
        }

        modelSwitchName =  modelSwitchName.substring(0, 1).toUpperCase() + modelSwitchName.substring(1);
        try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(packageName + ".ReadOnlySwitchFor"+modelSwitchName + "Model"))) {
            Writer out = res.open();
            generateReadOnlyVMFModelSwitchInterface(out, packageName, modelSwitchName, model);
        }

        modelSwitchName =  modelSwitchName.substring(0, 1).toUpperCase() + modelSwitchName.substring(1);
        try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(packageName + ".ListenerFor"+modelSwitchName + "Model"))) {
            Writer out = res.open();
            generateVMFModelListenerInterface(out, packageName, modelSwitchName, model);
        }

        modelSwitchName =  modelSwitchName.substring(0, 1).toUpperCase() + modelSwitchName.substring(1);
        try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(packageName + ".ReadOnlyListenerFor"+modelSwitchName + "Model"))) {
            Writer out = res.open();
            generateReadOnlyVMFModelListenerInterface(out, packageName, modelSwitchName, model);
        }

//        // Model class
        try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(packageName + "." + modelSwitchName + "Model__VMF_API"))) {
            Writer out = res.open();
            generateVMFModelAPIInterface(out, packageName, modelSwitchName, model);
        }

        try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(packageName + "." + VMFEngineProperties.VMF_IMPL_PKG_EXT + ".VCloneableInternal"))) {
            Writer out = res.open();
            generateVMFCloneableInterface(out, packageName+ "." + VMFEngineProperties.VMF_IMPL_PKG_EXT);
        }
    }

    /**
     * Generates model type code for the specified model definition.
     * @param set resource-set that shall be used by this generator
     * @param model model to use as generator source
     * @return package name of the model code
     * @throws IOException if the code generation fails due to I/O related problems
     */
    private String generateModelTypeClasses(ResourceSet set, Model model) throws IOException {
        String packageName = null;

        for (ModelType t : model.getTypes()) {

            if (packageName == null) {
                packageName = t.getPackageName();
            }

            String[] packageComponents = packageName.split("\\.");
            String modelName = packageComponents[packageComponents.length-1];
            modelName =  modelName.substring(0, 1).toUpperCase() + modelName.substring(1);


            if(t.isImmutable()) {
                try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(t.getPackageName() + "." + t.getTypeName()))) {
                    Writer out = res.open();
                    generateImmutableTypeInterface(out, t, modelName);
                }
                if(!t.isInterfaceOnly()) {
                    try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(t.getPackageName() + "." + VMFEngineProperties.VMF_IMPL_PKG_EXT + "."
                            + t.getImplementation().getTypeName()))) {
                        Writer out = res.open();
                        generateImmutableTypeImplementation(out, t, modelName);
                    }
                }
            } else {

                try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(t.getPackageName() + "." + t.getTypeName()))) {
                    Writer out = res.open();
                    generateTypeInterface(out, t, modelName);
                }

                try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(t.getPackageName() + "." + t.getReadOnlyInterface().getTypeName()))) {
                    Writer out = res.open();
                    generateReadOnlyTypeInterface(out, t, modelName);
                }

                if(t.isInterfaceOnly()) {
                    try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(t.getPackageName() + "." + VMFEngineProperties.VMF_IMPL_PKG_EXT + ".__VMF_TYPE_" + t.getTypeName()))) {
                        Writer out = res.open();
                        generateInternalTypeInterface(out, t, modelName);
                    }
                } else {
                    try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(t.getPackageName() + "." + VMFEngineProperties.VMF_IMPL_PKG_EXT + "."
                            + t.getImplementation().getTypeName()))) {
                        Writer out = res.open();
                        generateTypeImplementation(out, t, modelName);
                    }

                    try (Resource res = set.open(TypeUtil.computeFileNameFromJavaFQN(t.getPackageName() + "." + VMFEngineProperties.VMF_IMPL_PKG_EXT + "."
                            + t.getReadOnlyImplementation().getTypeName()))) {
                        Writer out = res.open();
                        generateReadOnlyTypeImplementation(out, t, modelName);
                    }
                }
            }
        }
        return packageName;
    }

    private void generateVMFModelSwitchInterface(Writer out, String packageName, String modelSwitchName, Model m) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        context.put("model", m);
        context.put("modelName", modelSwitchName);
        context.put("modelSwitchName", modelSwitchName);        
        mergeTemplate("vmf-model-switch-interface", context, out);
    }
    
    private void generateReadOnlyVMFModelSwitchInterface(Writer out, String packageName, String modelSwitchName, Model m) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        context.put("model", m);
        context.put("modelName", modelSwitchName);
        context.put("modelSwitchName", modelSwitchName);        
        mergeTemplate("vmf-model-switch-read-only-interface", context, out);
    }

    private void generateVMFModelListenerInterface(Writer out, String packageName, String modelSwitchName, Model m) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        context.put("model", m);
        context.put("modelName", modelSwitchName);
        context.put("modelSwitchName", modelSwitchName);
        mergeTemplate("vmf-model-traversal-listener-interface", context, out);
    }

    private void generateReadOnlyVMFModelListenerInterface(Writer out, String packageName, String modelSwitchName, Model m) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        context.put("model", m);
        context.put("modelName", modelSwitchName);
        context.put("modelSwitchName", modelSwitchName);
        mergeTemplate("vmf-model-traversal-listener-read-only-interface", context, out);
    }

    private void generateVMFModelAPIInterface(Writer out, String packageName, String modelName, Model m) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        context.put("model", m);
        context.put("modelName", modelName);
        mergeTemplate("vmf-model-class", context, out);
    }

    private void generateImmutableVMFModelSwitchInterface(Writer out, String packageName, String modelSwitchName, Model m) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        context.put("model", m);
        context.put("modelName", modelSwitchName);
        context.put("modelSwitchName", modelSwitchName);
        mergeTemplate("vmf-model-switch-immutable-interface", context, out);
    }

    private void generateVMFCloneableInterface(Writer out, String packageName) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        mergeTemplate("vmf-vcloneable-internal", context, out);
    }

    private void generateObservableObjectUtil(Writer out, String packageName) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        mergeTemplate("vmfutil-observableobject", context, out);
    }

    private void generateVObjectUtil(Writer out, String packageName) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        mergeTemplate("vmfutil-vobject", context, out);
    }

    private void generateVContainmentUtil(Writer out, String packageName) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        mergeTemplate("vmfutil-vcontainmentutil", context, out);
    }

    private void generateVCollectionUtil(Writer out, String packageName) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        mergeTemplate("vmfutil-vcollectionutil", context, out);
    }

    private void generateTypeInterface(Writer out, ModelType t, String modelName) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("modelName", modelName);
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("interface", context, out);
    }

    private void generateInternalTypeInterface(Writer out, ModelType t, String modelName) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("modelName", modelName);
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("interface__vmf_type", context, out);
    }

    private void generateWritableTypeInterface(Writer out, ModelType t, String modelName) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("modelName", modelName);
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("writable-interface", context, out);
    }

    private void generateReadOnlyTypeInterface(Writer out, ModelType t, String modelName) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("modelName", modelName);
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("read-only-interface", context, out);
    }

    private void generateTypeImplementation(Writer out, ModelType t, String modelName) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("modelName", modelName);
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("implementation", context, out);
    }

    private void generateReadOnlyTypeImplementation(Writer out, ModelType t, String modelName) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("modelName", modelName);
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("read-only-implementation", context, out);
    }

    private void generateImmutableTypeInterface(Writer out, ModelType t, String modelName) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("modelName", modelName);
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("immutable-interface", context, out);
    }

    private void generateImmutableTypeImplementation(Writer out, ModelType t, String modelName) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("modelName", modelName);
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("immutable-implementation", context, out);
    }
}

class VMFResourceLoader extends ClasspathResourceLoader {

    /**
     * Get an InputStream so that the Runtime can build a template with it.
     *
     * @param name name of template to get
     * @return InputStream containing the template
     * @throws ResourceNotFoundException if template not found in classpath.
     */
    public InputStream getResourceStream(String name)
            throws ResourceNotFoundException {
        InputStream input = VMF.class.getResourceAsStream(name);

        return input;
    }

}

/**
 * Dummy logger to prevent velocity from crashing if it is used in combination with other
 * log4j configurations. We should consider updating from this old velocity release to 
 * either a more modern version of velocity or switching to stringtemplates4.
 */
class VMFDefaultLogger implements LogChute
{

    public static void registerVelocityDefaultLogger(VelocityEngine engine) {
        VMFDefaultLogger logger = new VMFDefaultLogger();
        engine.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM, logger );
    }

    private VMFDefaultLogger() {

    }

    /**
     *  This init() will be invoked once by the LogManager
     *  to give you the current RuntimeServices intance
     */
    public void init(RuntimeServices rsvc)
    {
        // do nothing
    }

    /**
     *  This is the method that you implement for Velocity to
     *  call with log messages.
     */
    public void log(int level, String message)
    {
        // Logger.getLogger(this.getClass().getName()).
        //             log(Level.SEVERE, null, message);
    }

    /**
     *  This is the method that you implement for Velocity to
     *  call with log messages.
     */
    public void log(int level, String message, Throwable t)
    {
        // Logger.getLogger(this.getClass().getName()).
        // log(Level.SEVERE, message, t);
    }

    /**
     *  This is the method that you implement for Velocity to
     *  check whether a specified log level is enabled.
     */
    public boolean isLevelEnabled(int level)
    {
        return false;
    }

}
