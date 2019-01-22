#!/bin/bash

echo "----------------------------------------"
echo "BUILDING CORE..."
echo "----------------------------------------"
cd core
./gradlew clean publishtoMavenLocal

echo "----------------------------------------"
echo "BUILDING RUNTIME..."
echo "----------------------------------------"
cd ../runtime
./gradlew clean publishtoMavenLocal

echo "----------------------------------------"
echo "BUILDING PLUGIN..."
echo "----------------------------------------"
cd ../gradle-plugin
./gradlew clean publishtoMavenLocal

echo "----------------------------------------"
echo "TESTING..."
echo "----------------------------------------"
cd ../test-suite
./gradlew clean test --stacktrace
