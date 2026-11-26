<#
  Testes automatizados do Hub Fiscal.

  Sobe uma cópia isolada do hub (porta 8091, banco de usuários temporário — os usuários reais não são
  tocados), cria usuários de teste e roda três suítes contra ela:
    - Referência: respostas comparadas com testes\referencia (lotes, Excel, consultas, proxy, erros)
    - Funcional:  entradas inválidas, arquivos ruins e limites
    - Segurança:  permissões, CSRF, sessões, isolamento, cabeçalhos e força bruta
    - Empresas:   cadastro automático na importação (sem duplicar), edição das particularidades, relatório PDF
    - Simulador:  importação de XML/SPED no simulador, consulta de CNPJ (API simulada) e auditor com 0,9% × 11,3%
  No fim desliga a cópia e apaga os arquivos temporários. O hub em uso (porta 8090) continua no ar.

  Pré-requisitos: as calculadoras da Receita no ar (iniciar-hub.bat) e o hub compilado
  (hub-fiscal\target\hub-fiscal.jar). Usa as calculadoras de verdade (somente leitura e cálculos).

  Uso:
    powershell -ExecutionPolicy Bypass -File testes\executar-testes.ps1
    powershell -ExecutionPolicy Bypass -File testes\executar-testes.ps1 -GravarReferencia   # aprova as respostas atuais

  Código de saída: 0 = tudo certo; 1 = alguma falha.
#>
param(
    [int]$Porta = 8091,
    [switch]$GravarReferencia
)
$ErrorActionPreference = 'Stop'
$Raiz = Split-Path -Parent $PSScriptRoot
$Base = "http://localhost:$Porta"
$Dados = Join-Path $PSScriptRoot 'dados'

# credenciais só desta cópia de teste (banco temporário, apagado ao final)
$UsuarioAdmin = 'admin.teste';    $SenhaAdmin = 'Admin12345'
$UsuarioLimitado = 'limitado';    $SenhaLimitado = 'Limitado123'
$UsuarioCompleto = 'completo';    $SenhaCompleto = 'Completo123'

function Write-Etapa([string]$t) { Write-Host ''; Write-Host "==> $t" -ForegroundColor Cyan }
function Stop-Teste([string]$motivo) { Write-Host "    !!  $motivo" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- pré-requisitos
Write-Etapa 'Conferindo pré-requisitos'
$jdk = Get-ChildItem (Join-Path $Raiz 'tools') -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jdk) { Stop-Teste 'Java 25 não encontrado em tools\ (rode iniciar-hub.bat uma vez).' }
$jar = Join-Path $Raiz 'hub-fiscal\target\hub-fiscal.jar'
if (-not (Test-Path $jar)) { Stop-Teste 'hub-fiscal\target\hub-fiscal.jar não existe: compile o hub (ver README).' }
foreach ($u in 'http://127.0.0.1:8080/api/calculadora/dados-abertos/versao', 'http://127.0.0.1:8082/api/versao') {
    try { Invoke-WebRequest $u -UseBasicParsing -TimeoutSec 5 | Out-Null }
    catch { Stop-Teste "Calculadora fora do ar ($u): inicie com iniciar-hub.bat." }
}
if (Get-NetTCPConnection -State Listen -LocalPort $Porta -ErrorAction SilentlyContinue) { Stop-Teste "A porta $Porta já está em uso." }
Write-Host "    OK  Java, jar ($((Get-Item $jar).LastWriteTime)), calculadoras e porta $Porta"

