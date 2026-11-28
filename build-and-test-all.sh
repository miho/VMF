#!/bin/sh
set -eu

cd "$(dirname "$0")"

echo "Building and testing the generator, runtime and Gradle plugin"
sh ./gradlew clean test publishToMavenLocal --no-daemon "$@"

echo "Building and testing the Maven plugin"
(cd maven-plugin && sh ./maven.sh clean install "$@")

echo "Testing generated models"
(cd test-suite && sh ./gradlew clean test --no-daemon "$@")

echo "Testing generated models in a Maven build"
(cd test-suite && sh ./maven.sh clean verify "$@")

echo "Building and testing Jackson integration"
(cd jackson && sh ./gradlew clean test publishToMavenLocal --no-daemon "$@")
