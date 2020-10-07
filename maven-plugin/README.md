# VMF-Maven-Plugin

[ ![Download](https://api.bintray.com/packages/miho/VMF/VMF-Gradle-Plugin/images/download.svg) ](https://bintray.com/miho/VMF/VMF-Maven-Plugin/_latestVersion)

Just add the plugin to use it with maven (get the latest version from [here](https://plugins.maven.org/plugin/eu.mihosoft.vmf)):

```xml
<build>
	<plugins>
		<plugin>
			<groupId>eu.mihosoft.vmf</groupId>
			<artifactId>vmf-maven-plugin</artifactId>
			<version>0.1</version>
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

and remember to reference VMF jar files to use it:

```xml
<dependencies>
	<dependency>
		<groupId>eu.mihosoft.vmf</groupId>
		<artifactId>vmf</artifactId>
		<version>0.2.7.15</version>
	</dependency>
	<dependency>
		<groupId>eu.mihosoft.vmf</groupId>
		<artifactId>vmf-runtime</artifactId>
		<version>0.2.7.15</version>
	</dependency>
</dependencies>
```

## Building the VMF Maven Plugin

Switch to the plugin directory in a shell on your system (bash or command). Then execute the comman given below.

### Unix

```bash
cd maven-plugin
./maven.sh install
```

### Windows

```bat
cd maven-plugin
maven.bat install
```

## Prerequisites

If not already done you must install VMF core and VM runtime into your local Maven repository before you can build the plugin.

Switch to the core directory and invoke the corresponding Gradle task. Repeat this step after switching to the runtime directory.

### Unix

```bash
cd core
./maven.sh install
cd ../runtime
./maven.sh install
```

### Windows

```bat
cd core
maven.bat install
cd ..\runtime
maven.bat install
```

You should be able to install the Maven plugin succesfully afterwards.
