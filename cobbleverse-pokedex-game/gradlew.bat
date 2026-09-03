@echo off
setlocal
set "GRADLE_VERSION=8.12"
set "PROJECT_DIR=%~dp0"
set "LOCAL_DIR=%PROJECT_DIR%.gradle-local"
set "GRADLE_HOME=%LOCAL_DIR%\gradle-%GRADLE_VERSION%"
set "GRADLE_BAT=%GRADLE_HOME%\bin\gradle.bat"
set "ZIP_FILE=%LOCAL_DIR%\gradle-%GRADLE_VERSION%-bin.zip"
set "DOWNLOAD_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

if exist "%GRADLE_BAT%" goto run

if not exist "%LOCAL_DIR%" mkdir "%LOCAL_DIR%"

echo Gradle %GRADLE_VERSION% is not cached for this project.
echo Downloading from %DOWNLOAD_URL%
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%DOWNLOAD_URL%' -OutFile '%ZIP_FILE%'"
if errorlevel 1 (
  echo.
  echo ERROR: Could not download Gradle.
  echo Check your internet connection and try again.
  exit /b 1
)

echo Extracting Gradle...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%ZIP_FILE%' -DestinationPath '%LOCAL_DIR%' -Force"
if errorlevel 1 (
  echo ERROR: Could not extract Gradle.
  exit /b 1
)

del /q "%ZIP_FILE%" >nul 2>&1

:run
call "%GRADLE_BAT%" %*
exit /b %ERRORLEVEL%
