# Hub Fiscal – Manual de instalação, operação e histórico

Documento de consulta para quem administra o Hub Fiscal: como a VM de produção foi montada, como instalar do zero,
os comandos do dia a dia, **como atualizar a ferramenta e a base da Receita**, problemas já enfrentados (e a solução de
cada um) e os bugs corrigidos ao longo do projeto.

> Atalho para o que mais se usa:
>
> | Preciso de… | Comando (no SSH da VM) |
> |---|---|
> | Atualizar o **Hub Fiscal** (versão nova publicada no GitHub) | `sudo bash /opt/hub-fiscal/scripts/linux/hub.sh atualizar-hub` |
> | Atualizar a **base das calculadoras da Receita** agora | Tela **Sistema → Situação do sistema → Atualizar agora**, ou `sudo bash /opt/hub-fiscal/scripts/linux/hub.sh verificar` |
> | Ver se está tudo no ar | `sudo bash /opt/hub-fiscal/scripts/linux/hub.sh status` |
> | Ver o log do hub | `sudo bash /opt/hub-fiscal/scripts/linux/hub.sh logs hub` (Ctrl+C sai) |
> | Reiniciar tudo | `sudo bash /opt/hub-fiscal/scripts/linux/hub.sh reiniciar` |
> | Testar a classificação de cClassTrib (cópia isolada, ~15 s) | `bash /opt/hub-fiscal/scripts/linux/hub.sh testar` |
> | Endereço para os usuários | `http://hubfiscal:8090` |

---

## 1. Visão geral

O Hub Fiscal é uma aplicação web interna do departamento fiscal, construída **sobre as calculadoras oficiais da Receita
Federal/Serpro** (CBS, IBS e Imposto Seletivo – LC 214/2025). São três serviços Java na mesma máquina:

| Serviço | Porta | Acesso | O que é |
|---|---|---|---|
| Calculadora Regime Geral (regime normal) | 8080 | só a própria VM (127.0.0.1) | repositório oficial `calculadora-rtc-regime-regular`, sem modificação |
| Calculadora Simples Nacional | 8082 | só a própria VM (127.0.0.1) | repositório oficial `calculadora-rtc-simples-nacional`, sem modificação |
| **Hub Fiscal** | **8090** | rede interna | aplicação do escritório (login, telas, lotes, relatórios) |

Regras que não mudam:

- Os repositórios da Receita **nunca são alterados**: são clonados do GitHub oficial e atualizados com `git pull`.
- As calculadoras rodam no modo **offline** definido pela própria Receita: base de dados local (SQLite), cálculo idêntico
  ao da versão online, sem enviar dados para fora. A "Situação do sistema" mostra "Ambiente: offline" – é o esperado.
- Só o hub fica acessível na rede; ele exige login e aplica as permissões de cada usuário.

Repositório: `https://github.com/Fernando-Svein/Calculadora-RT` (público), com duas branches:

