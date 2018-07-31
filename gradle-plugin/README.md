# VMF-Gradle-Plugin

[ ![Download](https://api.bintray.com/packages/miho/VMF/VMF-Gradle-Plugin/images/download.svg) ](https://bintray.com/miho/VMF/VMF-Gradle-Plugin/_latestVersion)

Just add the plugin id to use this plugin (get the latest version from [here](https://plugins.gradle.org/plugin/eu.mihosoft.vmf)):

```gradle
plugins {
  id "eu.mihosoft.vmf" version "0.1.6" // use latest version
}
```

and optionally configure VMF:

```gradle
vmf {
    version = '0.1.1' // use desired VMF version
}
```

## Building the VMF Gradle Plugin

### IDE

Open the `VMF` [Gradle](http://www.gradle.org/) plugin project in your favourite IDE (tested with NetBeans 8.2 and IntelliJ 2018) and build it
by calling the `publishToMavenLocal` task.

### Command Line

Navigate to the `VMF` runtime [Gradle](http://www.gradle.org/) project (i.e., `path/to/VMF/gradle-project`) and enter the following command

#### Bash (Linux/macOS/Cygwin/other Unix shell)

    bash gradlew publishToMavenLocal
    
#### Windows (CMD)

    gradlew publishToMavenLocal 

