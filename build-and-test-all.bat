@echo off
echo ------------------------------------------
echo BUILDING (CORE, RUNTIME, GRADLE-PLUGIN)...
echo ------------------------------------------

cmd /c "gradlew.bat" clean test publishtoMavenLocal --no-daemon || exit /b %ERRORLEVEL%;

echo ------------------------------------------
echo BUILDING PLUGIN (MAVEN)...
echo ------------------------------------------
cd maven-plugin
cmd /c "maven.bat" clean install || exit /b %ERRORLEVEL%;

echo ------------------------------------------
echo TESTING (1/2, GRADLE)...
echo ------------------------------------------
cd ..\test-suite
cmd /c "gradlew.bat" clean test --stacktrace --no-daemon || exit /b %ERRORLEVEL%;

echo ------------------------------------------
echo TESTING (2/2, MAVEN)...
echo ------------------------------------------
cmd /c "maven.bat" test   || exit /b %ERRORLEVEL%;