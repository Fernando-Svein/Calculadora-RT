#!/usr/bin/env bash
# Hub Fiscal no Linux – teste da classificação de cClassTrib conferida na calculadora oficial.
#
#   bash /opt/hub-fiscal/scripts/linux/testar-classificacao.sh          (ou: hub.sh testar)
#
# Sobe uma cópia do hub instalado na porta 8091, só em 127.0.0.1, com usuários e empresas temporários,
# e envia a NF-e e o SPED de scripts/linux/testes/ para o lote de classificação. A cópia usa a calculadora
# oficial do Regime Geral que já está no ar (8080), só para consultas e cálculos. O hub em uso (8090), os
# usuários e as empresas não são tocados. No fim a cópia é desligada e os arquivos temporários apagados.
# Não precisa de sudo. Código de saída: 0 = tudo certo; 1 = alguma falha.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/comum.sh"

PORTA_TESTE=8091
JAR="$RAIZ/hub-fiscal/target/hub-fiscal.jar"
JAVA="$JAVA_HOME_HUB/bin/java"

etapa "Conferindo pré-requisitos"
[[ -x "$JAVA" ]] || morrer "Java não encontrado em $JAVA (rode o instalar.sh)."
[[ -r "$JAR" ]] || morrer "Hub não compilado: $JAR (rode hub.sh atualizar-hub)."
[[ -r "$BANCO_RG" ]] || morrer "Banco oficial não encontrado: $BANCO_RG."
command -v python3 >/dev/null || morrer "python3 não encontrado (sudo apt install -y python3)."
curl -fs -o /dev/null http://127.0.0.1:8080/api/calculadora/dados-abertos/versao \
  || morrer "Calculadora do Regime Geral fora do ar (sudo bash $RAIZ/scripts/linux/hub.sh iniciar)."
curl -s -o /dev/null "http://127.0.0.1:$PORTA_TESTE/" && morrer "A porta $PORTA_TESTE já está em uso."
ok "Java, hub compilado, banco oficial e calculadora no ar"

etapa "Subindo uma cópia do hub para teste (porta $PORTA_TESTE)"
TMP=$(mktemp -d)
PID=
encerrar() {
  [[ -n "$PID" ]] && kill "$PID" 2>/dev/null && wait "$PID" 2>/dev/null
  rm -rf "$TMP"
}
trap encerrar EXIT
# entrada e saídas redirecionadas: a cópia não prende o terminal nem a saída do script; exec: $! é o próprio Java
( cd "$TMP" && exec "$JAVA" -jar "$JAR" --server.port=$PORTA_TESTE --server.address=127.0.0.1 \
    --hub.api-regime-geral=http://127.0.0.1:8080/api --hub.banco-oficial="$BANCO_RG" \
    --hub.banco-usuarios="$TMP/usuarios.db" --hub.banco-empresas="$TMP/empresas.db" --hub.arquivo-logo="$TMP/logo.png" \
    --hub.arquivo-ultima-atualizacao="$TMP/ultima-atualizacao.json" --hub.atualizacao-manual=desativada \
    --hub.consulta-cnpj-url=desativada ) </dev/null >"$TMP/hub.log" 2>&1 &
PID=$!
for _ in $(seq 1 90); do
  curl -s -o /dev/null "http://127.0.0.1:$PORTA_TESTE/auth/estado" && break
  kill -0 "$PID" 2>/dev/null || { tail -30 "$TMP/hub.log"; morrer "A cópia de teste não subiu (log acima)."; }
  sleep 1
done
curl -s -o /dev/null "http://127.0.0.1:$PORTA_TESTE/auth/estado" || { tail -30 "$TMP/hub.log"; morrer "A cópia de teste não respondeu em 90 s."; }
ok "cópia no ar"

python3 "$RAIZ/scripts/linux/testes/teste-classificacao.py" "$PORTA_TESTE" "$RAIZ/scripts/linux/testes"
codigo=$?
if [[ $codigo -eq 0 ]]; then ok "Classificação de cClassTrib: tudo certo"; else falha "Classificação de cClassTrib: há falhas (acima)"; tail -20 "$TMP/hub.log"; fi
exit $codigo
