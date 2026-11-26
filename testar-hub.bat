@echo off
title HUB Fiscal - testes automatizados
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0testes\executar-testes.ps1" %*
echo.
pause
