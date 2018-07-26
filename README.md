VMF [![Tweet](https://img.shields.io/twitter/url/http/shields.io.svg?style=social)](https://twitter.com/intent/tweet?text=VMF:%20The%20new%20modeling%20framework%20for%20Java!&url=https://github.com/miho/VMF&via=mihosoft&hashtags=vmf,java,mdd,developers)
=======

[ ![Download](https://api.bintray.com/packages/miho/VMF/VMF/images/download.svg) ](https://bintray.com/miho/VMF/VMF/_latestVersion)
[![Build Status](https://travis-ci.org/miho/VMF.svg?branch=master)](https://travis-ci.org/miho/VMF)
[![Join the chat at https://gitter.im/VMF_/Lobby](https://badges.gitter.im/VMF_/Lobby.svg)](https://gitter.im/VMF_/Lobby?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

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

A VMF model consists of annotated Java interfaces. We could call this "wannabe" code. We just specify the interface and its properties and get a rich implementation that implements the property setters and getters, builders and much more. Even for a simple model VMF generated a lot of useful API:

<img src="resources/img/vmf-01.svg">

## Using VMF

Checkout the tutorial projects: https://github.com/miho/VMF-Tutorials

VMF comes with excellent Gradle support. Just add the plugin like so:

```gradle
plugins {
  id "eu.mihosoft.vmf" version "0.1.3" // use latest version
}
```
and configure VMF:

```gradle
vmf {
    version = '0.1.1' // use desired VMF version
}
```
Now just add the model definitions to the VMF source folder, e.g., `src/vmf/java`. The package name must end with `.vmfmodel`, for example:

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

