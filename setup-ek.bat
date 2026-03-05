@echo off
REM Setup script to copy EK jars to lib folder
REM Run this script from the TFL4 project directory

echo Copying EK jars to lib folder...

set EK_LIB_DIR=C:\Users\mme\Desktop\TravicEbicsApi_Java_V4.0.9\lib
set PROJECT_LIB_DIR=%~dp0lib

if not exist "%PROJECT_LIB_DIR%" mkdir "%PROJECT_LIB_DIR%"

echo Copying JAR files...
xcopy /Y /Q "%EK_LIB_DIR%\*.jar" "%PROJECT_LIB_DIR%\"

echo Copying native DLLs (for Windows containers)...
xcopy /Y /Q "%EK_LIB_DIR%\*.dll" "%PROJECT_LIB_DIR%\"

echo.
echo Done! You can now build the project with: mvn clean package -DskipTests
echo.
pause
