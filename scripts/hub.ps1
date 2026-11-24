<#
  Hub Fiscal - gerenciamento dos serviços (Windows PowerShell 5.1+)

  Ações:
    iniciar     Inicia as calculadoras oficiais e o hub (compila o que faltar)
    parar       Para os três serviços
    reiniciar   Para e inicia novamente
    status      Mostra o que está em execução
    atualizar   Baixa a versão nova das calculadoras da Receita (git pull), recria o
                banco oficial, recompila tudo e reinicia
    verificar   Consulta o GitHub da Receita; só atualiza se houver versão nova.
                Se a atualização falhar, volta para a versão anterior e religa tudo.
                Grava o resultado em logs\ultima-atualizacao.json (mostrado no hub).
    agendar     (servidor, como administrador) Cria as tarefas no Agendador do Windows:
                  - "Hub Fiscal - Iniciar": liga os serviços quando o servidor inicia
                  - "Hub Fiscal - Atualizacao": roda "verificar" todo dia no horário -Hora
    desagendar  Remove as duas tarefas

  Uso: powershell -ExecutionPolicy Bypass -File scripts\hub.ps1 iniciar
       powershell -ExecutionPolicy Bypass -File scripts\hub.ps1 agendar -Hora 03:00
#>
param(
    [ValidateSet('iniciar', 'parar', 'reiniciar', 'status', 'atualizar', 'verificar', 'agendar', 'desagendar')]
    [string]$Acao = 'iniciar',
    [string]$Hora = '03:00'
)

$ErrorActionPreference = 'Stop'
$Raiz = Split-Path -Parent $PSScriptRoot
$Logs = Join-Path $Raiz 'logs'
if (-not (Test-Path $Logs)) { New-Item -ItemType Directory -Path $Logs | Out-Null }

function Write-Etapa([string]$Texto) { Write-Host ""; Write-Host "==> $Texto" -ForegroundColor Cyan }
function Write-Ok([string]$Texto) { Write-Host "    OK  $Texto" -ForegroundColor Green }
function Write-Falha([string]$Texto) { Write-Host "    !!  $Texto" -ForegroundColor Red }

# ---------------------------------------------------------------- Java 25 (portátil em tools\)
$Tools = Join-Path $Raiz 'tools'
$Jdk = Get-ChildItem $Tools -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $Jdk -and $Acao -notin @('parar', 'status', 'desagendar')) {
    # primeira execução (ex.: clone novo do repositório): baixa o Temurin 25 portátil da Adoptium
    Write-Etapa 'Java 25 não encontrado: baixando o Eclipse Temurin 25 (portátil, ~200 MB)'
    try {
        if (-not (Test-Path $Tools)) { New-Item -ItemType Directory -Path $Tools | Out-Null }
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $zip = Join-Path $Tools 'jdk25.zip'
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile $zip
        Expand-Archive -Path $zip -DestinationPath $Tools -Force
        Remove-Item $zip -Force
        $Jdk = Get-ChildItem $Tools -Directory -Filter 'jdk-25*' | Select-Object -First 1
        Write-Ok "Java instalado em $($Jdk.FullName)"
    } catch {
        Write-Falha "Não foi possível baixar o Java: $($_.Exception.Message)"
    }
}
if (-not $Jdk -and $Acao -notin @('parar', 'status', 'desagendar')) {
    Write-Falha "JDK 25 não encontrado em $Tools. Baixe em https://adoptium.net (Temurin 25, ZIP) e extraia em tools\."
    exit 1
}
if (-not $Jdk) { $Jdk = [pscustomobject]@{ FullName = '' } }
$env:JAVA_HOME = $Jdk.FullName
$env:PATH = "$($Jdk.FullName)\bin;$env:PATH"
$Java = Join-Path $Jdk.FullName 'bin\java.exe'

$Servicos = @(
    @{ Nome = 'Calculadora Regime Geral'; Pasta = 'calculadora-rtc-regime-regular'; Jar = 'api-regime-geral.jar';
        Args = @('-Dspring.profiles.active=offline'); Url = 'http://localhost:8080/api/calculadora/dados-abertos/versao'; Log = 'regime-geral' },
    @{ Nome = 'Calculadora Simples Nacional'; Pasta = 'calculadora-rtc-simples-nacional'; Jar = 'api-simples-nacional.jar';
        Args = @('-Dspring.profiles.active=offline'); Url = 'http://localhost:8082/api/versao'; Log = 'simples-nacional' },
    @{ Nome = 'Hub Fiscal'; Pasta = 'hub-fiscal'; Jar = 'hub-fiscal.jar'; Args = @(); Url = 'http://localhost:8090/hub/status'; Log = 'hub' }
)

function Get-ProcessoServico($s) {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*$($s.Jar)*" }
}

