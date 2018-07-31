package eu.mihosoft.vmf.gradle.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile

class VMFPluginExtension {
    // vmf version
    String version  = "0.2-SNAPSHOT"
}

class VMFPlugin implements Plugin<Project> {
    void apply(Project project) {

        // add the 'vmf' extension object
        def extension = project.extensions.create('vmf', VMFPluginExtension)

        // apply the java plugin
        project.getPluginManager().apply(org.gradle.api.plugins.JavaPlugin.class)

        // we add a vmf configuration to the project
        project.configurations {
            vmf {
                extendsFrom compile
            }
        }

        // and the corresponding source sets
        project.sourceSets {
            main {
                java {
                    srcDirs = ["$project.buildDir/vmf-src-gen", 'src/main/java']
                }
            }

            vmf {
                // just default code locations
            }
        }

        project.repositories {
            mavenLocal()
            mavenCentral()
            jcenter()
        }

        project.dependencies {
            vmfCompile group: 'eu.mihosoft.vmf', name: 'vmf',         version: extension.version
            compile    group: 'eu.mihosoft.vmf', name: 'vmf-runtime', version: extension.version
        }

        // code generation task
        project.task(
                'vmfGenModelSources',
                dependsOn: project.tasks.getByName('vmfClasses'),
                group: 'vmf',
                description: 'Generates Java Code for VMF models defined in the \'vmf\' source set.'
        ) {

            doLast {

                // add model dependencies to url array
                def urls = []
                for(File clsDir : project.sourceSets.getByName('vmf').output.classesDirs) {
                    urls.add(new File(clsDir.toString())
                            .toURI().toURL())
                }

                // load VMF class (depending on version)
                def vmfClassPath = []
                project.sourceSets.vmf.compileClasspath.each { entry ->
                    vmfClassPath.add(entry.toURI().toURL())
                }

                def classLoader = new URLClassLoader(vmfClassPath as URL[])
                def vmfClass = classLoader.loadClass("eu.mihosoft.vmf.VMF")

                def vmfModelPaths = []
                project.sourceSets.vmf.java.each {
                    vmfCodeFile ->

                        String path = vmfCodeFile.absolutePath

                        if(project.project.sourceSets.vmf.java.srcDirs.size()>1) {
                            throw new IllegalArgumentException("VMF does not support more than one vmf source folder.")
                        } else if(project.project.sourceSets.vmf.java.srcDirs.isEmpty()) {
                            throw new IllegalArgumentException("VMF does not work without a vmf source folder.")
                        }

                        // we remove the leading part of the string including project location + leading '/'
                        path = path.substring(project.sourceSets.vmf.java.srcDirs[0].absolutePath.size()+1)

                        // now we remove the java file name
                        int lastIndexOfSeparator = path.lastIndexOf(File.separator);
                        if(lastIndexOfSeparator > -1) {
                            path = path.substring(0,lastIndexOfSeparator)
                        } else {
                            println("WARNING: cannot find separator in " + path);
                        }

                        vmfModelPaths.add(path)
                }

                // only process each package once
                vmfModelPaths = vmfModelPaths.unique()

                // generate code for all model paths
                for(String vmfModelPath : vmfModelPaths) {

                    println(" -> generating code for vmf model in package: " + vmfModelPath)

                    // generate code
                    vmfClass.generate(new File("$project.buildDir/vmf-src-gen"),
                            new URLClassLoader(urls as URL[],
                                    vmfClass.getClassLoader()),
                            vmfModelPath
                    )
                }

            }

        }

        // clean the generated code
        project.task('vmfClean', group: 'vmf', description: 'Cleans generates Java code.') {
            doLast {
                new File("${project.buildDir}/vmf-src-gen/").listFiles().each {
                    f -> f.deleteDir()
                }
            }
        }

        // add vmfClean task to clean tasks dependencies
        project.tasks.clean.dependsOn('vmfClean')


        // before we compile we need to run the vmf code generator
        project.tasks.withType(JavaCompile) {
            compileTask ->
                if(!compileTask.name.startsWith("compileVmf")) {
                    compileTask.dependsOn('vmfGenModelSources')
                }
        }
    }
}