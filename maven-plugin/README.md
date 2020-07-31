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

and remember to refernce VMF jar files to use it:

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

### Maven

```bash
mvn install
```