function Test-Url([string]$Url) {
    try { Invoke-WebRequest $Url -UseBasicParsing -TimeoutSec 3 | Out-Null; return $true } catch { return $false }
}

function Find-Git {
    $g = Get-Command git -ErrorAction SilentlyContinue
    if ($g) { return $g.Source }
    $desk = Get-ChildItem "$env:LOCALAPPDATA\GitHubDesktop" -Directory -Filter 'app-*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'resources\app\git\cmd\git.exe' } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
    return $desk
}

function Invoke-Maven([string]$Pasta, [string[]]$Argumentos) {
    Push-Location (Join-Path $Raiz $Pasta)
    try {
        & .\mvnw.cmd @Argumentos --batch-mode -q
        if ($LASTEXITCODE -ne 0) { throw "Maven falhou em $Pasta (código $LASTEXITCODE)" }
    } finally { Pop-Location }
}

$BancoRG = Join-Path $Raiz 'calculadora-rtc-regime-regular\calculadora\db\calculadora-pro.db'
$BancoSN = Join-Path $Raiz 'calculadora-rtc-simples-nacional\calculadora\db\calculadora-pro.db'

function New-BancoOficial {
    Write-Etapa 'Gerando o banco de dados oficial (Flyway)'
    $dir = Split-Path $BancoRG
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    if (Test-Path $BancoRG) { Remove-Item $BancoRG -Force }
    Invoke-Maven 'calculadora-rtc-regime-regular' @('flyway:migrate', '-Dflyway.configFiles=flyway\flyway-pro.conf')
    Write-Ok "Banco gerado: $BancoRG"
}

function Copy-BancoSimples {
    $dir = Split-Path $BancoSN
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    Copy-Item $BancoRG $BancoSN -Force
    Write-Ok 'Banco copiado para a calculadora do Simples Nacional'
}

function Build-Servico($s) {
    Write-Etapa "Compilando $($s.Nome)"
    Invoke-Maven $s.Pasta @('clean', 'package', '-DskipTests')
    Write-Ok "$($s.Jar) gerado"
}

$RepositoriosReceita = [ordered]@{
    'calculadora-rtc-regime-regular'   = 'https://github.com/ReceitaFederal/calculadora-rtc-regime-regular.git'
    'calculadora-rtc-simples-nacional' = 'https://github.com/ReceitaFederal/calculadora-rtc-simples-nacional.git'
}

# Em um clone novo deste projeto as calculadoras da Receita não vêm junto: baixa do GitHub oficial.
function Install-Calculadoras {
    foreach ($nome in $RepositoriosReceita.Keys) {
        if (Test-Path (Join-Path $Raiz "$nome\pom.xml")) { continue }
        $git = Find-Git
        if (-not $git) { Write-Falha "Git não encontrado: instale o Git para Windows (https://git-scm.com) para baixar $nome."; exit 1 }
        Write-Etapa "Baixando a calculadora oficial $nome (GitHub da Receita Federal)"
        & $git -c safe.directory=* clone --depth 50 $RepositoriosReceita[$nome] (Join-Path $Raiz $nome)
        if ($LASTEXITCODE -ne 0) { Write-Falha "git clone falhou para $nome"; exit 1 }
        Write-Ok "$nome baixada"
    }
}

function Start-Servicos {
    Install-Calculadoras
    if (-not (Test-Path $BancoRG)) { New-BancoOficial }
    if (-not (Test-Path $BancoSN)) { Copy-BancoSimples }
    foreach ($s in $Servicos) {
        $jar = Join-Path $Raiz "$($s.Pasta)\target\$($s.Jar)"
        if (-not (Test-Path $jar)) { Build-Servico $s }
        if (Get-ProcessoServico $s) { Write-Ok "$($s.Nome) já está em execução"; continue }
        Write-Etapa "Iniciando $($s.Nome)"
        $argumentos = @($s.Args) + @('-jar', "target\$($s.Jar)")
        Start-Process -FilePath $Java -ArgumentList $argumentos -WorkingDirectory (Join-Path $Raiz $s.Pasta) `
            -RedirectStandardOutput (Join-Path $Logs "$($s.Log).log") -RedirectStandardError (Join-Path $Logs "$($s.Log).err") `
            -WindowStyle Hidden | Out-Null
    }
    foreach ($s in $Servicos) {
        $ok = $false
        for ($i = 0; $i -lt 90; $i++) { if (Test-Url $s.Url) { $ok = $true; break }; Start-Sleep -Seconds 2 }
        if ($ok) { Write-Ok "$($s.Nome) respondendo" } else { Write-Falha "$($s.Nome) não respondeu. Veja logs\$($s.Log).log" }
    }
    $ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254*' } | Select-Object -ExpandProperty IPAddress
    Write-Host ""
    Write-Host "Hub Fiscal disponível em:" -ForegroundColor Yellow
    Write-Host "   http://localhost:8090   (neste computador)"
    foreach ($ip in $ips) { Write-Host "   http://$($ip):8090   (outros computadores da rede)" }
}

