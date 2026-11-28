@echo off
setlocal
pushd "%~dp0"
if errorlevel 1 exit /b %errorlevel%

echo Building and testing the generator, runtime and Gradle plugin
call gradlew.bat clean test compilePerformanceBenchmarks publishToMavenLocal --no-daemon %*
if errorlevel 1 goto finish

echo Building and testing the Maven plugin
cd maven-plugin
if errorlevel 1 goto finish
call maven.bat clean install %*
if errorlevel 1 goto finish

echo Testing generated models
cd ..\test-suite
if errorlevel 1 goto finish
call gradlew.bat clean test --no-daemon %*
if errorlevel 1 goto finish

echo Testing generated models in a Maven build
call maven.bat clean verify %*
if errorlevel 1 goto finish

echo Building and testing Jackson integration
cd ..\jackson
if errorlevel 1 goto finish
call gradlew.bat clean test publishToMavenLocal --no-daemon %*

:finish
set "buildStatus=%errorlevel%"
popd
exit /b %buildStatus%
