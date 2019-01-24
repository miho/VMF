#!/bin/bash

echo "----------------------------------------"
echo "BUILDING CORE..."
echo "----------------------------------------"
cd core
./gradlew clean test publishtoMavenLocal

echo "----------------------------------------"
echo "BUILDING RUNTIME..."
echo "----------------------------------------"
cd ../runtime
./gradlew clean test publishtoMavenLocal

echo "----------------------------------------"
echo "BUILDING PLUGIN..."
echo "----------------------------------------"
cd ../gradle-plugin
./gradlew clean test publishtoMavenLocal

echo "----------------------------------------"
echo "TESTING..."
echo "----------------------------------------"
cd ../test-suite
./gradlew clean test --stacktrace
