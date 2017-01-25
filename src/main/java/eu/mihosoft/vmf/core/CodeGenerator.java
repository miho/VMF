package eu.mihosoft.vmf.core;

import eu.mihosoft.vmf.VMF;
import org.apache.commons.collections.ExtendedProperties;
import org.apache.commons.lang.StringUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.apache.velocity.runtime.resource.loader.ResourceLoader;
import org.apache.velocity.util.ClassUtils;
import org.apache.velocity.util.ExceptionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.Properties;

class VMFEngineProperties {

    public static final String VMF_TEMPLATE_PATH = "/eu/mihosoft/vmf/vmtemplates/";
    public static final String VMF_CORE_API_PKG = "eu.mihosoft.vmf.runtime.core";

    public static final String VCOLL_PKG = "eu.mihosoft.vcollections";

    public static final String VMF_IMPL_PKG_EXT = "impl";
    public static final String VMF_IMPL_CLASS_EXT = "Impl";
    public static final String VMF_VMFUTIL_PKG_EXT = "vmfutil";

    public static void installProperties(VelocityContext ctx) {
        ctx.put("VMF_TEMPLATE_PATH", VMF_TEMPLATE_PATH);
        ctx.put("VMF_CORE_API_PKG", VMF_CORE_API_PKG);

        ctx.put("VMF_IMPL_PKG_EXT", VMF_IMPL_PKG_EXT);
        ctx.put("VMF_IMPL_CLASS_EXT", VMF_IMPL_CLASS_EXT);
        ctx.put("VMF_VMFUTIL_PKG_EXT", VMF_VMFUTIL_PKG_EXT);
        ctx.put("VCOLL_PKG", VCOLL_PKG);

        ctx.put("newline", "\n");
    }
}

public class CodeGenerator {

    private static final String TEMPLATE_PATH = "/eu/mihosoft/vmf/vmtemplates/";
    private VelocityEngine engine;

    public CodeGenerator(VelocityEngine engine) {
        this.engine = engine;
    }

    public CodeGenerator() {
        try {
            this.engine = createDefaultEngine();
        } catch (Exception e) {
            throw new RuntimeException("Could not initiate default velocity engine.", e);
        }
    }

    protected void mergeTemplate(String name, VelocityContext ctx, Writer out) throws IOException {
        String path = resolveTemplatePath(name);
        engine.mergeTemplate(path, "UTF-8", ctx, out);
    }

    public static String resolveTemplatePath(String templateName) {
        return TEMPLATE_PATH + templateName + ".vm";
    }

    public static VelocityEngine createDefaultEngine() throws Exception {
        VelocityEngine engine = new VelocityEngine();

        engine.setProperty(RuntimeConstants.RESOURCE_LOADER, "vmf");
        engine.setProperty("vmf.resource.loader.instance", new VMFResourceLoader());

        return engine;
    }

