@echo off
title HUB Fiscal - iniciar
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\hub.ps1" iniciar
if %errorlevel%==0 start "" http://localhost:8090
echo.
pause
