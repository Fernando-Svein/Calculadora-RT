#!/usr/bin/env bash
# Hub Fiscal no Linux – funções usadas por instalar.sh e hub.sh (não execute este arquivo direto).
#
# Mesma lógica do scripts\hub.ps1 do Windows: as calculadoras da Receita são clonadas sem modificação,
# o banco oficial é gerado pelo Flyway do próprio repositório da Receita e os três serviços rodam com
# Java 25. No Linux, cada serviço é uma unidade do systemd que roda com o usuário "hubfiscal"
# (sem login e sem privilégio de administrador) e volta sozinha se cair ou se a VM reiniciar.

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
USUARIO_SERVICO=hubfiscal
HOME_SERVICO=/var/lib/hubfiscal
LOGS="$RAIZ/logs"
JAVA_HOME_HUB="$RAIZ/tools/java-atual"   # link para a pasta do JDK 25 baixado
ARQUIVO_RESULTADO="$LOGS/ultima-atualizacao.json"

# Repositórios oficiais da Receita: usados sem modificação (só clone e git pull)
REPOS=(calculadora-rtc-regime-regular calculadora-rtc-simples-nacional)
declare -A URL_REPO=(
  [calculadora-rtc-regime-regular]=https://github.com/ReceitaFederal/calculadora-rtc-regime-regular.git
  [calculadora-rtc-simples-nacional]=https://github.com/ReceitaFederal/calculadora-rtc-simples-nacional.git
)

# Serviços: unidade systemd | nome | pasta | jar | endereço de verificação | arquivo de log
SERVICOS=(
  "hubfiscal-calc-rg|Calculadora Regime Geral|calculadora-rtc-regime-regular|api-regime-geral.jar|http://127.0.0.1:8080/api/calculadora/dados-abertos/versao|regime-geral"
  "hubfiscal-calc-sn|Calculadora Simples Nacional|calculadora-rtc-simples-nacional|api-simples-nacional.jar|http://127.0.0.1:8082/api/versao|simples-nacional"
  "hubfiscal|Hub Fiscal|hub-fiscal|hub-fiscal.jar|http://127.0.0.1:8090/hub/status|hub"
)
UNIDADES=(hubfiscal-calc-rg hubfiscal-calc-sn hubfiscal)

BANCO_RG="$RAIZ/calculadora-rtc-regime-regular/calculadora/db/calculadora-pro.db"
BANCO_SN="$RAIZ/calculadora-rtc-simples-nacional/calculadora/db/calculadora-pro.db"

if [[ -t 1 ]]; then AZUL=$'\e[36m'; VERDE=$'\e[32m'; VERMELHO=$'\e[31m'; AMARELO=$'\e[33m'; FIM=$'\e[0m'; else AZUL=; VERDE=; VERMELHO=; AMARELO=; FIM=; fi
etapa() { echo; echo "${AZUL}==> $*${FIM}"; }
ok()    { echo "${VERDE}    OK  $*${FIM}"; }
falha() { echo "${VERMELHO}    !!  $*${FIM}" >&2; }
aviso() { echo "${AMARELO}    --  $*${FIM}"; }
morrer() { falha "$*"; exit 1; }

exigir_root() {
  [[ $EUID -eq 0 ]] || morrer "Rode com sudo: sudo bash $0 ${*:-}"
}

# Executa um comando como o usuário do serviço, com o Java 25 do hub
como_servico() {
  runuser -u "$USUARIO_SERVICO" -- env JAVA_HOME="$JAVA_HOME_HUB" PATH="$JAVA_HOME_HUB/bin:/usr/local/bin:/usr/bin:/bin" \
    HOME="$HOME_SERVICO" "$@"
}

maven() {  # maven <pasta> <argumentos...>
  local pasta=$1; shift
  ( cd "$RAIZ/$pasta" && como_servico sh ./mvnw "$@" --batch-mode -q )
}

git_servico() {  # git_servico <pasta> <argumentos...>
  local pasta=$1; shift
  como_servico git -C "$RAIZ/$pasta" "$@"
}

