@echo off
title Hub Fiscal - ativar atualizacao automatica
echo USE NO SERVIDOR (VM). Clique com o botao direito e escolha "Executar como administrador".
echo.
echo Cria duas tarefas no Agendador de Tarefas do Windows:
echo   - Hub Fiscal - Iniciar     : liga as calculadoras e o hub quando o servidor inicia
echo   - Hub Fiscal - Atualizacao : todo dia as 03:00 verifica se a Receita publicou versao nova
echo                                e so atualiza quando houver (se falhar, volta a versao anterior)
echo.
echo Requer o Git para Windows instalado no servidor.
echo.
pause
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\hub.ps1" agendar -Hora 03:00
echo.
pause
