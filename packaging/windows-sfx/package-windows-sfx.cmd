@echo off
cd /d "%~dp0..\.."
call gradlew.bat packageWindowsSfx --console=plain
echo.
if errorlevel 1 (
  echo packageWindowsSfx failed.
) else (
  echo SFX: build\windows-sfx\
)
pause
