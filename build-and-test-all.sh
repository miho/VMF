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
echo "BUILDING PLUGIN..."
echo "----------------------------------------"
cd ../gradle-plugin
./gradlew clean test publishtoMavenLocal --no-daemon || { echo 'building gradle plugin failed' ; exit 1; }

echo "----------------------------------------"
echo "TESTING..."
echo "----------------------------------------"
cd ../test-suite
./gradlew clean test --stacktrace --no-daemon || { echo 'building and/or running test-suite failed' ; exit 1; }