function Stop-Servicos {
    foreach ($s in $Servicos) {
        $p = Get-ProcessoServico $s
        if ($p) {
            $p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -Confirm:$false }
            Write-Ok "$($s.Nome) parado"
        } else { Write-Host "    --  $($s.Nome) não estava em execução" }
    }
}

function Show-Status {
    foreach ($s in $Servicos) {
        $p = Get-ProcessoServico $s
        $online = Test-Url $s.Url
        $estado = if ($online) { 'respondendo' } elseif ($p) { 'iniciando/sem resposta' } else { 'parado' }
        $cor = if ($online) { 'Green' } elseif ($p) { 'Yellow' } else { 'Red' }
        Write-Host ("    {0,-30} {1}" -f $s.Nome, $estado) -ForegroundColor $cor
    }
}

function Update-Calculadoras {
    $git = Find-Git
    if (-not $git) { Write-Falha 'Git não encontrado (instale o Git ou o GitHub Desktop).'; exit 1 }
    Write-Etapa 'Parando os serviços'
    Stop-Servicos
    foreach ($repo in 'calculadora-rtc-regime-regular', 'calculadora-rtc-simples-nacional') {
        Write-Etapa "Atualizando $repo (git pull)"
        & $git -c safe.directory=* -C (Join-Path $Raiz $repo) pull --ff-only
        if ($LASTEXITCODE -ne 0) { Write-Falha "git pull falhou em $repo"; exit 1 }
    }
    New-BancoOficial
    Copy-BancoSimples
    foreach ($s in $Servicos) { Build-Servico $s }
    Start-Servicos
}

$Repos = @('calculadora-rtc-regime-regular', 'calculadora-rtc-simples-nacional')
$ArquivoResultado = Join-Path $Logs 'ultima-atualizacao.json'

function Save-Resultado([string]$Resultado, [string]$Mensagem, $Detalhes) {
    [ordered]@{
        data      = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ss')
        resultado = $Resultado     # sem-novidade | atualizado | falha
        mensagem  = $Mensagem
        repos     = $Detalhes
    } | ConvertTo-Json -Depth 4 | Set-Content -Path $ArquivoResultado -Encoding UTF8
}

function Invoke-Git([string]$Repo, [string[]]$Argumentos) {
    # safe.directory: a tarefa roda como SYSTEM e as pastas pertencem a outro usuário
    $ErrorActionPreference = 'Continue'
    $saida = & $script:Git -c safe.directory=* -C (Join-Path $Raiz $Repo) @Argumentos 2>&1
    if ($LASTEXITCODE -ne 0) { throw "git $($Argumentos -join ' ') falhou em ${Repo}: $saida" }
    return ($saida | Out-String).Trim()
}

function Update-SeHouverNovidade {
    Start-Transcript -Path (Join-Path $Logs 'atualizacao.log') -Append | Out-Null
    try {
        $script:Git = Find-Git
        if (-not $script:Git) { Save-Resultado 'falha' 'Git não encontrado no servidor.' @(); Write-Falha 'Git não encontrado'; return }

        Write-Etapa 'Consultando o GitHub da Receita'
        $info = @()
        foreach ($r in $Repos) {
            Invoke-Git $r @('fetch', '--quiet', 'origin') | Out-Null
            $local = Invoke-Git $r @('rev-parse', 'HEAD')
            $remoto = Invoke-Git $r @('rev-parse', '@{u}')
            $novos = [int](Invoke-Git $r @('rev-list', '--count', 'HEAD..@{u}'))
            $info += [ordered]@{ repo = $r; anterior = $local; novo = $remoto; commitsNovos = $novos }
            Write-Host ("    {0}: {1} commit(s) novo(s)" -f $r, $novos)
        }
        if (-not ($info | Where-Object { $_.commitsNovos -gt 0 })) {
            Save-Resultado 'sem-novidade' 'As calculadoras já estão na versão mais recente.' $info
            Write-Ok 'Nada a atualizar'
            # garante que os serviços estão no ar (ex.: depois de uma queda)
            if (-not ($Servicos | Where-Object { -not (Test-Url $_.Url) })) { return }
            Start-Servicos
            return
        }

        try {
            Write-Etapa 'Há versão nova: atualizando'
            Stop-Servicos
            foreach ($r in $Repos) { Invoke-Git $r @('merge', '--ff-only', '@{u}') | Out-Null }
            New-BancoOficial
            Copy-BancoSimples
            foreach ($s in $Servicos) { Build-Servico $s }
            Start-Servicos
            $fora = $Servicos | Where-Object { -not (Test-Url $_.Url) }
            if ($fora) { throw "Serviço(s) não responderam após a atualização: $(($fora | ForEach-Object Nome) -join ', ')" }
            $resumo = ($info | ForEach-Object { "$($_.repo): $($_.commitsNovos) commit(s)" }) -join '; '
            Save-Resultado 'atualizado' "Calculadoras atualizadas ($resumo)." $info
            Write-Ok 'Atualização concluída'
        } catch {
            $erro = $_.Exception.Message
            Write-Falha "Falha na atualização: $erro"
            Write-Etapa 'Voltando para a versão anterior'
            try {
                Stop-Servicos
                foreach ($i in $info) { Invoke-Git $i.repo @('reset', '--hard', $i.anterior) | Out-Null }
                New-BancoOficial
                Copy-BancoSimples
                foreach ($s in $Servicos) { Build-Servico $s }
                Start-Servicos
                Save-Resultado 'falha' "A atualização falhou e a versão anterior foi restaurada. Erro: $erro" $info
            } catch {
                Save-Resultado 'falha' "A atualização falhou e não foi possível restaurar a versão anterior. Erro: $erro / $($_.Exception.Message)" $info
                Start-Servicos
            }
        }
    } catch {
        Save-Resultado 'falha' "Não foi possível verificar atualizações: $($_.Exception.Message)" @()
        Write-Falha $_.Exception.Message
    } finally {
        Stop-Transcript | Out-Null
    }
}

