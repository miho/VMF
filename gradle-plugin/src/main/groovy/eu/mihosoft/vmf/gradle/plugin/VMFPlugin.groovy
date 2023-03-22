/*
 * Copyright 2017-2023 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2023 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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
package eu.mihosoft.vmf.gradle.plugin


import org.gradle.api.artifacts.Dependency
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property

import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPlugin

import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile


import org.gradle.api.internal.tasks.*;
import org.gradle.api.internal.plugins.DslObject;
import org.gradle.api.*;


//
import org.gradle.api.file.SourceDirectorySet;

import org.gradle.api.reflect.HasPublicType;
import org.gradle.api.reflect.TypeOf

import org.gradle.plugins.ide.idea.IdeaPlugin;

import org.gradle.work.Incremental
import org.gradle.work.InputChanges

import javax.inject.Inject;

import static org.gradle.api.reflect.TypeOf.typeOf;

/*
 * **NOTE:**
 *
 * This code is heavily influenced by the ANTLR plugin that ships with Gradle:
 *
 * -> see: https://github.com/gradle/gradle/tree/master/subprojects/antlr/src/main/java/org/gradle/api/plugins/antlr
 *
 * TODO 22.07.2018 find out whether adding a custom language folder, such as 'vmf' really needs that much code.
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

    public VMFSourceVirtualDirectoryImpl(String parentDisplayName, org.gradle.api.model.ObjectFactory sourceDirectorySetFactory) {
        final String displayName = parentDisplayName + " VMF source";
        vmf = sourceDirectorySetFactory.sourceDirectorySet("vmf", displayName);
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
    // String version  = "0.2-SNAPSHOT"
}

class VMFPlugin implements Plugin<Project> {

    private org.gradle.api.model.ObjectFactory sourceDirectorySetFactory;

    @Inject
    public VMFPlugin(org.gradle.api.model.ObjectFactory sourceDirectorySetFactory) {
        this.sourceDirectorySetFactory = sourceDirectorySetFactory;
    }

    @Override
    void apply(Project project) {

        // apply the java plugin
        project.getPluginManager().apply(JavaPlugin.class)

        // we optionally apply the idea plugin to improve the management of
        // 'vmf' source roots (see below)
        if (project.hasProperty("vmfPluginIntelliJIntegration")
                && project.property("vmfPluginIntelliJIntegration")) {
            project.getPluginManager().apply(IdeaPlugin.class)
        }

        // add the 'vmf' extension object
        def extension = project.extensions.create('vmf', VMFPluginExtension)

        project.repositories {
            mavenLocal()
            mavenCentral()
        }

        // get version from generated Constants.groovy
        String VMF_VERSION = eu.mihosoft.vmf.gradle.plugin.constants.Constants.VMF_VERSION;

        // project.getConvention().getPlugin(JavaPluginConvention.class)

        project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().all(new Action<SourceSet>() {
            public void execute(final SourceSet sourceSet) {

                // For each source set we will:

                // 0) Create a 'vmfCompile' configuration that contains the dependencies to vmf-core and
                // potentially other dependencies needed to compile the model definitions

                String configName = sourceSet.getTaskName("vmfCompile", "");

                Dependency dep1 = project.dependencies.create(
                        "eu.mihosoft.vmf:vmf:$VMF_VERSION")
                Dependency dep2 = project.dependencies.create(
                        "eu.mihosoft.vmf:vmf-runtime:$VMF_VERSION")
                def conf = project.configurations.maybeCreate(configName)
                conf.defaultDependencies { deps ->
                    deps.add(dep1)
                    deps.add(dep2)
                }

                // 1) Add a new 'vmf' virtual directory mapping
                final VMFSourceVirtualDirectoryImpl vmfDirectoryDelegate =
                        new VMFSourceVirtualDirectoryImpl(
                                ((DefaultSourceSet) sourceSet).getDisplayName(),
                                sourceDirectorySetFactory
                        );
                new DslObject(sourceSet).getConvention().getPlugins().put(
                        VMFSourceVirtualDirectory.NAME, vmfDirectoryDelegate
                );
                // 1.5 Add the source directory set 'vmf' to the source set
                final String srcDir = "src/" + sourceSet.getName() + "/vmf";
                SourceDirectorySet sourceDirectorySetVMF = vmfDirectoryDelegate.getVMF();
                sourceDirectorySetVMF.srcDir(srcDir);
                sourceSet.getAllSource().source(sourceDirectorySetVMF);

                // 2) Create a VMFTask for this sourceSet following the gradle
                //    naming conventions via call to sourceSet.getTaskName()
                final String taskName = sourceSet.getTaskName("vmfGenModelSources", "");

                // 3) Set up the VMF output directories (adding to javac inputs!)
                final String outputDirectoryName =
                        project.getBuildDir().absolutePath + "/generated-src/vmf-" + sourceSet.getName();
                final File outputDirectory = new File(outputDirectoryName);
                sourceSet.getJava().srcDir(outputDirectory);

                final String outputDirectoryNameModelDef =
                        project.getBuildDir().absolutePath + "/vmf-modeldef-compiled/vmf-" + sourceSet.getName();
                final File outputDirectoryModelDef = new File(outputDirectoryNameModelDef);

                final String compileModelDefTaskName = sourceSet.getTaskName("vmfCompileModelDef", "Code");

                // 3.5) create custom JavaCompile task that compiles the model definitions in the
                //      'vmf' SourceDirectorySet and creates the class files needed by the VMF.generate()
                //      task

                // resolve dependencies for task that compiles model definitions
                FileCollection vmfClassPath

                Closure<FileCollection> resolveClassPath = {
                    if (vmfClassPath == null) {
                        vmfClassPath = project.files(project.configurations.getByName(configName).resolve());
                    }

                    return vmfClassPath
                }

                JavaCompile compileVMFModelDefTask = project.task(compileModelDefTaskName, type: JavaCompile) {
                    source = sourceDirectorySetVMF
                    classpath = project.configurations.getByName(configName)
                    destinationDirectory = outputDirectoryModelDef
                    // dependencyCacheDir = file('.')
                    // sourceCompatibility = '1.7'
                    // targetCompatibility = '1.7'
                }

                CompileVMFTask vmfTask = project.getTasks().create(
                        taskName, CompileVMFTask.class, new Action<CompileVMFTask>() {
                    @Override
                    void execute(CompileVMFTask vmfTask) {
                        // 4) Set up convention mapping for default sources (allows user to not have to specify)
                        //    and set up a task description
                        vmfTask.setDescription(
                                "Generates Java Code for VMF models defined in the "
                                        + sourceSet.getName() + " source set."
                        );
                        vmfTask.group = "vmf"
                        vmfTask._inputFiles = vmfDirectoryDelegate.getVMF() as FileCollection;
                        vmfTask._outputFolder = outputDirectory;
                        vmfTask._sourceSetCompileClassPath = sourceSet.compileClasspath;
                        vmfTask._sourceDirectorySet.set(sourceDirectorySetVMF)
                        vmfTask._outputDirectoryModelDef = outputDirectoryModelDef;
                        vmfTask._vmfClassPath = resolveClassPath.call();
                    }
                });

//                // resolve classpath after it's initialized
//                // TODO check whether this is still valid and necessary for Gradle >= 7
//                vmfTask.doFirst {
//                    vmfTask.vmfClassPath = resolveClassPath.call();
//                }

                vmfTask.dependsOn(compileVMFModelDefTask)

                // 5) register fact that vmf should be run before compiling
                project.tasks.getByName(sourceSet.getCompileJavaTaskName()).dependsOn(taskName)

                final String cleanTaskName = sourceSet.getTaskName("vmfClean", "");

                // 6) clean the generated code and vmf model
                project.task(cleanTaskName, group: 'vmf',
                        description: 'Cleans generated VMF/Java code and the corresponding .class-Files.') {
                    doLast {
                        outputDirectory.listFiles().each {
                            f -> f.deleteDir()
                        }
                        outputDirectoryModelDef.listFiles().each {
                            f -> f.deleteDir()
                        }
                    }
                }

                // we optionally add the 'vmf' source roots to the idea module
                // (in addition to adding them to gradle)
                // and we mark generated folders (allows intellij to detect them as generated code)
                if (project.hasProperty("vmfPluginIntelliJIntegration")
                        && project.property("vmfPluginIntelliJIntegration")) {
                    // 7) register source set for idea plugin
                    project.idea {
                        module {
                            // add the already(!) added vmf src dir for intellij
                            sourceDirs += sourceSet.vmf.srcDirs

                            // add the already(!) added vmf gen output src dir for intellij
                            generatedSourceDirs += outputDirectory
                        }
                    }
                }
            }
        });

        project.dependencies {
            implementation group: 'eu.mihosoft.vmf', name: 'vmf-runtime', version: VMF_VERSION
        }

    }
}

/**
 * Generates implementations of VMF *.java model interfaces
 */
