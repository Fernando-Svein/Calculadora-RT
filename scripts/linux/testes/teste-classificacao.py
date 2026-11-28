#!/usr/bin/env python3
"""Verificações da classificação de cClassTrib conferida na calculadora oficial (chamado por testar-classificacao.sh).

Envia ao hub uma NF-e com um item para cada situação e o SPED de teste, e confere o status, a resposta da
calculadora oficial, o "O que revisar", o resumo e as colunas da planilha. Os códigos de erro esperados
(REG-011, REG-006, REG-005, REG-029) são os que a calculadora da Receita devolve para cada caso.

Uso: teste-classificacao.py <porta do hub> <pasta com os arquivos de teste>
"""
import http.cookiejar
import io
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

PORTA, DADOS = int(sys.argv[1]), sys.argv[2]
BASE = f"http://127.0.0.1:{PORTA}"
NFE, SPED = "nfe-classificacao-conferencia.xml", "sped-classificacao.txt"
jar = http.cookiejar.CookieJar()
web = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
resultados = []


def ok(nome, cond, detalhe=""):
    resultados.append(bool(cond))
    print(("    OK  " if cond else "    !!  ") + nome + ("" if cond else f"\n        obtido: {detalhe}"))


def csrf():
    return next((urllib.parse.unquote(c.value) for c in jar if c.name == "XSRF-TOKEN"), "")


def req(metodo, url, corpo=None, tipo="application/json"):
    r = urllib.request.Request(BASE + url, data=corpo, method=metodo,
                               headers={"Accept": "application/json", "X-XSRF-TOKEN": csrf(), "Content-Type": tipo})
    try:
        with web.open(r, timeout=600) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def lote(data, arquivos):
    b = uuid.uuid4().hex
    corpo = b""
    for a in arquivos:
        with open(f"{DADOS}/{a}", "rb") as f:
            corpo += (f"--{b}\r\nContent-Disposition: form-data; name=\"arquivos\"; filename=\"{a}\"\r\n"
                      "Content-Type: application/octet-stream\r\n\r\n").encode() + f.read() + b"\r\n"
    corpo += f"--{b}--\r\n".encode()
    st, resp = req("POST", f"/hub/lote/classificacao?data={data}", corpo, f"multipart/form-data; boundary={b}")
    return st, (json.loads(resp) if resp.startswith(b"{") else {"erro": resp[:500]})


# administrador da cópia de teste (banco de usuários temporário)
web.open(BASE + "/auth/csrf").read()
req("POST", "/auth/primeiro-acesso", json.dumps({"usuario": "teste.adm", "nome": "Teste", "senha": "Teste12345"}).encode())
st, c = req("POST", "/auth/login", json.dumps({"usuario": "teste.adm", "senha": "Teste12345"}).encode())
if st != 200:
    sys.exit(f"login na cópia de teste falhou: HTTP {st} {c[:300]}")

# ------------------------------------------------------------------ 2026: um item para cada situação
print("\n==> Lote com vigência 2026-03-10 (NF-e com 7 casos + SPED)")
st, r = lote("2026-03-10", [NFE, SPED])
ok("lote processado (HTTP 200)", st == 200, r)
if st != 200:
    sys.exit(1)
L = {l["codigo"]: l for l in r["linhas"]}
for cod in sorted(k for k in L if k.startswith("C")):
    x = L[cod]
    print(f"    {cod} {x['descricao'][:31]:31} {x['status']:10} atual: {x.get('oficialAtual')}"
          f" | sugerido {x.get('cClassTribSugerido')}: {x.get('oficialSugerido')}")

CASOS = [  # código, situação, status esperado, resposta oficial (atual), trecho do "O que revisar"
    ("C1", "cClassTrib correto (arroz 200003)", "CONFERIDO", "Aceita", None),
    ("C2", "NCM fora do anexo", "DIVERGENTE", "Rejeitada: REG-011", "REG-011"),
    ("C3", "CST incompatível", "DIVERGENTE", "Rejeitada: REG-006", "incompatível"),
    ("C4", "cClassTrib inexistente", "DIVERGENTE", "Rejeitada: REG-005", "não existe"),
    ("C5", "cClassTrib não admitido na NF-e", "DIVERGENTE", "Rejeitada: REG-029", "não é admitido"),
    ("C6", "benefício não aplicado", "REVISAR", "Aceita", "benefício não aplicado"),
    ("C7", "tributação integral correta", "CONFERIDO", "Aceita", None),
]
print()
for cod, nome, status, oficial, trecho in CASOS:
    x = L.get(cod, {})
    revisar = x.get("revisar") or ""
    certo = (x.get("status") == status and (x.get("oficialAtual") or "").startswith(oficial)
             and x.get("oficialSugerido") == "Aceita" and (trecho in revisar if trecho else not revisar))
    ok(f"{cod} {nome}: {status}, calculadora \"{oficial}\"", certo,
       f"status={x.get('status')} atual={x.get('oficialAtual')} sugerido={x.get('oficialSugerido')} revisar={revisar}")
