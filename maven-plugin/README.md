# VMF-Maven-Plugin

[ ![Download](https://api.bintray.com/packages/miho/VMF/VMF-Gradle-Plugin/images/download.svg) ](https://bintray.com/miho/VMF/VMF-Maven-Plugin/_latestVersion)

## Usage

Just add the plugin to your project Pom to generate sources and test sources:

```xml
<build>
	<plugins>
		<plugin>
			<groupId>eu.mihosoft.vmf</groupId>
			<artifactId>vmf-maven-plugin</artifactId>
			<version>0.2.8.0</version>
			<configuration>
			</configuration>
			<executions>
				<execution>
					<id>vmf-sources</id>
					<goals>
						<goal>vmf</goal>
					</goals>
				</execution>
				<execution>
					<id>vmf-test-sources</id>
					<goals>
						<goal>vmf-test</goal>
					</goals>
				</execution>
			</executions>
		</plugin>
	</plugins>
</build>
```

and remember to reference the VMF jar files in the dependency section:

```xml
<dependencies>
	<dependency>
		<groupId>eu.mihosoft.vmf</groupId>
		<artifactId>vmf</artifactId>
		<version>0.2.8.0</version>
	</dependency>
	<dependency>
		<groupId>eu.mihosoft.vmf</groupId>
		<artifactId>vmf-runtime</artifactId>
		<version>0.2.8.0</version>
	</dependency>
</dependencies>
```

A full example can be found in the test suite folder of the VMF project.

## Getting Help

The Apache Maven Help Plugin can be used to get a full description of the available goals and the usage of
the VMF Maven Plugin:

	mvn help:describe -DgroupId=eu.mihosoft.vmf -DartifactId=vmf-maven-plugin -Dversion=0.2.8.0
	[INFO] VMF Maven Plugin 0.2.8.0
	  Generates Java implementation classes from VMF model definitions. VMF is a
	  lightweight modeling framework. It conveniently translates annotated Java
	  interfaces into powerful implementations.

	vmf:vmf
	  Generates Java implementation classes from source files in VMF model
	  definition during Maven build.
	  Parameters:
	  
	  - vmf.sourceDirectory - the vmf model source directory (default:
	    ${basedir}/src/main/vmf)
	  - vmf.targetDirectory - the directory with generated Java files (default:
	    ${project.build.directory}/generated-sources/java-vmf)

	  Available parameters:

	    sourceDirectory (Default: ${basedir}/src/main/vmf)
	      Source directory containing the vmf model files.
	      User property: vmf.sourceDirectory

	    targetDirectory (Default:
	    ${project.build.directory}/generated-sources/java-vmf)
	      The target folder where the generated Java classes will be saved to.
	      User property: vmf.targetDirectory

## FAQ

### Generating Eclipse .classpath File

I am currently facing a problem when using the Apache Maven Eclipse Plugin to generate Eclipse Classpath. When models are located in the test source tree of a Maven project the generated target folder is not added to the Eclipse classpath by default. An invocation of

	mvn eclipse:eclipse

does not generate the test classes and does not add the generated test classes to the classpath.

As a workaround you can generate the test sources together with the Eclipse classpath:

	mvn generate-test-sources eclipse:eclipse

See also this thread from [Stackoverflow](https://stackoverflow.com/questions/19701295/maven-why-does-adding-test-source-via-build-helper-not-work-when-generating-ecl "Maven: Why does adding test source via build helper not work when generating eclipse project?").

### Eclipse Compiler Errors

Eclipse might complain about compile errors in the generated code like

	The target type of this expression is not a well formed parameterized type due to bound(s) mismatch	OwnerImpl.java	/test-suite/target/generated-test-sources/java-vmf/eu/mihosoft/vmftest/complex/horses/impl	line 118	Java Problem

I found several links to a similar problem 

* the [Eclipse issue 425278](https://bugs.eclipse.org/bugs/show_bug.cgi?id=425278 "425278")
* with a reference to an [Open JDK bug JDK-8033810](https://bugs.openjdk.java.net/browse/JDK-8033810 "JDK-8033810")
* which is [a duplicate of this JDK-8048547](https://bugs.openjdk.java.net/browse/JDK-8048547 "JDK-8048547")

The code compiles fine with Open JDK but fails inside Eclipse.

Currently I tend to think that this is an Open JDK bug and the code should not compile. At least this is what I read from bug JDK-8033810 that the Eclipse guys filed against Open JDK.


## Building the VMF Maven Plugin

If you are plugin developer and want to build the VMF Maven Plugin by yourself please follow the instructions given below.

### Prerequisites

If not already done you must install VMF core and VM runtime into your local Maven repository before you can build the VMF Maven Plugin.

Switch to the core directory and invoke the corresponding Gradle task. Repeat this in the runtime directory as well.

#### Unix

```bash
cd core
gradlew publishToMavenLocal
cd ../runtime
gradlew publishToMavenLocal
```

#### Windows

```bat
cd core
gradlew.bat publishToMavenLocal
cd ..\runtime
gradlew.bat publishToMavenLocal
```

### Build

You should be able to build and install the Maven plugin succesfully afterwards. Switch to the plugin directory in a shell on your system (bash or command). Then execute the command given below.

#### Unix

```bash
cd maven-plugin
./maven.sh install
```

#### Windows

```bat
cd maven-plugin
maven.bat install
```

The VMF Maven Plugin should be installed into your local Maven repository by this.

---
**NOTE**

We use script files here (maven.bat and maven.sh) in order to ensure that the right project version (from ../config/common.properties) is parsed in order to replace the dummy project version in our pom.

---
