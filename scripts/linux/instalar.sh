#!/usr/bin/env bash
# HUB Fiscal – instalação num servidor Ubuntu (24.04 LTS). Rode uma única vez, como administrador:
#
#     sudo bash /opt/hub-fiscal/scripts/linux/instalar.sh
#
# O que faz (leva de 10 a 20 minutos, a maior parte compilando):
#   1. instala git, curl e jq
#   2. cria o usuário de sistema "hubfiscal" (sem login), dono dos arquivos e dos processos
#   3. baixa o Java 25 (Eclipse Temurin) para tools/
#   4. baixa as calculadoras oficiais do GitHub da Receita, gera o banco oficial e compila os três serviços
#   5. registra os serviços no systemd (sobem sozinhos com a VM e voltam se caírem)
#   6. agenda a verificação de atualização das calculadoras todo dia às 03:00
#   7. se o firewall (ufw) estiver ativo, libera a porta 8090 só para redes internas
# Pode ser rodado de novo sem problema: o que já estiver pronto é mantido.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/comum.sh"

principal() {
  exigir_root "$@"
  [[ -f "$RAIZ/hub-fiscal/pom.xml" ]] || morrer "Não encontrei $RAIZ/hub-fiscal. Clone o projeto em /opt/hub-fiscal antes."
  echo "Instalando o HUB Fiscal em $RAIZ"

  etapa "Pacotes do sistema (git, curl, jq)"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq && apt-get install -y -qq git curl jq ca-certificates tar unzip >/dev/null || morrer "apt-get falhou (a VM tem internet?)"
  ok "Pacotes instalados"

  etapa "Usuário do serviço ($USUARIO_SERVICO)"
  if id "$USUARIO_SERVICO" &>/dev/null; then
    ok "Usuário já existe"
  else
    useradd --system --home-dir "$HOME_SERVICO" --create-home --shell /usr/sbin/nologin "$USUARIO_SERVICO" || morrer "useradd falhou"
    ok "Usuário criado (sem login)"
  fi
  mkdir -p "$LOGS" "$RAIZ/dados" "$RAIZ/tools"
  chown -R "$USUARIO_SERVICO:$USUARIO_SERVICO" "$RAIZ"
  chmod 750 "$RAIZ/dados"
  ok "Arquivos de $RAIZ pertencem a $USUARIO_SERVICO"

  instalar_java || morrer "Não foi possível instalar o Java 25"
  chown -R "$USUARIO_SERVICO:$USUARIO_SERVICO" "$RAIZ/tools"

  baixar_calculadoras || morrer "Não foi possível baixar as calculadoras da Receita"
  [[ -f "$BANCO_RG" ]] || gerar_banco || morrer "Não foi possível gerar o banco oficial"
  copiar_banco_simples || morrer "Não foi possível copiar o banco para o Simples"
  local s unidade nome pasta jar url log
  for s in "${SERVICOS[@]}"; do
    IFS='|' read -r unidade nome pasta jar url log <<<"$s"
    [[ -f "$RAIZ/$pasta/target/$jar" ]] && { ok "$nome já compilado"; continue; }
    compilar "$pasta" "$nome" || morrer "Compilação falhou: $nome"
  done

  etapa "Registrando os serviços no systemd"
  escrever_unidade hubfiscal-calc-rg "HUB Fiscal - Calculadora Regime Geral (Receita Federal)" calculadora-rtc-regime-regular \
    "-Dspring.profiles.active=offline -Dserver.address=127.0.0.1" api-regime-geral.jar regime-geral ""
  escrever_unidade hubfiscal-calc-sn "HUB Fiscal - Calculadora Simples Nacional (Receita Federal)" calculadora-rtc-simples-nacional \
    "-Dspring.profiles.active=offline -Dserver.address=127.0.0.1" api-simples-nacional.jar simples-nacional ""
  escrever_unidade hubfiscal "HUB Fiscal" hub-fiscal "" hub-fiscal.jar hub "hubfiscal-calc-rg.service hubfiscal-calc-sn.service"
  escrever_agendamento
  systemctl daemon-reload
  systemctl enable hubfiscal-calc-rg hubfiscal-calc-sn hubfiscal hubfiscal-atualizacao.timer --quiet
  systemctl start hubfiscal-atualizacao.timer
  gravar_agendamento
  ok "Serviços registrados e habilitados na inicialização da VM"
  ok "Atualização automática agendada: todo dia às $HORARIO_ATUALIZACAO"

  liberar_firewall

  systemctl restart "${UNIDADES[@]}"
  etapa "Aguardando os serviços responderem (até 3 minutos)"
  aguardar_servicos || morrer "Algum serviço não subiu. Veja os logs com: sudo bash $RAIZ/scripts/linux/hub.sh logs"

  mostrar_enderecos
  echo
  echo "${VERDE}Instalação concluída.${FIM}"
  echo "Próximo passo: criar o administrador do hub com"
  echo "   sudo bash $RAIZ/scripts/linux/hub.sh criar-admin"
}

escrever_unidade() {  # nome descrição pasta argumentos-java jar log depende-de
  local nome=$1 descricao=$2 pasta=$3 args=$4 jar=$5 log=$6 depende=$7
  cat >"/etc/systemd/system/$nome.service" <<EOF
[Unit]
Description=$descricao
After=network-online.target ${depende}
Wants=network-online.target ${depende}

[Service]
User=$USUARIO_SERVICO
Group=$USUARIO_SERVICO
WorkingDirectory=$RAIZ/$pasta
ExecStart=$JAVA_HOME_HUB/bin/java $args -jar target/$jar
Restart=on-failure
RestartSec=10
# o Java encerra com 143 ao receber o pedido de parada: é um encerramento normal
SuccessExitStatus=143
StandardOutput=append:$LOGS/$log.log
StandardError=append:$LOGS/$log.err
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ProtectHome=true

[Install]
WantedBy=multi-user.target
EOF
}

escrever_agendamento() {
  cat >/etc/systemd/system/hubfiscal-atualizacao.service <<EOF
[Unit]
Description=HUB Fiscal - verifica versão nova das calculadoras da Receita e atualiza se houver
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/bin/bash $RAIZ/scripts/linux/hub.sh verificar
TimeoutStartSec=2h
EOF
  cat >/etc/systemd/system/hubfiscal-atualizacao.timer <<EOF
[Unit]
Description=HUB Fiscal - verificação diária de atualização das calculadoras ($HORARIO_ATUALIZACAO)

[Timer]
OnCalendar=*-*-* $HORARIO_ATUALIZACAO:00
# se a VM estava desligada às 03:00, verifica quando ligar
Persistent=true

[Install]
WantedBy=timers.target
EOF
}

liberar_firewall() {
  etapa "Firewall"
  if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    local rede
    for rede in 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16; do
      ufw allow from "$rede" to any port 8090 proto tcp comment 'HUB Fiscal' >/dev/null
    done
    ok "Porta 8090 liberada só para redes internas (as calculadoras ficam só em 127.0.0.1)"
  else
    aviso "Firewall (ufw) inativo: nada a fazer. As calculadoras atendem só a própria VM (127.0.0.1); da rede, só o hub (8090)."
  fi
}

principal "$@"
