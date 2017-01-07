package eu.mihosoft.vmf.core;


import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import java.io.Writer;
import java.util.Properties;

class VMFEngineProperties {
    public static final String VMF_TEMPLATE_PATH = "eu/mihosoft/vmf/vmtemplates/";
    public static final String VMF_CORE_API_PKG = "eu.mihosoft.vmf.core";

    public static final String VMF_IMPL_PKG_EXT = "impl";
    public static final String VMF_IMPL_CLASS_EXT = "Impl";
    public static final String VMF_VMFUTIL_PKG_EXT = "impl.vmfutil";

    public static void installProperties(VelocityContext ctx) {
        ctx.put("VMF_TEMPLATE_PATH", VMF_TEMPLATE_PATH);
        ctx.put("VMF_CORE_API_PKG", VMF_CORE_API_PKG);

        ctx.put("VMF_IMPL_PKG_EXT", VMF_IMPL_PKG_EXT);
        ctx.put("VMF_IMPL_CLASS_EXT", VMF_IMPL_CLASS_EXT);
        ctx.put("VMF_VMFUTIL_PKG_EXT", VMF_VMFUTIL_PKG_EXT);
    }
}

public class CodeGenerator {

    private static final String TEMPLATE_PATH = "eu/mihosoft/vmf/vmtemplates/";
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

    protected void mergeTemplate(String name, VelocityContext ctx, Writer out) throws Exception {
        String path = resolveTemplatePath(name);
        engine.mergeTemplate(path, "UTF-8", ctx, out);
    }

    public static String resolveTemplatePath(String templateName) {
        return TEMPLATE_PATH + templateName + ".vm";
    }

    public static VelocityEngine createDefaultEngine() throws Exception {
        Properties props = new Properties();
        props.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
        props.setProperty("classpath.resource.loader.class",
                ClasspathResourceLoader.class.getName());

        return new VelocityEngine(props);
    }

    public void generate(ResourceSet set, Class<?>... classes) throws Exception {
        Model model = new Model();
        model.init(classes);

        String packageName = null;

        for (ModelType t : model.getTypes()) {

            if(packageName == null) {
                packageName = t.getPackageName();
            }

            try (Resource res = set.open(t.getPackageName() + "." + t.getTypeName())) {
                Writer out = res.open();
                generateTypeInterface(out, t);
            }

            try (Resource res = set.open(t.getPackageName() + ".Writable" + t.getTypeName())) {
                Writer out = res.open();
                generateWritableTypeInterface(out, t);
            }

            try (Resource res = set.open(t.getPackageName()+"." + VMFEngineProperties.VMF_IMPL_PKG_EXT + "."
                    + t.getImplementation().getTypeName())) {
                Writer out = res.open();
                generateTypeImplementation(out, t);
            }



//        try(Resource factoryRes = set.open(model.getPackage() + ".ModelFactory")) {
//            Writer out = factoryRes.open();
//            generateFactory(out, model);
//        }
//
//        try(Resource visitorRes = set.open(model.getPackage() + ".ModelVisitor")) {
//            Writer out = visitorRes.open();
//            generateVisitor(out, model);
//        }
//
//        try(Resource commandRes = set.open(model.getPackage() + ".ModelCommandFactory")) {
//            Writer out = commandRes.open();
//            generateCommands(out, model);
//        }

        }

        try (Resource res = set.open(packageName+"." + VMFEngineProperties.VMF_VMFUTIL_PKG_EXT + ".VContainmentUtil")) {
            Writer out = res.open();
            generateVContainmentUtil(out, packageName);
        }

        try (Resource res = set.open(packageName+"." + VMFEngineProperties.VMF_VMFUTIL_PKG_EXT + ".VObject")) {
            Writer out = res.open();
            generateVObjectUtil(out, packageName);
        }

        try (Resource res = set.open(packageName+"." + VMFEngineProperties.VMF_VMFUTIL_PKG_EXT + ".ObservableObject")) {
            Writer out = res.open();
            generateObservableObjectUtil(out, packageName);
        }
    }

    private void generateObservableObjectUtil(Writer out, String packageName) throws Exception {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        mergeTemplate("vmfutil-observableobject", context, out);
    }

    private void generateVObjectUtil(Writer out, String packageName) throws Exception {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        mergeTemplate("vmfutil-vobject", context, out);
    }

    private void generateVContainmentUtil(Writer out, String packageName) throws Exception {
        VelocityContext context = new VelocityContext();
        VMFEngineProperties.installProperties(context);
        context.put("packageName", packageName);
        mergeTemplate("vmfutil-vcontainmentutil", context, out);
    }

    public void generateTypeInterface(Writer out, ModelType t) throws Exception {
        VelocityContext context = new VelocityContext();
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("interface", context, out);
    }

    public void generateWritableTypeInterface(Writer out, ModelType t) throws Exception {
        VelocityContext context = new VelocityContext();
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("writable-interface", context, out);
    }

    public void generateTypeImplementation(Writer out, ModelType t) throws Exception {
        VelocityContext context = new VelocityContext();
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("implementation", context, out);
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