# ---------------------------------------------------------------- Java 25 (Eclipse Temurin, portátil)
instalar_java() {
  if [[ -x "$JAVA_HOME_HUB/bin/java" ]]; then ok "Java 25 já instalado ($(readlink "$JAVA_HOME_HUB"))"; return 0; fi
  local arq
  case "$(uname -m)" in x86_64) arq=x64 ;; aarch64) arq=aarch64 ;; *) falha "Processador $(uname -m) não suportado"; return 1 ;; esac
  etapa "Baixando o Java 25 (Eclipse Temurin, ~200 MB)"
  mkdir -p "$RAIZ/tools"
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/25/ga/linux/$arq/jdk/hotspot/normal/eclipse" -o "$RAIZ/tools/jdk25.tar.gz" || { falha "Download do Java falhou"; return 1; }
  tar -xzf "$RAIZ/tools/jdk25.tar.gz" -C "$RAIZ/tools" || { falha "Não foi possível extrair o Java"; return 1; }
  rm -f "$RAIZ/tools/jdk25.tar.gz"
  local pasta
  pasta=$(find "$RAIZ/tools" -maxdepth 1 -type d -name 'jdk-25*' | sort | tail -n 1)
  [[ -n "$pasta" ]] || { falha "Pasta do JDK 25 não encontrada após extrair"; return 1; }
  ln -sfn "$(basename "$pasta")" "$JAVA_HOME_HUB"
  ok "Java instalado em $pasta"
}

# ---------------------------------------------------------------- calculadoras, banco oficial e compilação
baixar_calculadoras() {
  local r
  for r in "${REPOS[@]}"; do
    [[ -f "$RAIZ/$r/pom.xml" ]] && continue
    etapa "Baixando a calculadora oficial $r (GitHub da Receita Federal)"
    como_servico git clone --depth 50 "${URL_REPO[$r]}" "$RAIZ/$r" || { falha "git clone falhou para $r"; return 1; }
    ok "$r baixada"
  done
}

gerar_banco() {
  etapa "Gerando o banco de dados oficial (Flyway)"
  como_servico mkdir -p "$(dirname "$BANCO_RG")" || return 1
  rm -f "$BANCO_RG"
  maven calculadora-rtc-regime-regular flyway:migrate -Dflyway.configFiles=flyway/flyway-pro.conf || { falha "Flyway falhou"; return 1; }
  ok "Banco gerado: $BANCO_RG"
}

copiar_banco_simples() {
  como_servico mkdir -p "$(dirname "$BANCO_SN")" || return 1
  como_servico cp -f "$BANCO_RG" "$BANCO_SN" || return 1
  ok "Banco copiado para a calculadora do Simples Nacional"
}

compilar() {  # compilar <pasta> <nome>
  etapa "Compilando $2"
  maven "$1" clean package -DskipTests || { falha "Compilação falhou: $2"; return 1; }
  ok "$2 compilado"
}

compilar_todos() {
  local s unidade nome pasta jar url log
  for s in "${SERVICOS[@]}"; do
    IFS='|' read -r unidade nome pasta jar url log <<<"$s"
    compilar "$pasta" "$nome" || return 1
  done
}

# ---------------------------------------------------------------- serviços
url_ok() {
  local codigo
  codigo=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$1" 2>/dev/null) || true
  # 401/403: o serviço está no ar, só exige login (o hub protege /hub/status)
  [[ "$codigo" == 200 || "$codigo" == 401 || "$codigo" == 403 ]]
}

aguardar_servicos() {
  local s unidade nome pasta jar url log i todos=0
  for s in "${SERVICOS[@]}"; do
    IFS='|' read -r unidade nome pasta jar url log <<<"$s"
    for ((i = 0; i < 90; i++)); do url_ok "$url" && break; sleep 2; done
    if url_ok "$url"; then ok "$nome respondendo"; else falha "$nome não respondeu. Veja: sudo bash $RAIZ/scripts/linux/hub.sh logs $log"; todos=1; fi
  done
  return $todos
}

iniciar_servicos() {
  local s unidade nome pasta jar url log
  [[ -f "$BANCO_RG" ]] || gerar_banco || return 1
  [[ -f "$BANCO_SN" ]] || copiar_banco_simples || return 1
  for s in "${SERVICOS[@]}"; do
    IFS='|' read -r unidade nome pasta jar url log <<<"$s"
    [[ -f "$RAIZ/$pasta/target/$jar" ]] || compilar "$pasta" "$nome" || return 1
  done
  etapa "Iniciando os serviços"
  systemctl start "${UNIDADES[@]}" || { falha "systemctl start falhou"; return 1; }
  aguardar_servicos
}

parar_servicos() {
  systemctl stop hubfiscal hubfiscal-calc-sn hubfiscal-calc-rg && ok "Serviços parados"
}

# Depois de baixar uma versão nova das calculadoras: banco, compilação dos três serviços e início
reconstruir() {
  gerar_banco && copiar_banco_simples && compilar_todos && iniciar_servicos
}

mostrar_enderecos() {
  local ip
  echo
  echo "${AMARELO}Hub Fiscal disponível em:${FIM}"
  for ip in $(hostname -I 2>/dev/null); do
    [[ "$ip" == *:* ]] && continue
    echo "   http://$ip:8090"
  done
  echo "   http://$(hostname).mshome.net:8090   (computador do Hyper-V, quando a VM usa o Default Switch)"
}
