package eu.mihosoft.vmf.gradle.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile


import org.gradle.api.Plugin;
import org.gradle.api.internal.*;
import org.gradle.api.internal.file.*;
import org.gradle.api.internal.file.collections.*;
import org.gradle.api.internal.tasks.*;
import org.gradle.api.internal.plugins.DslObject;
import org.gradle.api.*;
import org.codehaus.groovy.runtime.InvokerHelper;
//
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.internal.file.SourceDirectorySetFactory;
import org.gradle.api.reflect.HasPublicType;
import org.gradle.api.reflect.TypeOf
import org.gradle.api.tasks.incremental.IncrementalTaskInputs;
import org.gradle.util.ConfigureUtil;
import javax.inject.Inject
import java.util.stream.Collectors;
import groovy.grape.Grape;

import static org.gradle.api.reflect.TypeOf.typeOf;

/*
 * **NOTE:**
 *
 * This code is heavily influenced by the ANTLR plugin that ships with Gradle:
 *
 * -> see: https://github.com/gradle/gradle/tree/master/subprojects/antlr/src/main/java/org/gradle/api/plugins/antlr
 *
 * TODO 22.07.2018 find out whether adding a custom language folder, such as 'vmf-text' really needs that much code.
 */

/**
 * Contract for a Gradle "convention object" that acts as a handler for what the developer of the ANTLR gradle
 * plugin calls 'a virtual directory mapping',
 * injecting a virtual directory named 'vmf' into the project's various
 * {@link org.gradle.api.tasks.SourceSet source sets}.
 */
interface VMFSourceVirtualDirectory {
    String NAME = "vmf";

    /**
     * All VMF source for this source set.
     *
     * @return The VMF source.  Never returns null.
     */
    SourceDirectorySet getVMF();

    /**
     * Configures the VMF source for this set. The given closure is used to configure the
     * {@link org.gradle.api.file.SourceDirectorySet} (see {@link #getVMF}) which contains the VMF source.
     *
     * @param configureClosure the closure to use to configure the VMF source.
     * @return this
     */
    VMFSourceVirtualDirectory vmf(Closure configureClosure);

    /**
     * Configures the VMF source for this set. The given action is used to configure the
     * {@link org.gradle.api.file.SourceDirectorySet} (see
     * {@link #getVMF}) which contains the VMF source.
     *
     * @param configureAction The action to use to configure the VMF source.
     * @return this
     */
    VMFSourceVirtualDirectory vmf(Action<? super SourceDirectorySet> configureAction);
}

/**
 * The implementation of the {@link VMFSourceVirtualDirectory} contract.
 */
class VMFSourceVirtualDirectoryImpl implements VMFSourceVirtualDirectory, HasPublicType {
    private final SourceDirectorySet vmf;

    public VMFSourceVirtualDirectoryImpl(String parentDisplayName, SourceDirectorySetFactory sourceDirectorySetFactory) {
        final String displayName = parentDisplayName + " VMF source";
        vmf = sourceDirectorySetFactory.create(displayName);
        vmf.getFilter().include("**/*.java");
    }

    @Override
    public SourceDirectorySet getVMF() {
        return vmf;
    }

    @Override
    public VMFSourceVirtualDirectory vmf(Closure configureClosure) {
        ConfigureUtil.configure(configureClosure, getVMF());
        return this;
    }

    @Override
    public VMFSourceVirtualDirectory vmf(Action<? super SourceDirectorySet> configureAction) {
        configureAction.execute(getVMF());
        return this;
    }

    @Override
    public TypeOf<?> getPublicType() {
        return typeOf(VMFSourceVirtualDirectory.class);
    }
}

class VMFPluginExtension {
    // vmf version
    // String version  = "0.1.13"
    String version  = "0.2-SNAPSHOT"
}

class VMFPlugin implements Plugin<Project> {

    private SourceDirectorySetFactory sourceDirectorySetFactory;

    @Inject
    public VMFPlugin(SourceDirectorySetFactory sourceDirectorySetFactory) {
        this.sourceDirectorySetFactory = sourceDirectorySetFactory;
    }