ok("SPED: sugestão dos itens sem cClassTrib conferida na calculadora",
   all(L[c].get("oficialSugerido") == "Aceita" for c in ("P1", "P2", "P6")), {c: L[c].get("oficialSugerido") for c in ("P1", "P2", "P6")})
ok("SPED: item com NCM inválido ou sem NCM não vai à calculadora",
   not L["P5"].get("oficialSugerido") and not L["P3"].get("oficialSugerido"), (L["P5"].get("oficialSugerido"), L["P3"].get("oficialSugerido")))
ok("resumo: 5 aceitas, 4 rejeitadas, 0 não conferidas",
   "5 aceita(s), 4 rejeitada(s), 0 não conferida(s)" in (r.get("conferenciaOficial") or ""), r.get("conferenciaOficial"))

# ------------------------------------------------------------------ planilha
print("\n==> Planilha Excel")
st, xlsx = req("GET", f"/hub/lote/classificacao/{r['id']}/excel")
ok("download do Excel", st == 200 and xlsx[:2] == b"PK", st)
z = zipfile.ZipFile(io.BytesIO(xlsx))
abas = dict(re.findall(r'<sheet name="([^"]+)"[^>]*r:id="(rId\d+)"', z.read("xl/workbook.xml").decode()))
rels = {}
for rel in re.findall(r"<Relationship [^>]+>", z.read("xl/_rels/workbook.xml.rels").decode()):
    rels[re.search(r'Id="([^"]+)"', rel).group(1)] = re.search(r'Target="([^"]+)"', rel).group(1)


def aba(nome):
    alvo = rels[abas[nome]].lstrip("/")
    return z.read(alvo if alvo.startswith("xl/") else "xl/" + alvo).decode()


cl = aba("Classificação")
cab = re.findall(r"<t[^>]*>([^<]*)</t>", re.search(r'<row r="1".*?</row>', cl, re.S).group(0))
ok("colunas Status e \"O que revisar\" no início", cab[:2] == ["Status", "O que revisar"], cab[:2])
ok("colunas \"Calculadora oficial (atual)\" e \"(sugerido)\"",
   "Calculadora oficial (atual)" in cab and "Calculadora oficial (sugerido)" in cab, cab)
removidas = [c for c in ("Ocorrências", "CFOPs de entrada", "Sugestões por CFOP", "cClassTrib definido (preencher)",
                         "Responsável / data (preencher)") if c in cab]
ok("sem as colunas removidas (Ocorrências, CFOPs de entrada, Sugestões por CFOP, a preencher)", not removidas, removidas)
ok("erro oficial (REG-011) na planilha", "REG-011" in cl)
ok("Resumo com a conferência na calculadora oficial", "Conferência na calculadora oficial" in aba("Resumo"))

# ------------------------------------------------------------------ 2027: alíquotas nominais obrigatórias
print("\n==> Lote com vigência 2027-06-01 (a calculadora exige alíquotas nominais)")
st, r = lote("2027-06-01", [NFE])
L = {l["codigo"]: l for l in r.get("linhas", [])}
ok("lote processado (HTTP 200)", st == 200, r)
ok("nenhuma combinação \"Não conferida\"", " 0 não conferida(s)" in (r.get("conferenciaOficial") or ""), r.get("conferenciaOficial"))
ok("C7 aceita e C2 rejeitada (REG-011)", L.get("C7", {}).get("oficialAtual") == "Aceita"
   and (L.get("C2", {}).get("oficialAtual") or "").startswith("Rejeitada: REG-011"),
   (L.get("C7", {}).get("oficialAtual"), L.get("C2", {}).get("oficialAtual")))

print(f"\n{sum(resultados)}/{len(resultados)} verificações OK")
sys.exit(0 if all(resultados) else 1)
