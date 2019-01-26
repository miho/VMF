@echo off
echo ----------------------------------------
echo BUILDING CORE...
echo ----------------------------------------
cd core
cmd /c "gradlew.bat" clean test publishtoMavenLocal --no-daemon

@echo off
echo ----------------------------------------
echo BUILDING RUNTIME...
echo ----------------------------------------
cd ..\runtime
cmd /c "gradlew.bat" clean test publishtoMavenLocal --no-daemon

echo ----------------------------------------
echo BUILDING PLUGIN...
echo ----------------------------------------
cd ..\gradle-plugin
cmd /c "gradlew.bat" clean test publishtoMavenLocal --no-daemon

echo ----------------------------------------
echo TESTING...
echo ----------------------------------------
cd ..\test-suite
cmd /c "gradlew.bat" clean test --stacktrace --no-daemon