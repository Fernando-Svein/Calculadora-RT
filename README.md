# HUB Fiscal (Ubuntu Server)

Aplicação web interna do departamento fiscal, construída sobre as **calculadoras oficiais da Receita Federal/Serpro** (CBS, IBS e Imposto Seletivo – LC 214/2025). Atende clientes do **Regime Regular** e do **Simples Nacional**.

> Esta é a branch **`ubuntu-server`**: a versão para rodar num servidor Ubuntu. Os scripts e testes do Windows ficam na branch `main`.
> O código do hub e das telas é o mesmo nas duas; mudanças feitas na `main` são trazidas para cá com `git merge main`.

## Instalação (Ubuntu Server 24.04 LTS)

Requisitos da VM: 4 vCPU, 6 a 8 GB de RAM, 40 GB de disco e acesso à internet (`github.com` e `repo.maven.apache.org`).

```bash
sudo git clone -b ubuntu-server https://github.com/Fernando-Svein/Calculadora-RT.git /opt/hub-fiscal
sudo bash /opt/hub-fiscal/scripts/linux/instalar.sh          # 10 a 20 minutos
sudo bash /opt/hub-fiscal/scripts/linux/hub.sh criar-admin     # cria o administrador do hub
```

O instalador:

- instala `git`, `curl` e `jq` e baixa o Java 25 (Eclipse Temurin) para `tools/`;
- cria o usuário de sistema `hubfiscal` (sem login), dono dos arquivos e dos processos;
- baixa as calculadoras oficiais do GitHub da Receita, gera o banco oficial (Flyway) e compila os três serviços;
- registra três serviços no systemd, que sobem com a VM e voltam sozinhos se caírem:
  `hubfiscal-calc-rg` (127.0.0.1:8080), `hubfiscal-calc-sn` (127.0.0.1:8082) e `hubfiscal` (porta 8090);
- agenda `hubfiscal-atualizacao.timer`: todo dia às 03:00 verifica se a Receita publicou versão nova das calculadoras;
- se o firewall `ufw` estiver ativo, libera a porta 8090 só para redes internas.

Acesse `http://<ip-do-servidor>:8090`. As calculadoras atendem só a própria VM; da rede, só o hub, que exige login.
Ajuste o fuso para as datas e o horário da atualização: `sudo timedatectl set-timezone America/Sao_Paulo`.

## Operação

Todos os comandos: `sudo bash /opt/hub-fiscal/scripts/linux/hub.sh <comando>`

| Comando | O que faz |
|---|---|
| `status` | Situação dos três serviços, versão da base oficial e próxima verificação de atualização |
| `iniciar` / `parar` / `reiniciar` | Liga, desliga ou reinicia os três serviços |
| `logs [hub\|regime-geral\|simples-nacional]` | Acompanha o log (Ctrl+C para sair) |
| `criar-admin` | Cria o administrador inicial (só enquanto não há usuários) |
| `verificar` | Atualiza as calculadoras só se houver versão nova; se falhar, volta à anterior (é o que roda às 03:00) |
| `atualizar` | Força a atualização das calculadoras: git pull, banco novo, compilação e reinício |
| `atualizar-hub` | Baixa a versão nova do HUB Fiscal desta branch, recompila e reinicia só o hub |

O resultado de cada verificação aparece em **Sistema → Situação do sistema** e em `logs/atualizacao.log`.

**Backup:** inclua a pasta `/opt/hub-fiscal/dados/` (usuários, senhas e logo). O resto se recria a partir dos repositórios.
Se perder o acesso de todos os administradores: `hub.sh parar`, renomeie esse arquivo, `hub.sh iniciar` e rode `hub.sh criar-admin`.

## Acesso (login e senha)

Depois do `criar-admin`, o administrador cadastra a equipe em **Sistema → Usuários**:

- cada usuário novo recebe uma **senha provisória**, mostrada uma única vez, que ele troca no primeiro acesso;
- perfis: **Usuário** (usa as ferramentas liberadas para ele) e **Administrador** (todas as ferramentas e gestão de usuários);
- **Desativar** bloqueia o acesso na hora; **Redefinir senha** gera nova provisória;
- senhas com no mínimo 8 caracteres, letras e números (BCrypt); 5 erros seguidos bloqueiam o usuário por 15 minutos e 20 erros
  do mesmo computador bloqueiam esse computador por 15 minutos; a sessão expira após 8 horas sem uso.

