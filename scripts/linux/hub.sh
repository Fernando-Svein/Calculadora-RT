#!/usr/bin/env bash
# Hub Fiscal no Linux – operação do dia a dia (equivale ao scripts\hub.ps1 do Windows).
#
#   sudo bash /opt/hub-fiscal/scripts/linux/hub.sh <comando>
#
#   status         situação dos três serviços, versões e próxima verificação de atualização
#   iniciar        liga as calculadoras e o hub
#   parar          desliga os três serviços
#   reiniciar      desliga e liga de novo
#   logs [qual]    acompanha o log (hub, regime-geral ou simples-nacional; padrão: hub). Ctrl+C para sair
#   criar-admin    cria o administrador inicial do hub (só funciona enquanto não há usuários)
#   atualizar      baixa a versão nova das calculadoras da Receita, recria o banco, recompila e religa
#   verificar      só atualiza se a Receita publicou versão nova; se falhar, volta à versão anterior
#                  (é o que o agendamento das 03:00 executa; resultado na tela "Situação do sistema")
#   atualizar-hub  baixa a versão nova do próprio Hub Fiscal (do seu GitHub), recompila e religa o hub
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/comum.sh"

principal() {
  local comando=${1:-status}
  case "$comando" in
    status) status ;;
    iniciar) exigir_root "$@"; iniciar_servicos; mostrar_enderecos ;;
    parar) exigir_root "$@"; etapa "Parando os serviços"; parar_servicos ;;
    reiniciar) exigir_root "$@"; etapa "Parando os serviços"; parar_servicos; sleep 2; iniciar_servicos; mostrar_enderecos ;;
    logs) logs "${2:-hub}" ;;
    criar-admin) criar_admin ;;
    atualizar) exigir_root "$@"; atualizar ;;
    verificar) exigir_root "$@"; verificar 2>&1 | tee -a "$LOGS/atualizacao.log" ;;
    atualizar-hub) exigir_root "$@"; atualizar_hub ;;
    ajuda | -h | --help) sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
    *) falha "Comando desconhecido: $comando"; sed -n '6,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 1 ;;
  esac
}