    @Override
    void apply(Project project) {


        project.getPluginManager().apply(JavaPlugin.class)


        // add the 'vmf' extension object
        def extension = project.extensions.create('vmf', VMFPluginExtension)


        project.configurations {
            vmf {
                extendsFrom compile
            }
        }




        // apply the java plugin


//        // we add a vmf configuration to the project
//        project.configurations {
//            vmf {
//                extendsFrom compile
//            }
//        }

//        // and the corresponding source sets
//        project.sourceSets {
//            main {
//                java {
//                    srcDirs = ["$project.buildDir/vmf-src-gen", 'src/main/java']
//                }
//            }
//
//            vmf {
//                // just default code locations
//            }
//        }

        project.repositories {
            mavenLocal()
            mavenCentral()
            jcenter()
        }


        // def vmfClass = eu.mihosoft.vmf.VMF.class;

        project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().all(
                new Action<SourceSet>() {
                    public void execute(final SourceSet sourceSet) {

                        println("!!! SOURCESET: " + sourceSet.name)

                        // For each source set we will:

//                        // 0) Add model dependencies to url array
//                        def urls = []
//                        for(File clsDir : sourceSet.output.classesDirs) {
//                            urls.add(new File(clsDir.toString())
//                                    .toURI().toURL())
//                        }

                        //
                        // 1) Add a new 'vmf' virtual directory mapping
                        final VMFSourceVirtualDirectoryImpl vmfDirectoryDelegate =
                                new VMFSourceVirtualDirectoryImpl(
                                        ((DefaultSourceSet) sourceSet).getDisplayName(),
                                        sourceDirectorySetFactory
                                );
                        new DslObject(sourceSet).getConvention().getPlugins().put(
                                VMFSourceVirtualDirectory.NAME, vmfDirectoryDelegate
                        );
                        final String srcDir = "src/" + sourceSet.getName() + "/vmf";
                        SourceDirectorySet sourceDirectorySet = vmfDirectoryDelegate.getVMF();
                        sourceDirectorySet.srcDir(srcDir);
                        sourceSet.getAllSource().source(vmfDirectoryDelegate.getVMF());

                        // 2) Create an VMFTask for this sourceSet following the gradle
                        //    naming conventions via call to sourceSet.getTaskName()
                        final String taskName = sourceSet.getTaskName("vmfGenModelSources", "Code");

                        // 3) Set up the VMF output directory (adding to javac inputs!)
                        final String outputDirectoryName =
                                project.getBuildDir().absolutePath+"/generated-src/vmf" + sourceSet.getName();
                        final File outputDirectory = new File(outputDirectoryName);
                        sourceSet.getJava().srcDir(outputDirectory);

                        // TODO 22.01.2019 do we need to create a compile task for the source set or is this done automatically?
                        JavaPlugin javaPlugin =   project.getPlugins().findPlugin(JavaPlugin.class)

                        project.getTasks().create(taskName, CompileVMFTask.class, new Action<CompileVMFTask>() {
                            @Override
                            void execute(CompileVMFTask vmfTask) {
                                // 4) Set up convention mapping for default sources (allows user to not have to specify)
                                //    and set up a task description
                                vmfTask.setDescription(
                                        "Generates Java Code for VMF models defined in the " + sourceSet.getName() + " source set."
                                );
                                // vmfTask.dependsOn(project.tasks.getByName(sourceSet.getName()+'Classes'))
                                vmfTask.group = "vmf"
                                vmfTask.inputFiles = vmfDirectoryDelegate.getVMF() as FileCollection;
                                vmfTask.outputFolder = outputDirectory;
                                vmfTask.sourceSet = sourceSet;
                                vmfTask.sourceDirectorySet = sourceDirectorySet;
                                // vmfTask.vmfClass = vmfClass;
                            }
                        });

                        // 5) register fact that vmf should be run before compiling
                        project.tasks.getByName(sourceSet.getCompileJavaTaskName()).dependsOn(taskName)

                        final String cleanTaskName = sourceSet.getTaskName("vmfClean", "");

                        // 6) clean the generated code and vmf model
                        project.task(cleanTaskName, group: 'vmf',
                                description: 'Cleans generated VMF & Java code.') {
                            doLast {
                                outputDirectory.listFiles().each {
                                    f -> f.deleteDir()
                                }
                            }
                        }
                    }
                });

        project.dependencies {
            vmfCompile group: 'eu.mihosoft.vmf', name: 'vmf',         version: extension.version
            compile    group: 'eu.mihosoft.vmf', name: 'vmf-runtime', version: extension.version
        }


//        // code generation task
//        project.task(
//                'vmfGenModelSources',
//                dependsOn: project.tasks.getByName('vmfClasses'),
//                group: 'vmf',
//                description: 'Generates Java Code for VMF models defined in the \'vmf\' source set.'
//        ) {
//
//            doLast {
//
//                // add model dependencies to url array
//                def urls = []
//                for(File clsDir : project.sourceSets.getByName('vmf').output.classesDirs) {
//                    urls.add(new File(clsDir.toString())
//                            .toURI().toURL())
//                }
//
//                // load VMF class (depending on version)
//                def vmfClassPath = []
//                project.sourceSets.vmf.compileClasspath.each { entry ->
//                    vmfClassPath.add(entry.toURI().toURL())
//                }
//
//                def classLoader = new URLClassLoader(vmfClassPath as URL[])
//                def vmfClass = classLoader.loadClass("eu.mihosoft.vmf.VMF")
//
//                def vmfModelPaths = []
//                project.sourceSets.vmf.java.each {
//                    vmfCodeFile ->
//
//                        String path = vmfCodeFile.absolutePath
//
//                        if(project.project.sourceSets.vmf.java.srcDirs.size()>1) {
//                            throw new IllegalArgumentException("VMF does not support more than one vmf source folder.")
//                        } else if(project.project.sourceSets.vmf.java.srcDirs.isEmpty()) {
//                            throw new IllegalArgumentException("VMF does not work without a vmf source folder.")
//                        }
//
//                        // we remove the leading part of the string including project location + leading '/'
//                        path = path.substring(project.sourceSets.vmf.java.srcDirs[0].absolutePath.size()+1)
//
//                        // now we remove the java file name
//                        int lastIndexOfSeparator = path.lastIndexOf(File.separator);
//                        if(lastIndexOfSeparator > -1) {
//                            path = path.substring(0,lastIndexOfSeparator)
//                        } else {
//                            println("WARNING: cannot find separator in " + path);
//                        }
//
//                        path = path.replace(File.separator, ".");
//
//                        vmfModelPaths.add(path)
//                }
//
//                // only process each package once
//                vmfModelPaths = vmfModelPaths.unique()
//
//                // generate code for all model paths
//                for(String vmfModelPath : vmfModelPaths) {
//
//                    println(" -> generating code for vmf model in package: " + vmfModelPath)
//
//                    // generate code
//                    vmfClass.generate(new File("$project.buildDir/vmf-src-gen"),
//                            new URLClassLoader(urls as URL[],
//                                    vmfClass.getClassLoader()),
//                            vmfModelPath
//                    )
//                }
//
//            }
//
//        }
//
//        // clean the generated code
//        project.task('vmfClean', group: 'vmf', description: 'Cleans generates Java code.') {
//            doLast {
//                new File("${project.buildDir}/vmf-src-gen/").listFiles().each {
//                    f -> f.deleteDir()
//                }
//            }
//        }
//
//        // add vmfClean task to clean tasks dependencies
//        project.tasks.clean.dependsOn('vmfClean')
//
//
//        // before we compile we need to run the vmf code generator
//        project.tasks.withType(JavaCompile) {
//            compileTask ->
//                if(!compileTask.name.startsWith("compileVmf")) {
//                    compileTask.dependsOn('vmfGenModelSources')
//                }
//        }
    }
}

