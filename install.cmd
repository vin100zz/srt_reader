@echo off
rem Installe l'appli sur la TV et lui donne le droit de s'afficher par-dessus les autres applis.
rem   install.cmd 192.168.1.42          (debogage ADB classique, port 5555)
rem   install.cmd 192.168.1.42:41234    (debogage sans fil, apres "adb pair")
setlocal
if "%~1"=="" (
  echo Usage : install.cmd ADRESSE_IP_DE_LA_TV[:port]
  exit /b 1
)
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "TV=%~1"
echo %TV% | find ":" >nul || set "TV=%TV%:5555"
if not exist "%~dp0dist\srt-reader.apk" (
  echo Lance d'abord build.cmd
  exit /b 1
)

"%ADB%" connect %TV%
"%ADB%" -s %TV% install -r "%~dp0dist\srt-reader.apk" || (
  echo.
  echo Echec : accepte la demande de debogage sur la TV, puis relance.
  exit /b 1
)
"%ADB%" -s %TV% shell appops set fr.srtreader SYSTEM_ALERT_WINDOW allow
rem Acces aux notifications : permet de suivre la position de lecture de Netflix & co
"%ADB%" -s %TV% shell cmd notification allow_listener fr.srtreader/fr.srtreader.MediaListener
"%ADB%" -s %TV% shell am start -n fr.srtreader/.MainActivity
echo.
echo Installe. L'appli est ouverte sur la TV.
