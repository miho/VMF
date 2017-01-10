package eu.mihosoft.vmf;

import eu.mihosoft.vmf.core.CodeGenerator;
import eu.mihosoft.vmf.core.FileResourceSet;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by miho on 03.01.2017.
 *
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
public class VMF {

    public static void main(String[] args) throws IOException {
        generate(new File("~/tmp/src-gen"), "eu.mihosoft.vmf.tutorial.vmfmodel");
    }

    /**
     * Generates code for the specified model definition.
     * @param outputDir output directory for the generated code, e.g., '<em>main/java/src-gen</em>'
     * @param interfaces model interfaces (all interfaces must be in the same package,
     *                   package must end with '.vmfmodel')
     * @throws IOException if the code generation fails due to I/O related problems
     */
    public static void generate(File outputDir, Class<?>... interfaces) throws IOException {
        new CodeGenerator().generate(new FileResourceSet(outputDir),interfaces);
    }

    /**
     * Generates code for the specified model definition.
     * @param outputDir output directory for the generated code, e.g., '<em>main/java/src-gen</em>'
     * @param packageName package that contains the model interfaces
     *                    (all interfaces must be in the same package, package must end with '.vmfmodel')
     * @throws IOException if the code generation fails due to I/O related problems
     */
    public static void generate(File outputDir, String packageName) throws IOException {
        Collection<Class<?>> interfaces = listClassesInPackage(packageName);
        generate(outputDir,interfaces.toArray(new Class[interfaces.size()]));
    }

    /**
     * Lists all classes in the specified package.
     * @param packageName package name
     * @return all classes in the specified package
     */
    private static Collection<Class<?>> listClassesInPackage(String packageName) {
            // thanks to
            // http://mike.shannonandmike.net/2009/09/02/java-reflecting-to-get-all-classes-in-a-package/
            Set<Class<?>> classes = new HashSet<>();
            String packageNameSlashed = packageName.replace(".", "/");
            // Get a File object for the package
            URL directoryURL = Thread.currentThread().getContextClassLoader().getResource(packageNameSlashed);
            if (directoryURL == null) {
                Logger.getLogger(VMF.class.getName()).log(
                        Level.WARNING,
                        "Could not retrieve URL resource: " + packageNameSlashed);
                return classes;
            }

            String directoryString = directoryURL.getFile();
            if (directoryString == null) {
                Logger.getLogger(VMF.class.getName()).log(
                        Level.WARNING,
                        "Could not find directory for URL resource: " + packageNameSlashed);
                return classes;
            }

            File directory = new File(directoryString);
            if (directory.exists()) {
                // Get the list of the files contained in the package
                String[] files = directory.list();
                for (String fileName : files) {
                    // We are only interested in .class files
                    if (fileName.endsWith(".class")) {
                        // Remove the .class extension
                        fileName = fileName.substring(0, fileName.length() - 6);
                        try {
                            classes.add(Class.forName(packageName + "." + fileName));
                        } catch (ClassNotFoundException e) {
                            Logger.getLogger(VMF.class.getName()).log(Level.WARNING,
                                    packageName + "." + fileName + " does not appear to be a valid class.", e);
                        }
                    }
                }
            } else {
                Logger.getLogger(VMF.class.getName()).log(
                        Level.WARNING,
                        packageName + " does not appear to exist as a valid package on the file system.");
            }
            return classes;
    }
}
