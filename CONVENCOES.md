# Convenções do Hub Fiscal

Guia curto para manter o padrão do código. Vale para a `main` e para a branch `ubuntu-server`.

## Regras que não se quebram

- **Nunca alterar** `calculadora-rtc-regime-regular/` nem `calculadora-rtc-simples-nacional/`: são cópias dos repositórios da Receita, atualizadas por `git pull`. Toda funcionalidade nova vai em `hub-fiscal/`.
- **Cálculo é o da calculadora oficial.** O hub monta a entrada e apresenta a resposta. A única exceção é o cenário de CBS (`cenario/`), sempre sinalizado na tela e nunca usado em XML ou auditoria. Mudança em regra de cálculo precisa ser sinalizada e justificada antes.
- Todo cálculo feito no hub segue a calculadora: arredondamento **HALF_EVEN** (bancário), valores com 2 casas, alíquotas com 2 a 4.

## Organização

| O quê | Onde |
|---|---|
| Endereço de endpoint da calculadora usado pelo servidor | constante em `infra/EndpointsReceita` |
| URL, caminho de arquivo ou parâmetro configurável | `application.yml` (prefixo `hub`) + `config/HubProperties` |
| Funcionalidade do servidor | um pacote por assunto (`catalogo`, `classificacao`, `auditoria`, `cenario`, `marca`, `seguranca`…) |
| Tela | um arquivo em `static/js/modulos/` com `Hub.registrar({ rota, titulo, grupo, descricao, render })`, incluído no `index.html` |
| Componente reutilizável de tela | `static/js/componentes.js` (`Hub.comp`) |
| Tradução de nomes técnicos da API | `static/js/rotulos.js` (`Hub.rotulos`) |
| Ícone de tela | `static/js/icones.js` (mapa `ROTAS`) |

## Nomes e formatação

- Português em nomes de classes, métodos, variáveis, mensagens e comentários (`calcularBase`, `LinhaAuditoria`).
- Java: 4 espaços; JavaScript, CSS e HTML: 2 espaços; aspas simples em JavaScript; `'use strict'` em todo arquivo JS.
- Comentário explica **por quê**, não o quê. Cabeçalho de uma linha no topo de cada arquivo dizendo o que ele faz.

## Telas

- Sem `<script>` embutido nem `onclick=`: a política de segurança (CSP) bloqueia. Use arquivos `.js` e atributos `data-*`.
- Arquivo usado antes do login (tela de login) entra em `PUBLICOS` no `SegurancaConfig`.
- Texto vindo de fora (resposta da calculadora, arquivo enviado, dado do usuário) sempre passa por `Hub.esc` antes de ir para o HTML.
- Nada de nome cru da API na tela (`tpRegimeIBSCBS`, `pCBSSN`): use linguagem de contador e `Hub.rotulos`. CST, cClassTrib, NCM, NBS, CRT e CFOP podem aparecer, de preferência com o nome por extenso.
- Campo de formulário: `comp.campo(rotulo, html)`; o rótulo é ligado ao campo automaticamente. Campo sem rótulo visível (ex.: dentro de tabela) leva `aria-label`.
- Ao alterar arquivos em `static/`, aumente o `?v=` em `index.html` e `login.html`.

## Segurança

- Operação nova que deve ser restrita: regra em `Ferramentas` (o proxy e a checagem de permissão usam `Ferramentas.caminhoCanonico()`). Área de administração: caminho em `/admin/**`.
- Arquivo enviado pelo usuário: validar pelo conteúdo, não pela extensão; nunca servir de volta sem reprocessar (ver `marca/MarcaController`).
- Consultas ao SQLite sempre com parâmetros (`?`), nunca concatenando texto.
- Serviço externo (ex.: consulta de CNPJ): chamado só pelo servidor, com URL configurável no `application.yml`, tempo limite,
  mensagens claras para falha/limite/inexistente e nunca bloqueando o preenchimento manual. Nos testes, use um mock local
  (ver `testes/mock-cnpj.ps1`): a suíte nunca acessa a internet.
- O hub não ganha privilégios: ações que exigem administrador do servidor (parar/religar serviços) viram um pedido em
  arquivo atendido pelos scripts (ver `sistema/AtualizacaoManual`).

## Robustez

- Um arquivo ruim não derruba o lote: dado malformado (data, número de item, município) vira status/aviso no próprio item.
  Nada de `substring`/`parseInt` direto em dado vindo de arquivo; valide antes (ex.: `Ufs.doMunicipio`, `LocalDate.parse` em `try`).
- Arquivos grandes (SPED de centenas de MB): percorra o texto sem montar listas com todas as linhas e evite objetos por linha
  que não serão usados (ver `SpedParser` e `ItemFiscal.semGrupos()`). Meça com um heap limitado (`-Xmx1200m`) antes de mudar.

## Antes de enviar uma mudança

1. `parar-hub.bat` → compilar → `iniciar-hub.bat`.
2. `testar-hub.bat`: todos os testes passando. Resposta mudou de propósito? Conferir a diferença e aprovar com `-GravarReferencia`.
3. Funcionalidade nova ganha teste em `testes/` (`.ps1` gravado em UTF-8 **com BOM**).
4. Commit na `main`; depois `git merge main` na `ubuntu-server`.
