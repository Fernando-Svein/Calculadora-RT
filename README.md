# Hub Fiscal

Aplicação web interna do departamento fiscal, construída sobre as **calculadoras oficiais da Receita Federal/Serpro** (CBS, IBS e Imposto Seletivo – LC 214/2025). Atende clientes do **Regime Regular** e do **Simples Nacional**.

## Como usar (dia a dia)

| Ação | Como fazer |
|---|---|
| Ligar tudo | Dê dois cliques em `iniciar-hub.bat` |
| Desligar | `parar-hub.bat` |
| Atualizar as calculadoras da Receita | `atualizar-calculadoras.bat` (baixa a versão nova, recria o banco e recompila) |
| Conferir se está tudo funcionando | `testar-hub.bat` (testes automatizados; o hub em uso continua no ar) |
| Acessar | `http://localhost:8090` neste computador, ou `http://IP-DESTE-PC:8090` nos demais (o endereço aparece ao iniciar) |

Para que outros computadores acessem, libere a **porta 8090 (TCP, entrada)** no Firewall do Windows desta máquina. As calculadoras (portas 8080 e 8082) escutam só em `127.0.0.1`: os demais computadores usam apenas o hub, que exige login e repassa as chamadas.

## Acesso (login e senha)

O hub exige login. Na **primeira vez**, abra `http://localhost:8090` **no próprio servidor** e crie a conta de administrador
(por segurança essa tela não funciona a partir de outros computadores). Depois, em **Sistema → Usuários** (ou no menu do
usuário, no canto superior direito), o administrador cadastra a equipe:

- cada usuário novo recebe uma **senha provisória**, mostrada uma única vez, que ele troca no primeiro acesso;
- perfis: **Usuário** (usa as ferramentas que o administrador liberar para ele) e **Administrador** (todas as ferramentas e gestão de usuários);
- **Desativar** bloqueia o acesso na hora (inclusive de quem está logado); **Redefinir senha** gera nova provisória;
- senhas com no mínimo 8 caracteres, letras e números, guardadas com BCrypt; 5 erros seguidos bloqueiam o usuário por 15 minutos,
  e 20 erros do mesmo computador bloqueiam esse computador por 15 minutos; trocar a senha encerra as outras sessões do usuário;
  a sessão expira após 8 horas sem uso.

Os usuários ficam em `dados\hub-usuarios.db` – **inclua esse arquivo no backup** do servidor. Se perder o acesso de todos
os administradores, pare o hub, renomeie esse arquivo e inicie de novo: o primeiro acesso volta a ser oferecido no servidor.

## Logo do escritório

O administrador cadastra a logo em **Sistema → Logo do escritório** (PNG ou JPEG, até 2 MB; de preferência PNG com fundo
transparente). Ela passa a aparecer para todos no cabeçalho do menu, na tela de login e no ícone da aba do navegador;
sem logo, fica o ícone padrão "HF". No modo escuro a logo ganha uma placa branca para continuar legível.

Por segurança o hub não guarda o arquivo enviado: confere o formato pelos bytes (SVG é recusado, pois pode conter scripts),
abre a imagem e regrava como um PNG novo, reduzido para no máximo 640 × 320 px. O arquivo fica em `dados\logo.png`
(configurável em `hub.arquivo-logo`) – inclua no backup junto com os usuários.

## Empresas, simuladores e relatórios

