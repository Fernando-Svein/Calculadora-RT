@echo off
title Hub Fiscal - parar
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\hub.ps1" parar
echo.
pause