# ---------------------------------------------------------------- cópia isolada do hub
Write-Etapa "Subindo uma cópia do hub para teste (porta $Porta)"
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('hub-testes-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory $tmp | Out-Null
Copy-Item $jar (Join-Path $tmp 'hub-fiscal-teste.jar')   # cópia: não trava o jar usado pelo hub em produção
# API pública de CNPJ simulada (mock): os testes nunca acessam o serviço real
$PortaMockCnpj = $Porta + 2
$mockCnpj = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
    ("`"$PSScriptRoot\mock-cnpj.ps1`""), '-Porta', $PortaMockCnpj, '-Dados', "`"$Dados`""
$proc = Start-Process -FilePath (Join-Path $jdk.FullName 'bin\java.exe') -PassThru -WindowStyle Hidden `
    -WorkingDirectory (Join-Path $Raiz 'hub-fiscal') `
    -ArgumentList '-jar', (Join-Path $tmp 'hub-fiscal-teste.jar'), "--server.port=$Porta", "--hub.banco-usuarios=$tmp\usuarios.db", "--hub.arquivo-logo=$tmp\logo.png", "--hub.banco-empresas=$tmp\empresas.db", `
        "--hub.arquivo-ultima-atualizacao=$tmp\ultima-atualizacao.json", "--hub.atualizacao-manual=pedido", `
        "--hub.consulta-cnpj-url=http://127.0.0.1:$PortaMockCnpj/cnpj/{cnpj},http://127.0.0.1:$PortaMockCnpj/reserva/{cnpj}" `
    -RedirectStandardOutput (Join-Path $tmp 'hub.log') -RedirectStandardError (Join-Path $tmp 'hub.err')

$codigoSaida = 1
try {
    $noAr = $false
    for ($i = 0; $i -lt 60 -and -not $noAr; $i++) {
        try { Invoke-WebRequest "$Base/auth/estado" -UseBasicParsing -TimeoutSec 2 | Out-Null; $noAr = $true } catch { Start-Sleep 1 }
    }
    if (-not $noAr) { Get-Content (Join-Path $tmp 'hub.log') -Tail 30; Stop-Teste 'A cópia de teste não subiu (log acima).' }

    . (Join-Path $PSScriptRoot 'lib.ps1')

    # usuários de teste: administrador (primeiro acesso), um com só uma ferramenta e um com todas
    $s = New-SessaoHub
    $r = Invoke-Hub $s 'POST' '/auth/primeiro-acesso' (@{ usuario = $UsuarioAdmin; nome = 'Admin Teste'; senha = $SenhaAdmin } | ConvertTo-Json -Compress)
    if (-not $r.IsSuccessStatusCode) { Stop-Teste "primeiro acesso falhou: $(Get-Texto $r)" }
    $adm = Enter-Hub $UsuarioAdmin $SenhaAdmin
    foreach ($u in @(@{ login = $UsuarioLimitado; senha = $SenhaLimitado; permissoes = @('tabela-cclasstrib') },
            @{ login = $UsuarioCompleto; senha = $SenhaCompleto; permissoes = $null })) {
        $corpo = @{ login = $u.login; nome = "Teste $($u.login)"; perfil = 'USUARIO'; permissoes = $u.permissoes } | ConvertTo-Json -Compress
        $prov = (Get-Texto (Invoke-Hub $adm 'POST' '/admin/usuarios' $corpo) | ConvertFrom-Json).senhaProvisoria
        $su = Enter-Hub $u.login $prov
        Invoke-Hub $su 'POST' '/auth/senha' (@{ senhaAtual = $prov; novaSenha = $u.senha } | ConvertTo-Json -Compress) | Out-Null
    }
    Write-Host "    OK  no ar, com usuários de teste"

    # cada suíte roda até o fim; um erro inesperado vira falha registrada e as demais suítes continuam
    # Segurança por último: o teste de força bruta bloqueia novos logins deste computador por 15 minutos
    foreach ($suite in @(@('Referência', 'suite-referencia.ps1'), @('Funcional', 'suite-funcional.ps1'), @('Empresas', 'suite-empresas.ps1'), @('Simulador', 'suite-simulador.ps1'), @('Segurança', 'suite-seguranca.ps1'))) {
        Write-Etapa "Suíte: $($suite[0])"
        try {
            if ($suite[1] -eq 'suite-referencia.ps1') { . (Join-Path $PSScriptRoot $suite[1]) -GravarReferencia:$GravarReferencia }
            else { . (Join-Path $PSScriptRoot $suite[1]) }
        } catch {
            Add-Resultado $suite[0] 'suíte interrompida por erro inesperado' $false "$($_.Exception.Message) ($($_.InvocationInfo.ScriptName):$($_.InvocationInfo.ScriptLineNumber))"
        }
    }

    # ---------------------------------------------------------------- resumo
    Write-Etapa 'Resumo'
    foreach ($g in $script:Resultados | Group-Object Suite) {
        $falhas = @($g.Group | Where-Object { -not $_.Ok }).Count
        Write-Host ("    {0,-12} {1,3} testes, {2} falha(s)" -f $g.Name, $g.Count, $falhas) -ForegroundColor $(if ($falhas) { 'Red' } else { 'Green' })
    }
    $falhas = @($script:Resultados | Where-Object { -not $_.Ok })
    if ($falhas) {
        Write-Host ''; Write-Host 'Falhas:' -ForegroundColor Red
        $falhas | ForEach-Object { Write-Host "  [$($_.Suite)] $($_.Nome)`n      $($_.Detalhe)" -ForegroundColor Red }
    } else {
        Write-Host ''; Write-Host "Todos os $($script:Resultados.Count) testes passaram." -ForegroundColor Green
        $codigoSaida = 0
    }
} finally {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $mockCnpj.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
exit $codigoSaida
