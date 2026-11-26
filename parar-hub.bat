@echo off
title HUB Fiscal - parar
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\hub.ps1" parar
echo.
pause