    public void generate(ResourceSet set, Class<?>... classes) throws IOException {
        Model model = Model.newInstance(classes);

        String packageName = null;

        for (ModelType t : model.getTypes()) {

            if (packageName == null) {
                packageName = t.getPackageName();
            }

            try (Resource res = set.open(t.getPackageName() + "." + t.getTypeName())) {
                Writer out = res.open();
                generateTypeInterface(out, t);
            }

//            try (Resource res = set.open(t.getPackageName() + ".Writable" + t.getTypeName())) {
//                Writer out = res.open();
//                generateWritableTypeInterface(out, t);
//            }
            try (Resource res = set.open(t.getPackageName() + "." + t.getReadOnlyInterface().getTypeName())) {
                Writer out = res.open();
                generateReadOnlyTypeInterface(out, t);
            }

            try (Resource res = set.open(t.getPackageName() + "." + VMFEngineProperties.VMF_IMPL_PKG_EXT + "."
                    + t.getImplementation().getTypeName())) {
                Writer out = res.open();
                generateTypeImplementation(out, t);
            }

            try (Resource res = set.open(t.getPackageName() + "." + VMFEngineProperties.VMF_IMPL_PKG_EXT + "."
                    + t.getReadOnlyImplementation().getTypeName())) {
                Writer out = res.open();
                generateReadOnlyTypeImplementation(out, t);
            }

        }

//        try (Resource res = set.open(packageName + "." + VMFEngineProperties.VMF_IMPL_PKG_EXT + ".VMFModelWalker" + VMFEngineProperties.VMF_IMPL_CLASS_EXT)) {
//            Writer out = res.open();
//            generateVMFModelWalker(out, packageName+ "." + VMFEngineProperties.VMF_IMPL_PKG_EXT, model);
//        }
//
//        try (Resource res = set.open(packageName + "." + ".ModelWalker")) {
//            Writer out = res.open();
//            generateVMFModelWalkerInterface(out, packageName, model);
//        }

        try (Resource res = set.open(packageName + "." + VMFEngineProperties.VMF_IMPL_PKG_EXT + ".VObjectInternal")) {
            Writer out = res.open();
            generateVMFVObjectInternalInterface(out, packageName+ "." + VMFEngineProperties.VMF_IMPL_PKG_EXT);
        }

        try (Resource res = set.open(packageName + "." + VMFEngineProperties.VMF_IMPL_PKG_EXT + ".VCloneableInternal")) {
            Writer out = res.open();
            generateVMFCloneableInterface(out, packageName+ "." + VMFEngineProperties.VMF_IMPL_PKG_EXT);
        }

        try (Resource res = set.open(VMFEngineProperties.VMF_CORE_API_PKG + "." + VMFEngineProperties.VMF_VMFUTIL_PKG_EXT + ".VContainmentUtil")) {
            Writer out = res.open();
            generateVContainmentUtil(out, packageName);
        }

        try (Resource res = set.open(VMFEngineProperties.VMF_CORE_API_PKG + "." + VMFEngineProperties.VMF_VMFUTIL_PKG_EXT + ".VObject")) {
            Writer out = res.open();
            generateVObjectUtil(out, packageName);
        }

        try (Resource res = set.open(VMFEngineProperties.VMF_CORE_API_PKG + "." + VMFEngineProperties.VMF_VMFUTIL_PKG_EXT + ".ObservableObject")) {
            Writer out = res.open();
            generateObservableObjectUtil(out, packageName);
        }

        try (Resource res = set.open(VMFEngineProperties.VMF_CORE_API_PKG + "." + VMFEngineProperties.VMF_VMFUTIL_PKG_EXT + ".VCollectionUtil")) {
            Writer out = res.open();
            generateVCollectionUtil(out, packageName);
        }
    }

    private void generateVMFModelWalker(Writer out, String packageName, Model m) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        context.put("model", m);
        mergeTemplate("vmf-model-walker-implementation", context, out);
    }

    private void generateVMFModelWalkerInterface(Writer out, String packageName, Model m) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        context.put("model", m);
        mergeTemplate("vmf-model-walker-interface", context, out);
    }

    private void generateVMFVObjectInternalInterface(Writer out, String packageName) throws IOException {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        mergeTemplate("vmf-vobject-internal", context, out);
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

    public void generateTypeInterface(Writer out, ModelType t) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("interface", context, out);
    }

    public void generateWritableTypeInterface(Writer out, ModelType t) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("writable-interface", context, out);
    }

    public void generateReadOnlyTypeInterface(Writer out, ModelType t) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("read-only-interface", context, out);
    }

    public void generateTypeImplementation(Writer out, ModelType t) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("implementation", context, out);
    }

    public void generateReadOnlyTypeImplementation(Writer out, ModelType t) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("read-only-implementation", context, out);
    }

//    public void generateFactory(Writer out, Model model) throws Exception {
//        VelocityContext context = new VelocityContext();
//        context.put("model", model);
//        mergeTemplate("factory", context, out);
//    }
//
//    public void generateVisitor(Writer out, Model model) throws Exception {
//        VelocityContext context = new VelocityContext();
//        context.put("model", model);
//        mergeTemplate("visitor", context, out);
//    }
//
//    public void generateCommands(Writer out, Model model) throws Exception {
//        VelocityContext context = new VelocityContext();
//        context.put("model", model);
//        mergeTemplate("commands", context, out);
//    }
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
