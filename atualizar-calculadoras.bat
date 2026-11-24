@echo off
title Hub Fiscal - atualizar calculadoras da Receita
echo Este processo baixa a versao nova das calculadoras oficiais (GitHub da Receita Federal),
echo recria o banco de dados oficial, recompila tudo e reinicia os servicos.
echo Os usuarios ficarao sem acesso ao hub por alguns minutos.
echo.
pause
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\hub.ps1" atualizar
echo.
pause
