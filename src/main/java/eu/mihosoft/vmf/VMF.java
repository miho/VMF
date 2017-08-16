package eu.mihosoft.vmf;

import eu.mihosoft.vmf.core.CodeGenerator;
import eu.mihosoft.vmf.core.FileResourceSet;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Created by miho on 03.01.2017.
 *
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
public class VMF {

    /**
     * Generates code for the specified model definition.
     *
     * @param outputDir output directory for the generated code, e.g., '<em>main/java/src-gen</em>'
     * @param interfaces model interfaces (all interfaces must be in the same package,
     *                   package must end with '.vmfmodel')
     * @throws IOException if the code generation fails due to I/O related problems
     * @throws IllegalArgumentException if the specified model is empty
     */
    public static void generate(File outputDir, Class<?>... interfaces) throws IOException {
        new CodeGenerator().generate(new FileResourceSet(outputDir),interfaces);
    }

    /**
     * Generates code for the specified model definition. The context classloader of the current thread is used to
     * locate the model interfaces.
     *
     * @param outputDir output directory for the generated code, e.g., '<em>main/java/src-gen</em>'
     * @param packageName package that contains the model interfaces
     *                    (all interfaces must be in the same package, package must end with '.vmfmodel')
     * @throws IOException if the code generation fails due to I/O related problems
     * @throws IllegalArgumentException if the specified model is empty
     */
    public static void generate(File outputDir, String packageName) throws IOException {
        Collection<Class<?>> interfaces = listClassesInPackage(
                Thread.currentThread().getContextClassLoader(), packageName);
        generate(outputDir,interfaces.toArray(new Class[interfaces.size()]));
    }

    /**
     * Generates code for the specified model definition.
     *
     * @param outputDir output directory for the generated code, e.g., '<em>main/java/src-gen</em>'
     * @param classLoader the classloader that shall be used to locate the model interfaces in the specified package
     * @param packageName package that contains the model interfaces
     *                    (all interfaces must be in the same package, package must end with '.vmfmodel')
     * @throws IOException if the code generation fails due to I/O related problems
     * @throws IllegalArgumentException if the specified model is empty
     */
    public static void generate(File outputDir, ClassLoader classLoader, String packageName) throws IOException {
        Collection<Class<?>> interfaces = listClassesInPackage(classLoader, packageName);
        generate(outputDir,interfaces.toArray(new Class[interfaces.size()]));
    }

    /**
     * Lists all classes in the specified package.
     * @param classLoader classloader used for searching the package content
     * @param packageName package name
     * @return all classes in the specified package
     *
     * @throws IllegalArgumentException if the specified model is empty
     */
    private static Collection<Class<?>> listClassesInPackage(ClassLoader classLoader, String packageName) {
        List<String> clsNames = new FastClasspathScanner(packageName).overrideClassLoaders(classLoader)
                .scan()
                .getNamesOfAllClasses();

        return clsNames.stream().map(clsName->loadClass(classLoader, clsName)).
                filter(cls->cls!=null).filter(cls->{
                    // annotations from other packages are allowed
                    // we remove annotations from other packages from this list
                    // other classes from other packages are not removed since we generate
                    // an error message in this case
                    return Objects.equals(cls.getPackage().getName(),packageName)||!cls.isAnnotation();
                }).
                collect(Collectors.toList());
    }

    /**
     * Loads a class from a specified classloader.
     * @param classLoader classloader that shall be used for classloading
     * @param clsName the name of the class to load
     * @return the reuested class or {@code null} if the requested class cannot be found
     *
     * @throws IllegalArgumentException if the specified model is empty
     */
    private static Class<?> loadClass(ClassLoader classLoader,String clsName) {
        try {
            return classLoader.loadClass(clsName);
        } catch (ClassNotFoundException e) {
            //
        }

        return null;
    }

}