- **Cadastros → Empresas**: empresas atendidas e suas particularidades (regime, Lucro Real/Presumido, município, alíquotas
  do DAS por anexo, fator r, sublimite, atividade, documento e cClassTrib usuais). Ao importar um SPED ou XML num
  processamento em lote ou no simulador, o hub pergunta se as empresas dos arquivos (contribuinte do SPED ou emitente do XML) que ainda não estão no cadastro devem ser adicionadas; cada uma escolhida entra com a consulta completa do CNPJ (ou, se a consulta falhar, com
  CNPJ e razão social, marcada como "A conferir"; o mesmo CNPJ nunca é cadastrado duas vezes. Incluir e editar exige a
  ferramenta "Empresas". Ficam em `dados\hub-empresas.db` (`hub.banco-empresas`) – inclua no backup.
- **Empresa da simulação**: todos os simuladores (Regime Regular, Simples, Comparador, Pedágio e Bases) têm o seletor no
  topo; a empresa escolhida preenche os campos (borda violeta) e aparece no resultado. Fica lembrada no navegador.
- **Relatórios**: depois de calcular, "Relatório PDF" e "Relatório Excel" (empresa, resumo, tabela e observações). Os
  lotes continuam com a planilha própria.
- **Importar XML/SPED no simulador**: o Simulador Regime Regular lê um ou mais XMLs (ou um SPED) e preenche data, local e
  itens (NCM/NBS, CST, cClassTrib, quantidade e base). Item sem cClassTrib recebe a classificação sugerida pelo NCM/NBS,
  marcada em amarelo como "sugerido – não veio do documento".
- **Consultar CNPJ**: no cadastro da empresa, busca na base pública (BrasilAPI, `hub.consulta-cnpj-url`) razão social,
  fantasia, situação cadastral, CNAEs, endereço, Simples/MEI, regime declarado e quadro societário. Falhas (CNPJ inexistente,
  limite de consultas, serviço fora) aparecem com mensagem e o cadastro manual continua.
- **Auditor com cenário TCU**: a CBS de cada item aparece calculada com a alíquota oficial (0,9% em 2026) e com a do cenário
  (11,3%), lado a lado, na tela e no Excel; o status OK/Divergente segue a alíquota oficial.
- **Base das calculadoras**: o topo mostra a data da última atualização da base e a da próxima verificação diária
  (`ativar-atualizacao-automatica.bat` no Windows; timer das 03:00 no Linux). Sem agendamento, mostra "não agendada".

## Instalação no servidor (VM Windows)

1. Copie a pasta inteira do projeto para a VM (ex.: `C:\HubFiscal`) — inclui `tools\` com o Java 25 portátil.
2. Instale o **Git para Windows** (https://git-scm.com) na VM. A VM precisa de acesso ao `github.com` e ao Maven Central (`repo.maven.apache.org`).
3. Rode `iniciar-hub.bat` uma vez para conferir que tudo sobe.
4. Clique com o botão direito em `ativar-atualizacao-automatica.bat` → **Executar como administrador**. Isso cria duas tarefas no Agendador do Windows (conta SYSTEM, rodam sem ninguém logado):
   - **Hub Fiscal - Iniciar** – liga as calculadoras e o hub quando a VM inicia;
   - **Hub Fiscal - Atualizacao** – todo dia às 03:00 consulta o GitHub da Receita. Sem novidade, não mexe em nada. Com versão nova: para os serviços, atualiza, recria o banco, recompila e religa (alguns minutos). Se algo falhar, volta para a versão anterior e religa.
5. Libere a porta 8090 no firewall da VM.

O resultado de cada verificação aparece em **Sistema → Situação do sistema** e em `logs\atualizacao.log`. Para mudar o horário: `powershell -ExecutionPolicy Bypass -File scripts\hub.ps1 agendar -Hora 22:30`. Para remover as tarefas: `desativar-atualizacao-automatica.bat` (como administrador).

## O que o hub oferece

**Consultas e tabelas**
- Classificar NCM/NBS: cClassTrib possíveis pelos anexos da LC 214/2025, Imposto Seletivo, fundamentação e alerta de combustíveis
- Tabela cClassTrib/CST, Imposto Seletivo, filtro por fornecedor/adquirente e fundamentação legal
- Alíquotas de referência, atividades do Simples (cAtivSN), ferramentas da NFS-e, catálogos de erros e demais dados abertos

**Calculadoras**
- Simulador Regime Regular: vários itens, base direta ou pelos componentes da NF-e, IS, compra governamental, geração e validação do XML do grupo IBS/CBS
- Bases de cálculo (unificada NF-e/NFC-e, mercadorias, IS, NFS-e) e pedágio
- Simulador Simples Nacional (todos os documentos, anexos, fator r, ISS)
- Comparador: IBS/CBS dentro do Simples × pelo regime regular

**Cenário de alíquota da CBS (seletor "CBS" no topo)**
- *Oficial*: o resultado da calculadora da Receita (em 2026, a alíquota de teste de 0,9%).
- *Cenário* (ex.: "Cenário TCU – 11,3%"): no Simulador Regime Regular e no Pedágio, a operação é calculada pela calculadora
  oficial e o hub recalcula só a CBS (alíquota do cenário × (1 − redução) × base), porque a calculadora não aceita outra
  alíquota antes de 2027 (erro REG-036). De 2027 em diante a alíquota vai preenchida no campo "CBS %" e quem calcula é a
  calculadora. O resultado mostra um aviso de simulação.
- Não se aplica a: XML gerado (sempre com a alíquota legal), auditoria de notas (compara com a alíquota em vigor) e Simples
  Nacional (CBS dentro do DAS).
- A alíquota e o nome ficam em `hub.cenario-cbs` no `application.yml`; apagando a linha `aliquota`, o seletor some.

**Processamento em lote (com planilha Excel)**
- Classificar cadastro: SPED (EFD ICMS/IPI e EFD Contribuições), XML de NF-e/NFC-e/NFS-e ou ZIP. Sugere o cClassTrib, confere o usado nas notas e marca Divergente/Revisar/Sugerido/Conferido
- Auditar XML: recalcula na calculadora oficial o IBS/CBS destacado e aponta divergências de base, alíquota, valor e classificação

## Estrutura

```
Calculadora RT/
├─ calculadora-rtc-regime-regular/    repositório oficial da Receita (não alterar) – 127.0.0.1:8080
├─ calculadora-rtc-simples-nacional/  repositório oficial da Receita (não alterar) – 127.0.0.1:8082
├─ hub-fiscal/                        aplicação do escritório (Spring Boot 4 + telas HTML/JS) – porta 8090
│  └─ src/main/
│     ├─ resources/application.yml    configuração: URLs das calculadoras, caminhos, paralelismo
│     ├─ resources/static/            telas (HTML/CSS/JS sem etapa de build; um arquivo por tela em js/modulos)
│     └─ java/br/com/escritorio/hubfiscal/
│        ├─ infra/          integração com as calculadoras: ApiReceitaClient (único cliente HTTP),
│        │                  EndpointsReceita (caminhos usados pelo servidor), J (leitura do JSON), Paralelo, Valores
│        ├─ proxy/          repasse /proxy/rg/** e /proxy/sn/** das telas para as calculadoras
│        ├─ catalogo/       consultas: NCM/NBS → cClassTrib (BaseOficial lê o SQLite oficial; CatalogoService combina com a API)
│        ├─ importacao/     leitura de SPED, XML (NF-e, NFC-e, NFS-e) e ZIP
│        ├─ classificacao/  regras da classificação em lote e planilha
│        ├─ auditoria/      recálculo dos XMLs na calculadora e planilha
│        ├─ lote/           endpoints dos lotes; excel/  exportação genérica e Planilha (Apache POI)
│        ├─ seguranca/      login, usuários, permissões por ferramenta; sistema/  situação do sistema
│        └─ config/         HubProperties, tratamento de erros
├─ testes/                            testes automatizados (ver "Testes")
├─ docs-api/                          contrato OpenAPI das calculadoras (referência)
├─ scripts/hub.ps1                    iniciar / parar / status / atualizar / agendar
├─ tools/jdk-25*                      Java 25 portátil (exigido pelas calculadoras)
└─ logs/                              logs dos três serviços
```

Fluxo: tela → hub (8090) → `ApiReceitaClient` → calculadora oficial. Os lotes leem os arquivos (`importacao`), classificam ou auditam (`classificacao`/`auditoria`, que chamam a calculadora com no máximo `hub.paralelismo` chamadas simultâneas) e devolvem JSON para a tela e a planilha Excel.

Os repositórios da Receita são usados **sem modificação**, para que as atualizações sejam um simples `git pull`. O hub lê o banco oficial (SQLite) apenas em modo leitura, para as consultas que as APIs não oferecem prontas (ex.: "quais cClassTrib valem para este NCM?"), reproduzindo a mesma regra de vínculo da calculadora oficial.

## Limites conhecidos

- As sugestões de classificação consideram NCM/NBS, documento e CFOP; requisitos externos (destinatário, finalidade, regimes especiais) exigem análise do responsável.
- A partir de 2027 a base oficial não traz alíquotas de referência; os simuladores usam alíquotas informadas e marcam o resultado como simulado. Pelo mesmo motivo, o pedágio só calcula datas de 2026.
- A base oficial não vincula combustíveis (monofásico 620xxx) a NCM; o hub emite um alerta para esses NCMs.
- O SPED não traz NBS: serviços vindos só do SPED aparecem como "Sem NCM/NBS".
- Na base oficial, os números de anexo dos vínculos de NBS são inconsistentes; o hub mostra o vínculo NBS → cClassTrib, mas não o texto do anexo.
- O hub usa HTTP: use apenas na rede interna.

## Desenvolvimento

```powershell
# compilar o hub (gera hub-fiscal\target\hub-fiscal.jar)
$env:JAVA_HOME = (Resolve-Path tools\jdk-25*).Path
cd hub-fiscal
.\mvnw.cmd clean package

# aplicar: reinicia os três serviços com o jar novo
cd ..
powershell -ExecutionPolicy Bypass -File scripts\hub.ps1 reiniciar
powershell -ExecutionPolicy Bypass -File scripts\hub.ps1 status
```

## Testes

`testar-hub.bat` (ou `powershell -ExecutionPolicy Bypass -File testes\executar-testes.ps1`) sobe uma cópia isolada do hub
na porta 8091, com usuários de teste num banco temporário, roda as suítes abaixo e desliga a cópia. Os usuários reais e
o hub em uso não são tocados. Exige as calculadoras no ar e o hub compilado. Termina com código 0 quando tudo passa.

| Suíte | O que confere |
|---|---|
| Referência | 36 respostas comparadas com `testes\referencia`: lotes (JSON e Excel célula a célula), consultas, repasse do proxy às calculadoras (incluindo POSTs de cálculo) e erros |
| Funcional | arquivos inválidos (XML quebrado, XXE, vazio, binário), datas e parâmetros errados, limites |
| Empresas | cadastro automático na importação (sem duplicar, CNPJ alfanumérico, CPF/CNPJ inválido ignorados), edição das particularidades e validações, permissões, relatório PDF e datas da base no status |
| Simulador | importação de XML/SPED no simulador (cClassTrib do documento × sugerido), consulta de CNPJ com API simulada (`testes\mock-cnpj.ps1`: cadastro, QSA, cache, 404, 429, 502, CNPJ inválido) e auditor com a CBS a 0,9% e a 11,3% lado a lado |
| Segurança | permissões por ferramenta (inclusive variações do endereço), CSRF, sessões, isolamento de resultados, cabeçalhos, envio da logo (formatos, SVG, tamanho, permissão), força bruta |

São 207 testes (36 + 59 + 33 + 29 + 50); a suíte de Segurança roda por último porque o teste de força bruta bloqueia novos logins. Arquivos de entrada (fictícios) ficam em `testes\dados`; `hub-fiscal\exemplos` traz notas
e um SPED fictícios para experimentar à mão as telas de lote. Quando uma mudança **intencional** alterar respostas, ou quando
a Receita publicar uma base nova, confira as diferenças apontadas e, se estiverem certas, aprove as novas respostas com
`testes\executar-testes.ps1 -GravarReferencia`.

Fluxo para mudar o código: `parar-hub.bat` → compilar (ver "Desenvolvimento") → `iniciar-hub.bat` → `testar-hub.bat`.
O hub precisa estar parado para compilar (o jar fica em uso), e os testes precisam das calculadoras no ar.

Convenções de código: veja [CONVENCOES.md](CONVENCOES.md) (guia curto para manter o padrão).