$NomesTarefas = @{ Iniciar = 'Hub Fiscal - Iniciar'; Atualizar = 'Hub Fiscal - Atualizacao' }

function Register-Tarefas {
    $admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $admin) { Write-Falha 'Execute como administrador (clique direito > Executar como administrador).'; exit 1 }
    if (-not (Find-Git)) { Write-Falha 'Instale o Git para Windows no servidor antes de agendar (https://git-scm.com).'; exit 1 }
    $script = Join-Path $PSScriptRoot 'hub.ps1'
    $acao = { param($a) New-ScheduledTaskAction -Execute 'powershell.exe' -WorkingDirectory $Raiz `
        -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$script`" $a" }
    # conta SYSTEM: roda sem ninguém logado no servidor
    $quem = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $cfg = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit (New-TimeSpan -Hours 2) -MultipleInstances IgnoreNew

    $gIni = New-ScheduledTaskTrigger -AtStartup
    $gIni.Delay = 'PT1M'
    Register-ScheduledTask -TaskName $NomesTarefas.Iniciar -Action (& $acao 'iniciar') -Trigger $gIni -Principal $quem -Settings $cfg `
        -Description 'Liga as calculadoras oficiais e o Hub Fiscal quando o servidor inicia.' -Force | Out-Null
    Write-Ok "Tarefa '$($NomesTarefas.Iniciar)' criada (ao iniciar o servidor)"

    $gAtu = New-ScheduledTaskTrigger -Daily -At $Hora
    Register-ScheduledTask -TaskName $NomesTarefas.Atualizar -Action (& $acao 'verificar') -Trigger $gAtu -Principal $quem -Settings $cfg `
        -Description 'Verifica versão nova das calculadoras da Receita e atualiza se houver.' -Force | Out-Null
    Write-Ok "Tarefa '$($NomesTarefas.Atualizar)' criada (todo dia às $Hora)"
    Write-Host ''
    Write-Host 'Resultado de cada verificação: tela "Situação do sistema" do hub e logs\atualizacao.log' -ForegroundColor Yellow
}

function Unregister-Tarefas {
    foreach ($n in $NomesTarefas.Values) {
        if (Get-ScheduledTask -TaskName $n -ErrorAction SilentlyContinue) {
            Unregister-ScheduledTask -TaskName $n -Confirm:$false
            Write-Ok "Tarefa '$n' removida"
        } else { Write-Host "    --  Tarefa '$n' não existia" }
    }
}

switch ($Acao) {
    'iniciar' { Start-Servicos }
    'parar' { Write-Etapa 'Parando os serviços'; Stop-Servicos }
    'reiniciar' { Write-Etapa 'Parando os serviços'; Stop-Servicos; Start-Sleep -Seconds 2; Start-Servicos }
    'status' { Write-Etapa 'Situação'; Show-Status }
    'atualizar' { Update-Calculadoras }
    'verificar' { Update-SeHouverNovidade }
    'agendar' { Write-Etapa 'Agendando inicialização e atualização automática'; Register-Tarefas }
    'desagendar' { Write-Etapa 'Removendo tarefas agendadas'; Unregister-Tarefas }
}
