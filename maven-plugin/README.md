# VMF Maven plugin

The VMF Maven plugin generates Java model classes during a normal Maven build. The example uses Maven 3.9, JDK 21, and VMF 0.4.0 from Maven Central.

## Generate your model

Put model definitions under `src/main/vmf` in packages ending in `.vmfmodel`. Add the runtime dependency and bind the plugin goal in your POM.

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

Run `mvn clean verify`. The `vmf` goal runs during `generate-sources` and adds `target/generated-sources/java-vmf` to Maven's compile roots. To also generate test models from `src/test/vmf`, add `<goal>vmf-test</goal>` to the same goals list. That goal runs during `generate-test-sources` and adds `target/generated-test-sources/java-vmf` to Maven's test compile roots.

For an executable example, see [VMF Tutorial 16](https://github.com/miho/VMF-Tutorials/tree/master/VMF-Tutorial-16).

## Build from source

From the VMF checkout, install the matching generator and runtime before installing the plugin and running the Maven consumer suite.

```sh
./gradlew :vmf:publishToMavenLocal :vmf-runtime:publishToMavenLocal
(cd maven-plugin && sh ./maven.sh -B -ntp clean install)
(cd test-suite && sh ./maven.sh -B -ntp clean verify)
```

On Windows, use `gradlew.bat` in the root and `maven.bat` in each of the two directories.
