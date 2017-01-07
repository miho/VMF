package eu.mihosoft.vmf.playground;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import java.io.Writer;
import java.util.Properties;

class VMFEngineProperties {
    public static final String VMF_TEMPLATE_PATH = "eu/mihosoft/vmf/vmtemplates/";
    public static final String VMF_CORE_API_PKG = "eu.mihosoft.vmf.core";

    public static void installProperties(VelocityContext ctx) {
        ctx.put("VMF_TEMPLATE_PATH", VMF_TEMPLATE_PATH);
        ctx.put("VMF_CORE_API_PKG", VMF_CORE_API_PKG);
    }
}

public class ModelGenerator {

    private static final String TEMPLATE_PATH = "eu/mihosoft/vmf/vmtemplates/";
    private VelocityEngine engine;

    public ModelGenerator(VelocityEngine engine) {
        this.engine = engine;
    }

    public ModelGenerator() {
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
        for (Type t : model.getTypes()) {
            try (Resource res = set.open(t.getClzzPackageName() + "." + t.getClzzName())) {
                Writer out = res.open();
                generateTypeInterface(out, t);
            }
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

    public void generateTypeInterface(Writer out, Type t) throws Exception {
        VelocityContext context = new VelocityContext();
        context.put("type", t);
        VMFEngineProperties.installProperties(context);
        mergeTemplate("interface", context, out);
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
