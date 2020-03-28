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
echo BUILDING PLUGIN...
echo ----------------------------------------
cd ..\gradle-plugin
cmd /c "gradlew.bat" publishtoMavenLocal || exit /b %ERRORLEVEL%;

echo ----------------------------------------
echo TESTING...
echo ----------------------------------------
cd ..\test-suite
cmd /c "gradlew.bat" test --stacktrace   || exit /b %ERRORLEVEL%;