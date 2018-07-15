VMF
=======
[ ![Download](https://api.bintray.com/packages/miho/VMF/VMF/images/download.svg) ](https://bintray.com/miho/VMF/VMF/_latestVersion)

VMF is a lightweight modeling framework. It conveniently translates annotated Java interfaces into powerful implementations. 

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

## Using VMF

Checkout the tutorial projects: https://github.com/miho/VMF-Tutorials

VMF comes with excellent Gradle support. Just add the plugin like so:

```gradle
plugins {
  id "eu.mihosoft.vmf" version "0.1.1" // use latest version
}
```
and configure VMF:

```gradle
vmf {
    version = '0.1' // use desired VMF version
}
```
Now just add the model definitions to the VMF source folder, e.g., `src/vmf/`. The package name must end with `.vmfmodel`, for example:

```java
package eu.mihosoft.vmf.tutorial.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

interface Parent {

    @Contains(opposite = "parent")
    MyChild[] getChildren();

    String getName();
}

interface Child {
    @Container(opposite="children")
    MyParent getParent();
    
    int getValue();
}
```

Just call the `vmfGenModelSources` task to generate the implementation.

## Building VMF

### Requirements

- Java >= 1.8
- Internet connection (dependencies are downloaded automatically)
- IDE: [Gradle](http://www.gradle.org/) Plugin (not necessary for command line usage)

### IDE

Open the `VMF` [Gradle](http://www.gradle.org/) project in your favourite IDE (tested with NetBeans 8.2 and IntelliJ 2018) and build it
by calling the `publishToMavenLocal` task.

### Command Line

Navigate to the [Gradle](http://www.gradle.org/) project (e.g., `path/to/VMF`) and enter the following command

#### Bash (Linux/macOS/Cygwin/other Unix shell)

    bash gradlew publishToMavenLocal
    
#### Windows (CMD)

    gradlew publishToMavenLocal

## Testing VMF

Use the test suite TODO

