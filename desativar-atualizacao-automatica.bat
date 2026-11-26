@echo off
title Hub Fiscal - desativar atualizacao automatica
echo Remove as tarefas "Hub Fiscal - Iniciar" e "Hub Fiscal - Atualizacao" do Agendador do Windows.
echo Execute como administrador.
echo.
pause
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\hub.ps1" desagendar
echo.
pause
