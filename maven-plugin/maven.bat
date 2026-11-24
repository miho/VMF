@echo off

setlocal

FOR /F "tokens=*" %%i in ('findstr "^publication.version" ..\config\common.properties') do SET %%i

call mvn -Drevision=%publication.version% %*
exit /b %errorlevel%