class CompileVMFTask extends DefaultTask {

    @InputFiles
    FileCollection _inputFiles;

    @OutputDirectory
    File _outputFolder;

    @InputFiles
    FileCollection _sourceSetCompileClassPath;

    @InputFiles
    @Incremental
    // just creates the property, setting as before
    final Property<SourceDirectorySet> _sourceDirectorySet = project.objects.property(SourceDirectorySet)


    @OutputDirectory
    File _outputDirectoryModelDef;

    @InputFiles
    FileCollection _vmfClassPath

    // Class<?> vmfClass;

    @TaskAction
    void vmfGenModelSources(InputChanges inputChanges) {

        // directory set
        println(" -> directories:")
        for(File f : _sourceDirectorySet.get().srcDirs) {
            println("   --> dir:  " + f)
        }
//
        // all inputs
        println(" -> all inputs:")
        for (File f : _inputFiles) {
            println("   --> file: " + f)
        }


        // output directory
        println(" -> output directory:")
        println("   --> folder: " + _outputFolder)

        checkValidFileStructure();

        // call VMF.generate(...)

        def filesOutOfDate = []

        // println(" -> out-of-date inputs:")

        inputChanges.getFileChanges(_sourceDirectorySet).each { it ->
            if (it.file.isFile() && it.file.absolutePath.toLowerCase().endsWith(".java")) {
                println("   --> file: " + it.file)
                filesOutOfDate.add(it.file)
            }
        }

        // load VMF class (depending on version)
        def vmfCompileModelClassPath = []
        _sourceSetCompileClassPath.each { entry ->
            vmfCompileModelClassPath.add(entry.toURI().toURL())
        }
        vmfCompileModelClassPath.add(_outputDirectoryModelDef.toURI().toURL())


        // add vmf base classes (from core package and manual definitions in build.gradle dependencies {})
        _vmfClassPath.each { entry ->
            vmfCompileModelClassPath.add(entry.toURI().toURL())
        }

        def classLoader = new URLClassLoader(vmfCompileModelClassPath as URL[])
        def vmfClass = classLoader.loadClass("eu.mihosoft.vmf.VMF")

        def vmfModelPaths = []

        filesOutOfDate.each {
            vmfCodeFile ->

                // we remove the leading part of the string including project location + leading '/'
                String path = getPackageNameFromFile((File) vmfCodeFile)

                // use '.' instead of '/' since we look for packages and not file paths
                path = path.replace(File.separator, ".");

                vmfModelPaths.add(path)
        }

        // only process each package once
        vmfModelPaths = vmfModelPaths.unique()

        // generate code for all model paths
        for (String vmfModelPath : vmfModelPaths) {

            println(" -> generating code for vmf model in package: " + vmfModelPath)

            // generate code
            vmfClass.generate(
                    _outputFolder,
                    classLoader,
                    vmfModelPath
            )
        }
    }

