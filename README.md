# Hub Fiscal – Reforma Tributária do Consumo

Aplicação web interna do departamento fiscal, construída sobre as **calculadoras oficiais da Receita Federal/Serpro** (CBS, IBS e Imposto Seletivo – LC 214/2025). Atende clientes do **Regime Regular** e do **Simples Nacional**.

## Como usar (dia a dia)

| Ação | Como fazer |
|---|---|
| Ligar tudo | Dê dois cliques em `iniciar-hub.bat` |
| Desligar | `parar-hub.bat` |
| Atualizar as calculadoras da Receita | `atualizar-calculadoras.bat` (baixa a versão nova, recria o banco e recompila) |
| Acessar | `http://localhost:8090` neste computador, ou `http://IP-DESTE-PC:8090` nos demais (o endereço aparece ao iniciar) |

Para que outros computadores acessem, libere a **porta 8090 (TCP, entrada)** no Firewall do Windows desta máquina. As portas 8080 e 8082 (calculadoras) não precisam ser liberadas: o hub repassa as chamadas.

## Acesso (login e senha)

O hub exige login. Na **primeira vez**, abra `http://localhost:8090` **no próprio servidor** e crie a conta de administrador
(por segurança essa tela não funciona a partir de outros computadores). Depois, em **Sistema → Usuários** (ou no menu do
usuário, no canto superior direito), o administrador cadastra a equipe:

- cada usuário novo recebe uma **senha provisória**, mostrada uma única vez, que ele troca no primeiro acesso;
- perfis: **Usuário** (todas as ferramentas) e **Administrador** (também gerencia usuários);
- **Desativar** bloqueia o acesso na hora (inclusive de quem está logado); **Redefinir senha** gera nova provisória;
- senhas com no mínimo 8 caracteres, letras e números, guardadas com BCrypt; 5 erros seguidos bloqueiam o usuário por 15 minutos;
  a sessão expira após 8 horas sem uso.

Os usuários ficam em `dados\hub-usuarios.db` – **inclua esse arquivo no backup** do servidor. Se perder o acesso de todos
os administradores, pare o hub, renomeie esse arquivo e inicie de novo: o primeiro acesso volta a ser oferecido no servidor.

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

**Processamento em lote (com planilha Excel)**
- Classificar cadastro: SPED (EFD ICMS/IPI e EFD Contribuições), XML de NF-e/NFC-e/NFS-e ou ZIP. Sugere o cClassTrib, confere o usado nas notas e marca Divergente/Revisar/Sugerido/Conferido
- Auditar XML: recalcula na calculadora oficial o IBS/CBS destacado e aponta divergências de base, alíquota, valor e classificação

Arquivos fictícios para treino estão em `hub-fiscal/exemplos/`.

## Estrutura

```
Calculadora RT/
├─ calculadora-rtc-regime-regular/    repositório oficial da Receita (não alterar) – porta 8080
├─ calculadora-rtc-simples-nacional/  repositório oficial da Receita (não alterar) – porta 8082
├─ hub-fiscal/                        aplicação do escritório (Spring Boot + telas HTML/JS) – porta 8090
│  ├─ src/main/java/...               servidor: proxy, catálogo, leitura de SPED/XML, lotes, Excel
│  ├─ src/main/resources/static/      telas (sem etapa de build)
│  └─ exemplos/                       arquivos fictícios para teste
├─ scripts/hub.ps1                    iniciar / parar / status / atualizar
├─ tools/jdk-25*                      Java 25 portátil (exigido pelas calculadoras)
└─ logs/                              logs dos três serviços
```

Os repositórios da Receita são usados **sem modificação**, para que as atualizações sejam um simples `git pull`. O hub lê o banco oficial (SQLite) apenas em modo leitura, para as consultas que as APIs não oferecem prontas (ex.: "quais cClassTrib valem para este NCM?"), reproduzindo a mesma regra de vínculo da calculadora oficial.

## Limites conhecidos

- As sugestões de classificação consideram NCM/NBS, documento e CFOP; requisitos externos (destinatário, finalidade, regimes especiais) exigem análise do responsável.
- A partir de 2027 a base oficial não traz alíquotas de referência; os simuladores usam alíquotas informadas e marcam o resultado como simulado. Pelo mesmo motivo, o pedágio só calcula datas de 2026.
- A base oficial não vincula combustíveis (monofásico 620xxx) a NCM; o hub emite um alerta para esses NCMs.
- O SPED não traz NBS: serviços vindos só do SPED aparecem como "Sem NCM/NBS".
- Na base oficial, os números de anexo dos vínculos de NBS são inconsistentes; o hub mostra o vínculo NBS → cClassTrib, mas não o texto do anexo.
- Não há login: use apenas na rede interna.

## Desenvolvimento

```powershell
$env:JAVA_HOME = (Resolve-Path tools\jdk-25*).Path
cd hub-fiscal
.\mvnw.cmd clean package -DskipTests
```