/**
 * Generates implementations of VMF *.java model interfaces
 */
class CompileVMFTask extends DefaultTask {

    @InputFiles
    FileCollection inputFiles;

    @OutputDirectory
    File outputFolder;

    SourceSet sourceSet;
    SourceDirectorySet sourceDirectorySet;

    // Class<?> vmfClass;

    @TaskAction
    void vmfGenModelSources(IncrementalTaskInputs inputs) {

//        // directory set
//        println(" -> directories:")
//        for(File f : sourceDirectorySet.srcDirs) {
//            println("   --> dir:  " + f)
//        }
//
//        // all inputs
//        println(" -> all inputs:")
//        for (File f : inputFiles) {
//            println("   --> file: " + f)
//        }
//
//        // inputs that are out of date
//        println(" -> out-of-date inputs:")
//        inputs.outOfDate {
//            println("   --> file: " + it.file)
//        }
//
//        // output directory
//        println(" -> output directory:")
//        println("   --> folder: " + outputFolder)

        checkValidFileStructure();

        // call VMF.generate(...)

        def filesOutOfDate = []

        inputs.outOfDate {

            if(it.file.isFile() && it.file.absolutePath.toLowerCase().endsWith(".java")) {
                filesOutOfDate.add(it.file)
            }

        }

//        // add model dependencies to url array
//        def urls = []
//        for(File clsDir : sourceDirectorySet.outputDir) {
//            urls.add(new File(clsDir.toString())
//                    .toURI().toURL())
//        }

        // load VMF class (depending on version)
        def vmfClassPath = []
        sourceSet.compileClasspath.each { entry ->
            vmfClassPath.add(entry.toURI().toURL())
        }

        def classLoader = new URLClassLoader(vmfClassPath as URL[])
        def vmfClass = classLoader.loadClass("eu.mihosoft.vmf.VMF")

        def vmfModelPaths = []

        filesOutOfDate.each {
            vmfCodeFile ->

                print("!!! FILE: " + vmfCodeFile.absolutePath)

                // String path = vmfCodeFile.absolutePath

                // we remove the leading part of the string including project location + leading '/'
                String path = getPackageNameFromFile((File)vmfCodeFile)

//                // now we remove the java file name
//                int lastIndexOfSeparator = path.lastIndexOf(File.separator);
//                if(lastIndexOfSeparator > -1) {
//                    path = path.substring(0,lastIndexOfSeparator)
//                } else {
//                    println("WARNING: cannot find separator in " + path);
//                }

                path = path.replace(File.separator, ".");

                vmfModelPaths.add(path)
        }



        // only process each package once
        vmfModelPaths = vmfModelPaths.unique()

        // generate code for all model paths
        for(String vmfModelPath : vmfModelPaths) {

            println(" -> generating code for vmf model in package: " + vmfModelPath)

            File[] filesToCompile = filesOutOfDate.stream().filter({ f->
                String folder = f.getAbsoluteFile().getParentFile().getAbsolutePath();
                return folder.replace(File.separator, ".").endsWith(vmfModelPath)
            }).collect(Collectors.toList()) as File[]

            GroovyClassLoader gcl = new GroovyClassLoader();

            String modelDefCode = "";

            for (File f : filesToCompile) {
                modelDefCode += f.text+"\n";
            }

            gcl.parseClass(modelDefCode);


            // generate code
            vmfClass.generate(outputFolder,
                    gcl,
                    vmfModelPath
            )
        }
    }