| Branch | Uso |
|---|---|
| `main` | desenvolvimento (Windows), com os testes automatizados (`testes\`) e os scripts `.bat`/`hub.ps1` |
| `ubuntu-server` | **produção (VM Linux)**: a `main` sem os arquivos do Windows e sem `testes\`, com o README de Linux |

---

## 2. A VM de produção (como foi montada)

### 2.1 Hyper-V (servidor Windows)

| Item | Valor usado |
|---|---|
| Nome | `HubFiscal` |
| Geração | **2** |
| Memória | **6 GB fixos** (memória dinâmica desligada: são três serviços Java) |
| Processadores | 2 a 4 virtuais |
| Disco | 60 GB |
| Rede | **switch Externo** (a VM recebe IP da rede do escritório) |
| Inicialização segura | modelo **Autoridade de Certificação UEFI da Microsoft** (sem isso o Ubuntu não dá boot) |
| Ação de início automático | **Sempre iniciar** – ver abaixo |

A VM precisa ligar sozinha quando o servidor físico reinicia. No PowerShell do servidor Hyper-V (como administrador):

```powershell
Get-VM HubFiscal | Select-Object Name, State, AutomaticStartAction
Set-VM -Name HubFiscal -AutomaticStartAction Start -AutomaticStartDelay 30
```

> O padrão do Hyper-V é "iniciar só se estava ligada quando o servidor desligou": se alguém desligar a VM antes de
> reiniciar o servidor, ela fica desligada. Com `Start`, sempre liga.

### 2.2 Instalação do Ubuntu Server 24.04 LTS

Telas do instalador:

1. Idioma Português; teclado **Portuguese (Brazil)**.
2. Tipo: **Ubuntu Server** (não o "minimized").
3. Rede: deixar por DHCP (o IP fixo é feito por reserva no roteador – item 2.4) ou fixar em **Edit IPv4 → Manual**.
4. Proxy em branco; mirror padrão.
5. Armazenamento: **Use an entire disk**.
6. Perfil:
   - Seu nome: `HubFiscal`
   - **Nome do servidor: `hubfiscal`** (é o nome pelo qual a rede encontra a VM)
   - **Usuário: `hub`** + senha forte (é pedida também no `sudo`)
7. Ubuntu Pro: **Skip for now**.
8. **Marcar "Install OpenSSH server"** (acesso pelo SSH).
9. Snaps: nenhum. Ao final, **Reboot Now** (e ejetar a ISO em Mídia → DVD).

### 2.3 Acesso pelo SSH

Do computador do escritório (PowerShell):

```powershell
ssh hub@hubfiscal
```

(ou `ssh hub@192.168.1.126`). Na primeira vez, responda `yes` para aceitar a chave do servidor.

### 2.4 Nome na rede (`http://hubfiscal:8090`)

- O nome da VM foi definido na instalação (`hubfiscal`). Quando a VM pega IP por DHCP, ela informa esse nome e o
  **roteador** passa a responder por ele (`ping hubfiscal` responde como `hubfiscal.localdomain [192.168.1.126]`).
  Por isso `http://hubfiscal:8090` funciona em todos os computadores que usam esse roteador.
- **Pendência recomendada:** reservar o IP **192.168.1.126** para a VM no DHCP do roteador ("Reserva de DHCP"/"Address
  Reservation", pelo MAC da VM – `ip -br link`). Sem a reserva, o IP pode mudar num reinício.
- Alternativa em redes com domínio (Active Directory): registro **A** `hubfiscal` → IP da VM no DNS do controlador:
  `Add-DnsServerResourceRecordA -ZoneName "suaempresa.local" -Name "hubfiscal" -IPv4Address "192.168.1.126"`.
- Para conferir num computador: `ipconfig /flushdns`, depois `nslookup hubfiscal` e `ping hubfiscal`.

### 2.5 Firewall e segurança

- O instalador mostra "Firewall (ufw) inativo: nada a fazer": é só informativo. As calculadoras atendem apenas a
  própria VM; da rede só responde o hub (8090, com login) e o SSH (22).
- Camada extra opcional (libere o SSH **antes**, senão o acesso remoto cai):
  ```bash
  sudo ufw allow 22/tcp
  sudo ufw allow from 192.168.0.0/16 to any port 8090 proto tcp
  sudo ufw enable
  ```
- Uso **somente interno**: nunca redirecionar a porta 8090 para a internet. O acesso é HTTP (sem criptografia);
  HTTPS com certificado interno é o próximo passo recomendado se houver Wi-Fi de visitantes na mesma rede.

---

## 3. Instalação do Hub Fiscal na VM (do zero)

No SSH da VM:

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y git
sudo git clone -b ubuntu-server https://github.com/Fernando-Svein/Calculadora-RT.git /opt/hub-fiscal
sudo bash /opt/hub-fiscal/scripts/linux/instalar.sh
```

O `instalar.sh` (10 a 20 minutos) faz tudo e pode ser rodado de novo sem problema:

1. instala git, curl, jq;
2. cria o usuário de sistema `hubfiscal` (sem login; dono dos arquivos e dos processos);
3. baixa o Java 25 (Eclipse Temurin) para `/opt/hub-fiscal/tools/`;
4. baixa as calculadoras do GitHub da Receita, gera a base oficial (Flyway) e compila os três serviços;
5. registra no systemd `hubfiscal-calc-rg`, `hubfiscal-calc-sn` e `hubfiscal` (ligam com a VM e voltam se caírem);
6. agenda a verificação diária de atualização da Receita (timer `hubfiscal-atualizacao.timer`, **03:00**) e o gatilho
   do botão "Atualizar agora" (`hubfiscal-atualizacao.path`);
7. libera a porta 8090 no ufw **só se** ele estiver ativo.

Depois, criar o administrador do hub e conferir:

```bash
sudo bash /opt/hub-fiscal/scripts/linux/hub.sh criar-admin
sudo bash /opt/hub-fiscal/scripts/linux/hub.sh status
```

Primeiros passos na tela (`http://hubfiscal:8090`): **Sistema → Usuários** (cadastrar a equipe e as ferramentas de
cada um), **Sistema → Logo do escritório**, **Cadastros → Empresas**.

Arquivos importantes na VM:

| Caminho | Conteúdo |
|---|---|
| `/opt/hub-fiscal/` | projeto (branch `ubuntu-server`) |
| `/opt/hub-fiscal/dados/` | **usuários (`hub-usuarios.db`), empresas (`hub-empresas.db`) e logo – fazer backup** |
| `/opt/hub-fiscal/logs/` | logs dos três serviços, `atualizacao.log`, `ultima-atualizacao.json` |
| `/opt/hub-fiscal/hub-fiscal/src/main/resources/application.yml` | configurações (seção 7) |
| `/etc/systemd/system/hubfiscal*.service/.timer/.path` | serviços, agendamento e gatilho |

---

## 4. Operação do dia a dia (`hub.sh`)

Todos com `sudo bash /opt/hub-fiscal/scripts/linux/hub.sh <comando>`:

| Comando | O que faz |
|---|---|
| `status` | situação dos três serviços, versões, última verificação e próxima atualização |
| `iniciar` / `parar` / `reiniciar` | liga, desliga, reinicia os três serviços |
| `logs [hub\|regime-geral\|simples-nacional]` | acompanha o log (Ctrl+C para sair) |
| `criar-admin` | cria o administrador inicial (só funciona enquanto não há usuários) |
| `verificar` | atualiza as calculadoras **só se** a Receita publicou versão nova; se falhar, volta à anterior |
| `atualizar` | força a atualização das calculadoras (git pull, base nova, compilação, reinício) |
| `atualizar-hub` | baixa a versão nova do **Hub Fiscal** (branch `ubuntu-server`), recompila e reinicia só o hub |
| `backup` | copia `dados/` para `backups/` agora (o automático roda todo dia às 02:30; ficam as 14 últimas cópias) |
| `testar` | testa a classificação de cClassTrib numa cópia do hub na porta 8091 (usuários temporários; não toca no hub em uso): NF-e com um item por situação (correto, REG-011, REG-006, REG-005, REG-029, benefício não aplicado), conferência na calculadora oficial, planilha e vigência 2027. Não precisa de sudo. Arquivos em `scripts/linux/testes/` |

Comandos do systemd úteis:

```bash
systemctl status hubfiscal --no-pager            # hub
systemctl is-enabled hubfiscal hubfiscal-calc-rg hubfiscal-calc-sn   # devem dizer "enabled"
systemctl status hubfiscal-atualizacao.timer hubfiscal-atualizacao.path --no-pager
journalctl -b -u hubfiscal --no-pager | tail -30 # o que aconteceu com o hub neste boot
uptime -s                                        # desde quando a VM está ligada
```

Depois de um reinício da VM, os serviços levam **1 a 2 minutos** para ficar prontos.

---

## 5. Atualizações

### 5.1 Atualizar o Hub Fiscal (versão nova da ferramenta)

Quando uma mudança é publicada no GitHub (branch `ubuntu-server`):

```bash
sudo bash /opt/hub-fiscal/scripts/linux/hub.sh atualizar-hub
```

Ele baixa (`git pull`), devolve os arquivos ao usuário do serviço, atualiza o agendamento e o gatilho, recompila só o
hub e o reinicia (as calculadoras não param). Leva 1 a 2 minutos. Depois, no navegador, **Ctrl+F5**.

> Se a compilação falhar, o hub continua na versão anterior: **não reinicie a VM**, corrija e rode `atualizar-hub` de novo.

### 5.2 Atualizar a base das calculadoras da Receita

- **Automático:** todo dia às 03:00 (timer do systemd). Se a VM estava desligada, roda quando ligar.
- **Na hora, pela tela:** Sistema → Situação do sistema → **Atualizar agora** (administradores). A tela mostra
  "solicitada", "em andamento" e o resultado. Se houver versão nova, as calculadoras e o hub ficam fora do ar por
  alguns minutos – avise a equipe.
- **Na hora, pelo SSH:** `sudo bash /opt/hub-fiscal/scripts/linux/hub.sh verificar`.

Em todos os casos só baixa e recompila quando a Receita publicou versão nova; senão, só registra "em dia". O topo do
hub mostra "Base atualizada em … · Próxima: …".

### 5.3 Fluxo de desenvolvimento (de uma mudança até a VM)

No computador de desenvolvimento (Windows, branch `main`):

1. alterar o código e testar: `parar-hub.bat` → compilar → `iniciar-hub.bat` → **`testar-hub.bat`** (todos os testes
   precisam passar);
2. commit na `main` e `git push`;
3. levar para a produção:
   ```bash
   git checkout ubuntu-server
   git merge main
   # conflitos esperados: arquivos do Windows e testes\ (modificados na main, apagados aqui) → git rm -f <arquivo>
   #   ou git rm -r -f testes ; application.yml → manter o comentário do Linux e as configurações novas;
   #   README.md → manter a versão "Hub Fiscal (Ubuntu Server)"
   git commit --no-edit
   git push
   git checkout main
   ```
4. na VM: `sudo bash /opt/hub-fiscal/scripts/linux/hub.sh atualizar-hub`.

---

## 6. Backup e restauração

- **O que copiar:** a pasta `/opt/hub-fiscal/dados/` (usuários e senhas, empresas e suas particularidades, logo). O
  resto se recria a partir dos repositórios.
- **Backup automático:** todo dia às 02:30 (`hubfiscal-backup.timer`) o hub grava
  `/opt/hub-fiscal/backups/dados-AAAAMMDD-HHMMSS.tar.gz` (bancos copiados com o `.backup` do sqlite3) e mantém as 14
  últimas cópias. Para fazer na hora: `sudo bash /opt/hub-fiscal/scripts/linux/hub.sh backup`. Copie essa pasta para
  fora da VM de vez em quando (outro servidor, nuvem): se a VM se perder, os backups vão junto.
- **Restaurar:** `hub.sh parar`, `sudo tar -xzf backups/dados-....tar.gz -C /opt/hub-fiscal`,
  `sudo chown -R hubfiscal:hubfiscal /opt/hub-fiscal/dados` e `hub.sh iniciar`.
- **Logs:** os arquivos de `logs/` giram todo dia (`/etc/logrotate.d/hubfiscal`), compactados, por 30 dias.
- Alternativa: backup/ponto de verificação da VM inteira no Hyper-V.
- Perdeu o acesso de todos os administradores: `hub.sh parar`, renomeie `dados/hub-usuarios.db`, `hub.sh iniciar` e
  rode `hub.sh criar-admin`.

---

## 7. Configurações (`application.yml`)

| Chave (`hub.`) | Padrão | Para quê |
|---|---|---|
| `banco-usuarios` | `../dados/hub-usuarios.db` | usuários e senhas (BCrypt) |
| `banco-empresas` | `../dados/hub-empresas.db` | empresas e particularidades |
| `arquivo-logo` | `../dados/logo.png` | logo do escritório |
| `consulta-cnpj-url` | BrasilAPI, minhareceita.org | serviços de consulta de CNPJ, em ordem (vírgula); `desativada` desliga |
| `atualizacao-manual` | `auto` | botão "Atualizar agora"; `desativada` esconde |
| `cenario-cbs.aliquota` / `rotulo` | `11.3` / `Cenário TCU` | cenário de CBS das simulações; apagar `aliquota` tira o seletor |
| `paralelismo` | 8 | chamadas simultâneas às calculadoras nos lotes |
| `retencao-resultados-minutos` | 240 | quanto tempo um resultado de lote fica disponível para download |
| `resultados-por-usuario` | 5 | resultados de lote guardados por usuário; o mais antigo sai antes da retenção |
| `lotes-simultaneos` | 2 | lotes processados ao mesmo tempo; os demais esperam na fila (a tela mostra "Na fila") |

O hub roda com no máximo 2 GB de memória (`MEMORIA_HUB` em `scripts/linux/comum.sh`). Se faltar, o Java encerra e o
systemd religa em 10 segundos, em vez de ficar travado. Para mudar: ajuste a variável e rode `hub.sh atualizar-hub`.

Depois de alterar: `sudo bash /opt/hub-fiscal/scripts/linux/hub.sh reiniciar`.

---

## 8. Problemas enfrentados e solução

| Sintoma | Causa | Solução |
|---|---|---|
| `git clone` na VM pedia usuário/senha | repositório estava privado | repositório tornado **público** |
| Na VM faltava `scripts/linux` após o `git pull` | o commit do Linux ainda não estava no GitHub | `git push` feito do computador de desenvolvimento |
| "Não consigo colar o código na VM" (console do Hyper-V) | o console não aceita colar | usar **SSH** (`ssh hub@hubfiscal`) |
| VM com IP diferente a cada reinício (teste no Default Switch) | Default Switch dá IP novo | usar o nome (`hubfiscal.mshome.net` no teste; `hubfiscal` em produção) e reservar o IP no roteador |
| Tela "Hub ainda não configurado" | nenhum administrador criado | `hub.sh criar-admin` |
| `criar-admin`: "O hub recusou: Recarregue a página" | o script enviava o token CSRF mascarado do JSON; o hub compara com o cookie | **corrigido**: o script envia o valor do cookie `XSRF-TOKEN` |
| Situação da atualização "Falhou" sem motivo | `ultima-atualizacao.json` vazio (lido durante a gravação) | **corrigido**: gravação atômica (arquivo temporário + `mv`) e arquivo vazio = "sem verificação" |
| Hub não volta depois de reiniciar o servidor | dentro da VM os serviços estavam `enabled` e subiram sozinhos (journal); a VM é que não ligava / acesso cedo demais | `Set-VM -AutomaticStartAction Start`; aguardar 1–2 min após o boot |
| Botão "Atualizar agora" parado em "solicitada" | o gatilho (`.path`) não tinha sido instalado: o `atualizar-hub` executava a versão **anterior** do script após o `git pull` | rodar `atualizar-hub` de novo; **corrigido**: o comando se reexecuta com o script novo após o pull |
| Consulta de CNPJ: "HTTP 503 / Bad Gateway" | a BrasilAPI responde 503 sempre para alguns CNPJs (ex.: 05.015.854/0001-65) | **corrigido**: serviço reserva **minhareceita.org** (fonte da BrasilAPI) |
| CBS do Cenário TCU "muito alta" na auditoria | não era erro: 11,3% ÷ 0,9% = 12,56× (R$ 75.710 → R$ 950.618, base ≈ R$ 8,4 mi) | conferência documentada; a CBS do regime pleno substitui PIS/COFINS |
| Aviso "Firewall (ufw) inativo" | informativo | ver 2.5 |
| "Ambiente: offline" nas calculadoras | modo oficial da Receita para instalação local | nada a fazer |

---

## 9. Bugs corrigidos (registro)

| Onde | Bug | Correção | Teste que cobre |
|---|---|---|---|
| `scripts/linux/hub.sh` (criar-admin) | token CSRF errado (403) | envia o cookie `XSRF-TOKEN` | uso manual na VM |
| `hub.sh` / `SistemaController` | JSON de atualização vazio → "Falhou" | gravação atômica; vazio = null | suíte Funcional |
| `hub.sh atualizar-hub` | passos novos só rodavam na 2ª execução | `exec` do script novo após o pull | uso na VM |
| `SpedParser` | SPED de 244 MB estourava a memória (1,2 GB de heap) | leitura linha a linha, UTF-8 validado sem alocar, itens sem mapas vazios | medição: erro → 6,4 s |
| `SpedParser` | SPED em UTF-8 com BOM perdia o registro 0000 | remove o BOM | "SPED com BOM" |
| `AuditoriaService` | data de emissão inválida derrubava o lote inteiro (500) | item "Não auditado" com motivo | "XML malformado (data-invalida)" |
| `AuditoriaService` | `nItem` inválido/repetido derrubava o lote | idem + proteção final | "XML malformado (nitem-texto)" |
| `AuditoriaService` | município com menos de 7 dígitos derrubava o lote | `infra/Ufs` valida o código | "XML malformado (municipio-curto)" |
| `XmlFiscalParser` | leitor de XML recriado a cada arquivo | leitor por thread reaproveitado | 20 mil XMLs: 5,6 s → 2,1 s |
| `ConsultaCnpjService` | 503 da BrasilAPI sem alternativa | serviço reserva | "principal com 503 → reserva" |
| Auditor | recalculava só com 0,9% mesmo com o cenário TCU | CBS oficial e do cenário lado a lado | "auditor: R$ 1.000 → 9,00 e 113,00" |
| CSS (Tabela cClassTrib e outras) | texto ao lado do campo colado na base | centralizado na altura do campo | auditoria de telas |
| CSS do topo | pílula de datas estourava a largura em 1366 px | rótulos curtos até 1520 px | auditoria de telas |
| Logo no cabeçalho | imagem saía do tamanho | altura fixa | auditoria de telas |
| `cenario-cbs.js` | listeners acumulavam a cada tela | remove ao sair | auditoria de telas |

Rodadas anteriores (24–25/11): auditoria de segurança (CSP, proxy com caminho canônico, calculadoras só em
127.0.0.1, sessão/CSRF), rótulos em linguagem de contador, acessibilidade (rótulos ligados aos campos).

---

## 10. Funcionalidades (resumo por versão)

| Data | Commit | Principais mudanças |
|---|---|---|
| 24/11 | `9d4369a` … `7d2f13a` | hub sobre as calculadoras; login, perfis e permissões por ferramenta; novo visual |
| 25/11 | `f42a2a2` | auditoria, 5S, testes automatizados, cenário de CBS 11,3%, telas em linguagem de contador |
| 25/11 | `54e4b27`, `f3c7202`, `55820a8` | documentação PDF; instalação e operação no Ubuntu (systemd, timer 03:00) |
| 26/11 | `0e7ddb7`, `556b654` | correções do Linux (criar-admin, arquivo de atualização) |
| 26/11 | `75d6ccc` | nome, logo cadastrável, acessibilidade |
| 26/11 | `e2764d2` | cadastro de empresas, empresa nos simuladores, relatórios PDF/Excel, datas da base no topo |
| 26/11 | `a98c9a3` | importação XML/SPED no simulador, consulta de CNPJ, auditor 0,9% × 11,3%, "Atualizar agora", QA e desempenho |
| 26/11 | `39b9130` | `atualizar-hub` com o script novo |
| 26/11 | `163e6fe` | importação pergunta antes de cadastrar empresas, seleção múltipla com exclusão, CNPJ com serviço reserva |
| 26/11 | (este) | vendas por NCM na classificação e na auditoria (tela e Excel); este manual |

Destaques de uso:

- **Cadastros → Empresas:** particularidades usadas nos simuladores; consulta de CNPJ (situação, CNAEs, endereço,
  Simples/MEI, QSA); seleção múltipla e "Excluir selecionadas". Ao importar SPED/XML, o hub **pergunta** quais
  empresas novas adicionar (cada uma com a consulta do CNPJ).
- **Simuladores:** seletor "Empresa da simulação"; relatório PDF e Excel; o Simulador Regime Regular importa XML/SPED
  (cClassTrib ausente vira sugestão marcada em amarelo).
- **Lotes (classificação e auditoria):** planilha Excel; quadro e aba **Vendas por NCM** (seção 11); auditor com CBS a
  0,9% e no cenário TCU lado a lado.
- **Situação do sistema:** datas da base, botão "Atualizar agora".

## 11. Vendas por NCM – o que entra

- **Venda:** item de saída com CFOP de venda ou prestação (5/6/7 com final 101–125, 251–258, 301–307, 351–360,
  401–405, 651–656, 667, 932, 933); NFS-e; cupom fiscal (C425); serviço prestado no SPED (A170 com IND_OPER = 1).
- **Fica de fora (com o valor informado na tela):** entradas, remessas, transferências, devoluções, bonificações,
  amostras, venda de ativo (551), 929 (nota também lançada no ECF) e notas canceladas/denegadas.
- Agrupado por **empresa** (emitente do XML / contribuinte do SPED) e NCM (NBS nos serviços): em XMLs de compra o
  emitente é o fornecedor – use o filtro de empresa.
- Nota presente no SPED e no XML (mesma chave) conta **uma vez**. Valor vendido = produtos − descontos.

---

## 12. Testes automatizados (computador de desenvolvimento)

**Testes unitários (JUnit):** `cd hub-fiscal` e `mvnw test`. Leitores de SPED e XML (inclusive XML malicioso), regras
de CFOP, agrupamento dos itens, fila de lotes, guarda de resultados e interpretação da resposta da calculadora
oficial. Não precisam das calculadoras no ar e rodam no GitHub Actions a cada push nas duas branches.

**No servidor Linux:** `bash /opt/hub-fiscal/scripts/linux/hub.sh testar` (classificação de cClassTrib contra a
calculadora oficial que está no ar, numa cópia isolada do hub).

`testar-hub.bat` sobe uma cópia isolada do hub (porta 8091, bancos temporários, API de CNPJ simulada) e roda 225 testes:

| Suíte | Testes | O que confere |
|---|---|---|
| Referência | 36 | respostas comparadas com `testes\referencia` (lotes JSON e Excel, consultas, proxy) |
| Funcional | 59 | arquivos inválidos, limites, botão "Atualizar agora", regressões do QA |
| Empresas | 44 | empresas encontradas na importação, cadastro, edição, exclusão, permissões, PDF |
| Simulador | 36 | importação no simulador, consulta de CNPJ (principal/reserva/erros), auditor 0,9% × 11,3%, vendas por NCM |
| Segurança | 50 | permissões, CSRF, sessões, cabeçalhos, logo, força bruta (roda por último) |

Mudança intencional nas respostas: conferir as diferenças e aprovar com
`powershell -ExecutionPolicy Bypass -File testes\executar-testes.ps1 -GravarReferencia`.
