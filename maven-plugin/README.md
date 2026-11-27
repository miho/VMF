# VMF Maven plugin

The VMF Maven plugin generates Java model classes during a normal Maven build, and on request C# and TypeScript
from the same model. The example uses Maven 3.9, JDK 21, and VMF 0.4.0 from Maven Central.

## Generate your model

Put model definitions under `src/main/vmf` in packages ending in `.vmfmodel`. Add the runtime dependency and bind
the plugin goal in your POM.

```xml
<properties>
    <vmf.version>0.4.0</vmf.version>
</properties>

<dependencies>
    <dependency>
        <groupId>eu.mihosoft.vmf</groupId>
        <artifactId>vmf-runtime</artifactId>
        <version>${vmf.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>eu.mihosoft.vmf</groupId>
            <artifactId>vmf-maven-plugin</artifactId>
            <version>${vmf.version}</version>
            <executions>
                <execution>
                    <goals>
                        <goal>vmf</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Run `mvn clean verify`. The `vmf` goal runs during `generate-sources` and adds `target/generated-sources/java-vmf`
to Maven's compile roots. To also generate test models from `src/test/vmf`, add `<goal>vmf-test</goal>` to the same
goals list. That goal runs during `generate-test-sources` and adds `target/generated-test-sources/java-vmf` to
Maven's test compile roots.

For an executable example, see [VMF Tutorial 16](https://github.com/miho/VMF-Tutorials/tree/master/VMF-Tutorial-16).

## Generate C# and TypeScript

Builds after VMF 0.4.0 can also generate C# and TypeScript from the same model interfaces:

```xml
<plugin>
    <groupId>eu.mihosoft.vmf</groupId>
    <artifactId>vmf-maven-plugin</artifactId>
    <version>${vmf.version}</version>
    <configuration>
        <targets>
            <target>csharp</target>
            <target>typescript</target>
        </targets>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>vmf</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Java is always generated. The other languages go next to the Java sources, to
`target/generated-sources/csharp-vmf` and `target/generated-sources/typescript-vmf` (`generated-test-sources` for
the `vmf-test` goal); `csharpDirectory` and `typescriptDirectory` choose other folders. The build fails if a model
uses features a language does not support. The [language mappings](../docs/targets/) describe the generated code and
its runtime.

## Build from source

From the VMF checkout, install the matching generator and runtime before installing the plugin and running the Maven
consumer suite.

```sh
./gradlew publishToMavenLocal
(cd maven-plugin && sh ./maven.sh -B -ntp clean install)
(cd test-suite && sh ./maven.sh -B -ntp clean verify)
```

On Windows, use `gradlew.bat` in the root and `maven.bat` in each of the two directories.
