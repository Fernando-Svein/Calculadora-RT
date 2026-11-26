@echo off
title HUB Fiscal - desativar atualizacao automatica
echo Remove as tarefas "HUB Fiscal - Iniciar" e "HUB Fiscal - Atualizacao" do Agendador do Windows.
echo Execute como administrador.
echo.
pause
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\hub.ps1" desagendar
echo.
pause
