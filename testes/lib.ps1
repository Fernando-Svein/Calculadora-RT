<#
  Funções comuns dos testes do Hub Fiscal: sessão HTTP (cookies + token CSRF) e registro de resultados.
  Usa HttpClient em vez de Invoke-WebRequest: com -WebSession, o Invoke-WebRequest guarda os -Headers
  na sessão e os reenvia nas chamadas seguintes, o que mascara testes de CSRF.
#>
Add-Type -AssemblyName System.Net.Http, System.IO.Compression, System.IO.Compression.FileSystem

$script:Resultados = New-Object System.Collections.Generic.List[object]

# ---------------------------------------------------------------- HTTP

function New-SessaoHub {
    $cookies = New-Object Net.CookieContainer
    $handler = New-Object Net.Http.HttpClientHandler
    $handler.CookieContainer = $cookies
    $handler.AllowAutoRedirect = $false
    $cliente = New-Object Net.Http.HttpClient($handler)
    $cliente.Timeout = [TimeSpan]::FromMinutes(5)
    $cliente.GetAsync("$Base/auth/csrf").Result | Out-Null
    [pscustomobject]@{ Cliente = $cliente; Cookies = $cookies }
}

function Get-TokenCsrf($Sessao) {
    $c = $Sessao.Cookies.GetCookies([uri]$Base) | Where-Object Name -eq 'XSRF-TOKEN'
    if ($c) { [uri]::UnescapeDataString($c.Value) } else { '' }
}

<# Requisição ao hub. Devolve a HttpResponseMessage. $Json sem tipo: [string] converteria $null em "" (GET com corpo). #>
function Invoke-Hub($Sessao, [string]$Metodo, [string]$Url, $Json = $null,
    [string]$ContentType = 'application/json', [hashtable]$Cabecalhos = @{}, [switch]$SemToken) {
    $m = New-Object Net.Http.HttpRequestMessage($Metodo, "$Base$Url")
    $m.Headers.Add('Accept', 'application/json')
    if ($Metodo -ne 'GET' -and -not $SemToken) { $m.Headers.Add('X-XSRF-TOKEN', (Get-TokenCsrf $Sessao)) }
    foreach ($k in $Cabecalhos.Keys) { $m.Headers.TryAddWithoutValidation($k, [string]$Cabecalhos[$k]) | Out-Null }
    if ($null -ne $Json) { $m.Content = New-Object Net.Http.StringContent($Json, [Text.Encoding]::UTF8, $ContentType) }
    $Sessao.Cliente.SendAsync($m).Result
}

<# Envio de arquivos (multipart, campo "arquivos"), como faz a tela de lotes. #>
function Send-Arquivos($Sessao, [string]$Url, [string[]]$Arquivos) {
    $m = New-Object Net.Http.HttpRequestMessage('POST', "$Base$Url")
    $m.Headers.Add('Accept', 'application/json')
    $m.Headers.Add('X-XSRF-TOKEN', (Get-TokenCsrf $Sessao))
    $form = New-Object Net.Http.MultipartFormDataContent
    foreach ($a in $Arquivos) {
        $form.Add((New-Object Net.Http.ByteArrayContent(, [IO.File]::ReadAllBytes($a))), 'arquivos', [IO.Path]::GetFileName($a))
    }
    $m.Content = $form
    $Sessao.Cliente.SendAsync($m).Result
}

function Get-Texto($Resposta) { $Resposta.Content.ReadAsStringAsync().Result }
function Get-Status($Resposta) { [int]$Resposta.StatusCode }

<# Login; devolve a sessão ou lança erro. #>
function Enter-Hub([string]$Login, [string]$Senha) {
    $s = New-SessaoHub
    $corpo = @{ usuario = $Login; senha = $Senha } | ConvertTo-Json -Compress
    $r = Invoke-Hub $s 'POST' '/auth/login' $corpo
    if (-not $r.IsSuccessStatusCode) { throw "login de $Login falhou: HTTP $(Get-Status $r) $(Get-Texto $r)" }
    $s
}

# ---------------------------------------------------------------- resultados

function Add-Resultado([string]$Suite, [string]$Nome, [bool]$Ok, [string]$Detalhe = '') {
    $script:Resultados.Add([pscustomobject]@{ Suite = $Suite; Nome = $Nome; Ok = $Ok; Detalhe = $Detalhe })
    $marca = if ($Ok) { '  ok  ' } else { '  FALHOU' }
    $cor = if ($Ok) { 'Green' } else { 'Red' }
    Write-Host ("{0} {1}" -f $marca, $Nome) -ForegroundColor $cor
    if (-not $Ok -and $Detalhe) { Write-Host "         $Detalhe" -ForegroundColor DarkRed }
}

<# Confere o status HTTP (um ou mais aceitos) e, opcionalmente, um trecho do corpo. #>
function Assert-Resposta([string]$Suite, [string]$Nome, $Resposta, [int[]]$Status, [string]$Contem = $null) {
    $st = Get-Status $Resposta
    $txt = Get-Texto $Resposta
    $ok = $st -in $Status
    $det = "esperado HTTP $($Status -join '/'), veio $st"
    if ($ok -and $Contem -and -not $txt.Contains($Contem)) { $ok = $false; $det = "a resposta não contém '$Contem'" }
    if (-not $ok) { $det += ": " + ($txt.Substring(0, [Math]::Min(200, $txt.Length)) -replace '\s+', ' ') }
    Add-Resultado $Suite $Nome $ok $det
}