## Logo do escritório

O administrador cadastra a logo em **Sistema → Logo do escritório** (PNG ou JPEG, até 2 MB; de preferência PNG com fundo
transparente). Ela aparece para todos no cabeçalho do menu, na tela de login e no ícone da aba do navegador; sem logo, fica o
ícone padrão "HF". No modo escuro a logo ganha uma placa branca para continuar legível.

Por segurança o hub não guarda o arquivo enviado: confere o formato pelos bytes (SVG é recusado, pois pode conter scripts),
abre a imagem e regrava como um PNG novo, reduzido para no máximo 640 × 320 px. O arquivo fica em
`/opt/hub-fiscal/dados/logo.png` (configurável em `hub.arquivo-logo`) – inclua no backup junto com os usuários.

## O que o hub oferece

**Consultas e tabelas:** classificar NCM/NBS (cClassTrib, anexos da LC 214/2025, Imposto Seletivo, fundamentação), tabela cClassTrib/CST,
alíquotas de referência, atividades do Simples, ferramentas da NFS-e, códigos de erro e demais dados abertos.

**Calculadoras:** simulador Regime Regular (vários itens, base pelos componentes da NF-e, IS, compra governamental, XML do grupo IBS/CBS),
bases de cálculo, pedágio, simulador Simples Nacional e comparador Simples × regime regular.

**Cenário de CBS (seletor "CBS" no topo):** simulações com 11,3% (TCU) sem alterar o cálculo oficial. Em 2026 o hub recalcula só a CBS
da resposta oficial; a partir de 2027 a alíquota vai como nominal para a calculadora. XML gerado e auditoria seguem a alíquota oficial.
Configuração em `hub.cenario-cbs` no `application.yml`.

**Processamento em lote (com Excel):** classificar cadastro a partir de SPED/XML e auditar o IBS/CBS destacado nos XMLs.

## Estrutura

```
/opt/hub-fiscal/
├─ calculadora-rtc-regime-regular/    repositório oficial da Receita (não alterar) – 127.0.0.1:8080
├─ calculadora-rtc-simples-nacional/  repositório oficial da Receita (não alterar) – 127.0.0.1:8082
├─ hub-fiscal/                        aplicação do escritório (Spring Boot 4 + telas HTML/JS) – porta 8090
│  └─ src/main/resources/application.yml   URLs das calculadoras, caminhos, paralelismo, cenário de CBS
├─ scripts/linux/                     instalar.sh, hub.sh e comum.sh
├─ dados/                             hub-usuarios.db (fazer backup)
├─ logs/                              logs dos três serviços e das atualizações
├─ docs/                              documentação completa em PDF
└─ tools/                             Java 25 portátil
```

Fluxo: tela → hub (8090) → calculadora oficial. Os repositórios da Receita são usados **sem modificação**, para que as atualizações
sejam um simples `git pull`. O hub lê o banco oficial (SQLite) apenas em modo leitura.

## Limites conhecidos

- A partir de 2027 a base oficial não traz alíquotas de referência; os simuladores usam alíquotas informadas e marcam o resultado como simulado. O pedágio só calcula datas de 2026.
- As sugestões de classificação consideram NCM/NBS, documento e CFOP; requisitos externos exigem análise do responsável.
- O hub usa HTTP: use apenas na rede interna.

## Desenvolvimento

As mudanças no hub são feitas e testadas na branch `main` (que tem os testes automatizados) e trazidas para esta branch:

```bash
git checkout ubuntu-server
git merge main      # se a main mexeu num arquivo do Windows removido aqui: git rm <arquivo> e conclua o merge
git push
```

No servidor, aplique com `sudo bash /opt/hub-fiscal/scripts/linux/hub.sh atualizar-hub`.

Convenções de código: veja [CONVENCOES.md](CONVENCOES.md).
