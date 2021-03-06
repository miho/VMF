@echo off
echo ----------------------------------------
echo BUILDING CORE...
echo ----------------------------------------
cd core
cmd /c "gradlew.bat" publishtoMavenLocal || exit /b %ERRORLEVEL%;

@echo off
echo ----------------------------------------
echo BUILDING RUNTIME...
echo ----------------------------------------
cd ..\runtime
cmd /c "gradlew.bat" publishtoMavenLocal || exit /b %ERRORLEVEL%;

echo ----------------------------------------
echo BUILDING PLUGIN (1/2, GRADLE)...
echo ----------------------------------------
cd ..\gradle-plugin
cmd /c "gradlew.bat" publishtoMavenLocal || exit /b %ERRORLEVEL%;

echo ----------------------------------------
echo BUILDING PLUGIN (2/2, MAVEN)...
echo ----------------------------------------
cd ..\maven-plugin
cmd /c "maven.bat" install || exit /b %ERRORLEVEL%;

echo ----------------------------------------
echo TESTING (1/2, GRADLE)...
echo ----------------------------------------
cd ..\test-suite
cmd /c "gradlew.bat" test --stacktrace   || exit /b %ERRORLEVEL%;

echo ----------------------------------------
echo TESTING (2/2, MAVEN)...
echo ----------------------------------------
cd ..\test-suite
cmd /c "maven.bat" test   || exit /b %ERRORLEVEL%;