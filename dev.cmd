@echo off
rem Lance la page de controle sur le PC, sans TV : http://localhost:8080
rem   dev.cmd [chemin\vers\film.srt]
setlocal
cd /d "%~dp0"
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*") do set "JAVA_HOME=%%D"
if "%~1"=="" (
  call "%~dp0gradlew.bat" -q --console=plain :core:run
) else (
  call "%~dp0gradlew.bat" -q --console=plain :core:run --args="%~f1"
)
