VMF
=======


VMF is a lightweight modeling framework. It conveniently translates annotated Java interfaces into powerful implementations. 

It generates/supports:

- getters and setters
- default values
- builder API
- equals() and hasCode()
- deep and shallow cloning
- change notification
- undo/redo
- object graph traversal API via iterators and streams
- immutable types and read-only wrappers
- delegation

## How

Have a look at the sample project. TODO

## Building VMF

### Requirements

- Java >= 1.8
- Internet connection (dependencies are downloaded automatically)
- IDE: [Gradle](http://www.gradle.org/) Plugin (not necessary for command line usage)

### IDE

Open the `VMF` [Gradle](http://www.gradle.org/) project in your favourite IDE (tested with NetBeans 8.2 and IntelliJ 2018) and build it
by calling the `assemble` task.

### Command Line

Navigate to the [Gradle](http://www.gradle.org/) project (e.g., `path/to/VMF`) and enter the following command

#### Bash (Linux/macOS/Cygwin/other Unix shell)

    bash gradlew publishToMavenLocal
    
#### Windows (CMD)

    gradlew publishToMavenLocal

## Testing VMF

Use the test suite TODO

