#!/bin/bash

echo "------------------------------------------"
echo "BUILDING (CORE, RUNTIME, GRADLE-PLUGIN)..."
echo "------------------------------------------"
sh ./gradlew clean test publishtoMavenLocal --no-daemon || { echo 'building vmf failed' ; exit 1; }

echo "------------------------------------------"
echo "BUILDING PLUGIN (MAVEN)..."
echo "------------------------------------------"
cd maven-plugin
sh ./maven.sh clean install || { echo 'building maven plugin failed' ; exit 1; }

echo "------------------------------------------"
echo "TESTING  (1/2, GRADLE)..."
echo "------------------------------------------"
cd ../test-suite
sh ./gradlew clean test --stacktrace --no-daemon || { echo 'building and/or running test-suite failed' ; exit 1; }

echo "------------------------------------------"
echo "TESTING  (2/2, MAVEN)..."
echo "------------------------------------------"
sh ./maven.sh clean test || { echo 'building and/or running test-suite failed' ; exit 1; }