    void checkValidFileStructure() {
        Map<String, List<String>> numFilesPerPackageName = new HashMap<>();

        for (File f : _inputFiles) {

            String filePath = f.absolutePath;
            String key = getPackageNameFromFile(f);

            List<String> filesPerPackage = numFilesPerPackageName.get(key);
            if (filesPerPackage == null) {
                filesPerPackage = [];
                numFilesPerPackageName.put(key, filesPerPackage);
            }

            filesPerPackage.add(filePath);

        }

        boolean hasEmptyKeys = numFilesPerPackageName.keySet().stream().
                filter({ k -> k.trim().isEmpty() }).count() > 0;

        String emptyKeyMsg = "Model interface files in default package are not supported:\n";

        for (Map.Entry<String, List<String>> entry : numFilesPerPackageName.entrySet()) {
            if (entry.getKey().trim().isEmpty()) {
                for (String entryPath : entry.value) {
                    emptyKeyMsg += " -> " + entryPath + "\n";
                }
            }
        }

        if (hasEmptyKeys) {
            throw new RuntimeException(emptyKeyMsg);
        }

    }

    String getPackageNameFromFile(File file) {

        // We want to figure out the package name. Therefore we
        // - remove the front part, e.g., '/Users/myname/path/to/project/src/main/vmf'
        // - remove the file name from end of the remaining string, e.g., 'MyFile.java
        // - the result is the package name
        for (File dir : _sourceDirectorySet.get().srcDirs) {

            String absolutePath = dir.absolutePath;
            String packageName = file.absolutePath;

            if (packageName.startsWith(absolutePath)) {

                packageName = packageName.substring(absolutePath.length(), packageName.length());

                if (packageName.endsWith(file.getName())) {
                    packageName = packageName.substring(0, packageName.length() - file.getName().length());
                }

                if (packageName.startsWith(File.separator)) {
                    packageName = packageName.substring(1, packageName.length());
                }

                if (packageName.endsWith(File.separator)) {
                    packageName = packageName.substring(0, packageName.length() - 1);
                }

                packageName = packageName.replace(File.separator, '.');

                return packageName;
            }
        }

        throw new RuntimeException("Cannot detect package name of " + file.absolutePath);
    }
}