status() {
  etapa "Situação"
  local s unidade nome pasta jar url log ativo
  for s in "${SERVICOS[@]}"; do
    IFS='|' read -r unidade nome pasta jar url log <<<"$s"
    ativo=$(systemctl is-active "$unidade" 2>/dev/null)
    if url_ok "$url"; then printf "${VERDE}    %-30s respondendo${FIM}\n" "$nome"
    elif [[ "$ativo" == active || "$ativo" == activating ]]; then printf "${AMARELO}    %-30s iniciando/sem resposta${FIM}\n" "$nome"
    else printf "${VERMELHO}    %-30s parado${FIM}\n" "$nome"; fi
  done
  local v
  v=$(curl -s -m 3 http://127.0.0.1:8080/api/calculadora/dados-abertos/versao | jq -r '"calculadora \(.versaoApp), base \(.versaoDb) de \(.dataVersaoDb)"' 2>/dev/null) && [[ -n "$v" ]] && echo "    Regime Geral: $v"
  if [[ -f "$ARQUIVO_RESULTADO" ]]; then
    echo "    Última verificação de atualização: $(jq -r '"\(.data) – \(.resultado): \(.mensagem)"' "$ARQUIVO_RESULTADO" 2>/dev/null)"
  fi
  local prox
  prox=$(systemctl show hubfiscal-atualizacao.timer -p NextElapseUSecRealtime --value 2>/dev/null)
  [[ -n "$prox" && "$prox" != "n/a" ]] && echo "    Próxima verificação: $prox"
  mostrar_enderecos
}

logs() {
  local arq="$LOGS/$1.log"
  [[ -f "$arq" ]] || morrer "Log não encontrado: $arq (use hub, regime-geral ou simples-nacional)"
  echo "Acompanhando $arq (Ctrl+C para sair). Erros ficam em $LOGS/$1.err"
  tail -n 100 -f "$arq"
}

# Cria o administrador inicial pelo terminal. O hub só aceita isso a partir do próprio servidor
# (127.0.0.1), por segurança; como o Ubuntu Server não tem navegador, o pedido sai daqui mesmo.
criar_admin() {
  url_ok http://127.0.0.1:8090/hub/status || morrer "O hub não está respondendo. Rode: sudo bash $0 iniciar"
  local estado
  estado=$(curl -s -m 5 http://127.0.0.1:8090/auth/estado | jq -r '.primeiroAcesso' 2>/dev/null)
  [[ "$estado" == true ]] || morrer "O hub já tem usuários. Entre pelo navegador com um administrador e cadastre a equipe em Sistema > Usuários."
  local usuario nome senha senha2
  echo "Criando o administrador inicial do Hub Fiscal."
  read -rp "Usuário (login, ex.: fernando): " usuario
  read -rp "Nome completo: " nome
  read -rsp "Senha (mínimo 8 caracteres, letras e números): " senha; echo
  read -rsp "Repita a senha: " senha2; echo
  [[ "$senha" == "$senha2" ]] || morrer "As senhas não conferem."
  local cookies corpo resposta codigo token cabecalho
  cookies=$(mktemp)
  trap 'rm -f "$cookies"' RETURN
  resposta=$(curl -s -m 10 -c "$cookies" -b "$cookies" http://127.0.0.1:8090/auth/csrf) || morrer "Não foi possível falar com o hub."
  token=$(jq -r '.token' <<<"$resposta"); cabecalho=$(jq -r '.header' <<<"$resposta")
  corpo=$(jq -n --arg u "$usuario" --arg n "$nome" --arg s "$senha" '{usuario: $u, nome: $n, senha: $s}')
  resposta=$(curl -s -m 10 -c "$cookies" -b "$cookies" -H "Content-Type: application/json" -H "$cabecalho: $token" \
    -d "$corpo" -w $'\n%{http_code}' http://127.0.0.1:8090/auth/primeiro-acesso)
  codigo=${resposta##*$'\n'}
  if [[ "$codigo" == 2* ]]; then
    ok "Administrador \"$usuario\" criado. Entre pelo navegador com esse usuário e senha."
    mostrar_enderecos
  else
    morrer "O hub recusou: $(jq -r '.detail // .title // .' <<<"${resposta%$'\n'*}" 2>/dev/null)"
  fi
}

atualizar() {
  etapa "Parando os serviços"
  parar_servicos
  local r
  for r in "${REPOS[@]}"; do
    etapa "Atualizando $r (git pull)"
    git_servico "$r" pull --ff-only || morrer "git pull falhou em $r"
  done
  reconstruir || morrer "A atualização falhou. Veja as mensagens acima."
  mostrar_enderecos
}

salvar_resultado() {  # resultado mensagem json-dos-repositórios
  jq -n --arg data "$(date +%Y-%m-%dT%H:%M:%S)" --arg resultado "$1" --arg mensagem "$2" --argjson repos "${3:-[]}" \
    '{data: $data, resultado: $resultado, mensagem: $mensagem, repos: $repos}' >"$ARQUIVO_RESULTADO"
  chmod 644 "$ARQUIVO_RESULTADO"
}

verificar() {
  echo; echo "===== $(date '+%d/%m/%Y %H:%M:%S') – verificação de atualização ====="
  etapa "Consultando o GitHub da Receita"
  local r anterior novo qtd info='[]' ha_novidade=0
  declare -A ANTERIOR=()
  for r in "${REPOS[@]}"; do
    git_servico "$r" fetch --quiet origin || { salvar_resultado falha "Não foi possível consultar o GitHub ($r)." "$info"; falha "fetch falhou em $r"; return 1; }
    anterior=$(git_servico "$r" rev-parse HEAD); novo=$(git_servico "$r" rev-parse '@{u}')
    qtd=$(git_servico "$r" rev-list --count 'HEAD..@{u}')
    ANTERIOR[$r]=$anterior
    info=$(jq -c --arg repo "$r" --arg a "$anterior" --arg n "$novo" --argjson q "$qtd" '. + [{repo: $repo, anterior: $a, novo: $n, commitsNovos: $q}]' <<<"$info")
    echo "    $r: $qtd commit(s) novo(s)"
    ((qtd > 0)) && ha_novidade=1
  done
  if ((ha_novidade == 0)); then
    salvar_resultado sem-novidade "As calculadoras já estão na versão mais recente." "$info"
    ok "Nada a atualizar"
    # garante que os serviços estão no ar (ex.: depois de uma queda)
    systemctl start "${UNIDADES[@]}"
    return 0
  fi
  etapa "Há versão nova: atualizando"
  local erro=""
  parar_servicos
  for r in "${REPOS[@]}"; do
    git_servico "$r" merge --ff-only '@{u}' >/dev/null || { erro="git merge falhou em $r"; break; }
  done
  [[ -z "$erro" ]] && { reconstruir || erro="banco, compilação ou início dos serviços falhou"; }
  if [[ -z "$erro" ]]; then
    salvar_resultado atualizado "Calculadoras atualizadas ($(jq -r 'map("\(.repo): \(.commitsNovos) commit(s)") | join("; ")' <<<"$info"))." "$info"
    ok "Atualização concluída"
    return 0
  fi
  falha "Falha na atualização: $erro"
  etapa "Voltando para a versão anterior"
  parar_servicos
  for r in "${REPOS[@]}"; do git_servico "$r" reset --hard "${ANTERIOR[$r]}" >/dev/null; done
  if reconstruir; then
    salvar_resultado falha "A atualização falhou e a versão anterior foi restaurada. Erro: $erro" "$info"
  else
    salvar_resultado falha "A atualização falhou e não foi possível restaurar a versão anterior. Erro: $erro" "$info"
    systemctl start "${UNIDADES[@]}"
  fi
  return 1
}

atualizar_hub() {
  etapa "Baixando a versão nova do Hub Fiscal (git pull em $RAIZ)"
  echo "Se o GitHub pedir login: Username = seu usuário do GitHub; Password = o token de acesso."
  # roda como administrador (o usuário do serviço não consegue pedir o token no seu terminal) e
  # devolve os arquivos ao usuário do serviço em seguida
  git -c safe.directory="$RAIZ" -C "$RAIZ" pull --ff-only || morrer "git pull falhou"
  chown -R "$USUARIO_SERVICO:$USUARIO_SERVICO" "$RAIZ"
  systemctl daemon-reload
  compilar hub-fiscal "Hub Fiscal" || morrer "Compilação do hub falhou. O hub continua rodando a versão anterior; não reinicie a VM antes de corrigir (sudo bash $0 atualizar-hub de novo)."
  etapa "Reiniciando o hub"
  systemctl restart hubfiscal
  aguardar_servicos || morrer "O hub não voltou. Veja: sudo bash $0 logs hub"
  mostrar_enderecos
}

principal "$@"
