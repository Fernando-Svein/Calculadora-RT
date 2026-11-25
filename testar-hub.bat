@echo off
title Hub Fiscal - testes automatizados
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0testes\executar-testes.ps1" %*
echo.
pause
