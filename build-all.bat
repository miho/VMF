@echo off
echo ------------------------------------------
echo BUILDING (CORE, RUNTIME, GRADLE-PLUGIN)...
echo ------------------------------------------

cmd /c "gradlew.bat" clean publishtoMavenLocal --no-daemon || exit /b %ERRORLEVEL%;

echo ------------------------------------------
echo BUILDING AND TESTING JACKSON MODULE
echo ------------------------------------------
cd ..\jackson
@REM cmd /c "gradlew.bat" clean test --stacktrace --no-daemon --warning-mode all || exit /b %ERRORLEVEL%;
cmd /c "gradlew.bat" clean publishtoMavenLocal --no-daemon || exit /b %ERRORLEVEL%;

