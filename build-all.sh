#!/bin/bash

echo "------------------------------------------"
echo "BUILDING (CORE, RUNTIME, GRADLE-PLUGIN)..."
echo "------------------------------------------"
sh ./gradlew clean publishtoMavenLocal --no-daemon || { echo 'building vmf failed' ; exit 1; }

echo ------------------------------------------
echo BUILDING AND TESTING JACKSON MODULE
echo ------------------------------------------
cd ../jackson
sh ./gradlew clean publishtoMavenLocal --no-daemon || { echo 'building vmf failed' ; exit 1; }

