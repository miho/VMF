VMF
=======

[![Javadocs](https://www.javadoc.io/badge/eu.mihosoft.vmf/vmf.svg?color=blue&label=javadoc-core)](https://www.javadoc.io/doc/eu.mihosoft.vmf/vmf) [![Javadocs](https://www.javadoc.io/badge/eu.mihosoft.vmf/vmf-runtime.svg?color=blue&label=javadoc-runtime)](https://www.javadoc.io/doc/eu.mihosoft.vmf/vmf-runtime) [![Javadocs](https://www.javadoc.io/badge/eu.mihosoft.vmf/vmf-jackson.svg?color=blue&label=javadoc-jackson)](https://www.javadoc.io/doc/eu.mihosoft.vmf/vmf-jackson)
![VMF CI](https://github.com/miho/VMF/workflows/VMF%20CI/badge.svg)
[![Join the chat at https://gitter.im/VMF_/Lobby](https://badges.gitter.im/VMF_/Lobby.svg)](https://gitter.im/VMF_/Lobby?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
<a href="https://foojay.io/today/works-with-openjdk">
   <img align="right" 
        src="https://github.com/foojayio/badges/raw/main/works_with_openjdk/Works-with-OpenJDK.png"   
        width="100">
</a>

<br>


VMF is a lightweight modeling framework. It conveniently translates annotated Java interfaces into powerful implementations. It writes all the inevitable but boring boilerplate code for you and provides a modern and stable API. It is designed to work with the newest versions of Java as soon as they are released. It works well with Java 11-22.
It generates/supports:

- getters and setters
- default values
- containment
- builder API
- equals() and hashCode()
- deep and shallow cloning
- change notification
- undo/redo
- object graph traversal API via iterators and streams
- immutable types and read-only wrappers
- delegation
- annotations
- reflection
- jackson serialization support (json, xml, yml)
- json schema generation for validation and automatic visual editor generation
- ...

A VMF model consists of annotated Java interfaces. We could call this "wannabe" code. Just specify the interface and its properties and get a fully functional  implementation including property setters and getters, builders, iterators, and much more. Even for a simple model VMF provides a lot of useful APIs:

<img src="resources/img/vmf-01.svg">

## Using VMF

Checkout the tutorial projects: https://github.com/miho/VMF-Tutorials

VMF comes with excellent Gradle support. Just add the plugin like so (get the latest version [here](https://plugins.gradle.org/plugin/eu.mihosoft.vmf)):

```gradle
plugins {
  id "eu.mihosoft.vmf" version "0.2.9.4" // use latest version
}
```

Now just add the model definitions to the VMF source folder, e.g., `src/main/vmf/`. The package name must end with `.vmfmodel`, for example:

```java
package eu.mihosoft.vmf.tutorial.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

interface Parent {

    @Contains(opposite = "parent")
    Child[] getChildren();

    String getName();
}

interface Child {
    @Container(opposite="children")
    Parent getParent();
    
    int getValue();
}
```

Just call the `vmfGenModelSources` task to generate the implementation.

To improve IDE support, enable the IntelliJ support via

```
buildscript {
  ext.vmfPluginIntelliJIntegration = true
}
```

## Building VMF (Core, Runtime and Plugin)

### Requirements

- Java >= 11
- Internet connection (dependencies are downloaded automatically)
- IDE: [Gradle](http://www.gradle.org/) Plugin (not necessary for command line usage)

### IDE

Open the `VMF` [Gradle](http://www.gradle.org/) project in your favourite IDE (tested with IntelliJ 2024) and build it
by executing the `publishToMavenLocal` task.

### Command Line

Navigate to the `VMF`[Gradle](http://www.gradle.org/) project (i.e., `path/to/VMF/`) and enter the following command

#### Bash (Linux/macOS/Cygwin/other Unix shell)

    bash gradlew publishToMavenLocal
    
#### Windows (CMD)

    gradlew publishToMavenLocal
    
> **NOTE:** to execute the tests within the VMF project (basic tests) execute the `test` task after executing `publishToMavenLocal`.    
    
    
## Testing VMF (Core, Runtime & Plugin)

To execute the full test suite, navigate to the test project (i.e., `path/to/VMF/test-suite`) and enter the following command

#### Bash (Linux/macOS/Cygwin/other Unix shell)

    bash gradlew test
    
#### Windows (CMD)

    gradlew test

This will use the latest snapshot vmf and gradle-plugin to execute the tests defined in the test-suite project.

### Viewing the Report

An HTML version of the test report is located in the build folder `test-suite/build/reports/tests/test/index.html`.
