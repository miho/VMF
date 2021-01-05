@echo off

setlocal

FOR /F "tokens=*" %%i in ('findstr "^publication.version" ..\config\common.properties') do SET %%i

mvn -Drevision=%publication.version% %*

endlocal

