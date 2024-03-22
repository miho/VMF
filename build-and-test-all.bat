@echo off
echo ------------------------------------------
echo BUILDING (CORE, RUNTIME, GRADLE-PLUGIN)...
echo ------------------------------------------

cmd /c "gradlew.bat" clean publishtoMavenLocal --no-daemon || exit /b %ERRORLEVEL%;
cmd /c "gradlew.bat" test --no-daemon || exit /b %ERRORLEVEL%;

echo ------------------------------------------
echo BUILDING PLUGIN (MAVEN)...
echo ------------------------------------------
cd maven-plugin
cmd /c "maven.bat" clean install || exit /b %ERRORLEVEL%;

echo ------------------------------------------
echo TESTING (1/1, GRADLE)...
echo ------------------------------------------
cd ..\test-suite
@REM cmd /c "gradlew.bat" clean test --stacktrace --no-daemon --warning-mode all || exit /b %ERRORLEVEL%;
cmd /c "gradlew.bat" clean test --stacktrace --no-daemon || exit /b %ERRORLEVEL%;

@REM echo ------------------------------------------
@REM echo TESTING (2/2, MAVEN)...
@REM echo ------------------------------------------
@REM cmd /c "maven.bat" test   || exit /b %ERRORLEVEL%;