    void checkValidFileStructure() {
        Map<String,List<String>> numFilesPerPackageName = new HashMap<>();

        for(File f : inputFiles) {

            String filePath = f.absolutePath;
            String key = getPackageNameFromFile(f);

            List<String> filesPerPackage = numFilesPerPackageName.get(key);
            if(filesPerPackage==null) {
                filesPerPackage = [];
                numFilesPerPackageName.put(key,filesPerPackage);
            }

            filesPerPackage.add(filePath);

        }

        boolean hasEmptyKeys = numFilesPerPackageName.keySet().stream().
                filter({k->k.trim().isEmpty()}).count() > 0;

        String emptyKeyMsg = "Model interface files in default package are not supported:\n";

        for(Map.Entry<String,List<String>> entry : numFilesPerPackageName.entrySet()) {
            if(entry.getKey().trim().isEmpty()) {
                for(String entryPath : entry.value) {
                    emptyKeyMsg += " -> " + entryPath + "\n";
                }
            }
        }

        if(hasEmptyKeys) {
            throw new RuntimeException(emptyKeyMsg);
        }

    }

    String getPackageNameFromFile(File file) {

        // We want to figure out the package name. Therefore we
        // - remove the front part, e.g., '/Users/myname/path/to/project/src/main/vmf-text'
        // - remove the file name from end of the remaining string, e.g., 'MyFile.java
        // - the result is the package name
        for (File dir : sourceDirectorySet.srcDirs) {

            String absolutePath = dir.absolutePath;
            String packageName = file.absolutePath;

            if (packageName.startsWith(absolutePath)) {

                packageName = packageName.substring(absolutePath.length(), packageName.length());

                if(packageName.endsWith(file.getName())) {
                    packageName = packageName.substring(0,packageName.length()-file.getName().length());
                }

                if(packageName.startsWith(File.separator)) {
                    packageName = packageName.substring(1,packageName.length());
                }

                if(packageName.endsWith(File.separator)) {
                    packageName = packageName.substring(0,packageName.length()-1);
                }

                packageName = packageName.replace(File.separator,'.');

                return packageName;
            }
        }

        throw new RuntimeException("Cannot detect package name of " + file.absolutePath);
    }
}