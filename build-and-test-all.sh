#!/bin/bash

echo "----------------------------------------"
echo "BUILDING CORE..."
echo "----------------------------------------"
cd core
./gradlew clean test publishtoMavenLocal --no-daemon || { echo 'building core failed' ; exit 1; }

echo "----------------------------------------"
echo "BUILDING RUNTIME..."
echo "----------------------------------------"
cd ../runtime
./gradlew clean test publishtoMavenLocal --no-daemon || { echo 'building runtime failed' ; exit 1; }

echo "----------------------------------------"
echo "BUILDING PLUGIN  (1/2, GRADLE)..."
echo "----------------------------------------"
cd ../gradle-plugin
./gradlew clean test publishtoMavenLocal --no-daemon || { echo 'building gradle plugin failed' ; exit 1; }

echo "----------------------------------------"
echo "BUILDING PLUGIN (2/2, MAVEN)..."
echo "----------------------------------------"
cd ../maven-plugin
sh ./maven.sh clean install || { echo 'building gradle plugin failed' ; exit 1; }

echo "----------------------------------------"
echo "TESTING  (1/2, GRADLE)..."
echo "----------------------------------------"
cd ../test-suite
./gradlew clean test --stacktrace --no-daemon || { echo 'building and/or running test-suite failed' ; exit 1; }

echo "----------------------------------------"
echo "TESTING  (2/2, MAVEN)..."
echo "----------------------------------------"
cd ../test-suite
./maven.sh clean test || { echo 'building and/or running test-suite failed' ; exit 1; }
