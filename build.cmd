@echo off
rem Compile l'appli TV et la copie dans dist\srt-reader.apk
setlocal
cd /d "%~dp0"
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*") do set "JAVA_HOME=%%D"
call "%~dp0gradlew.bat" --console=plain :core:test :app:assembleDebug || exit /b 1
if not exist dist mkdir dist
copy /y app\build\outputs\apk\debug\app-debug.apk dist\srt-reader.apk >nul || exit /b 1
echo.
echo APK pret : %~dp0dist\srt-reader.apk
