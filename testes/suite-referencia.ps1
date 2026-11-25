<#
  Suíte de referência: grava as respostas do hub para um conjunto fixo de entradas e compara com
  testes\referencia (respostas aprovadas). Pega qualquer mudança de comportamento: JSON dos lotes,
  planilhas Excel célula a célula, consultas do catálogo, repasse do proxy às calculadoras e erros.

  Normaliza o que muda a cada execução: id do resultado, horário de geração, data de hoje e, nas
  respostas de erro da calculadora da Receita, o carimbo de hora e a ordem da lista de campos.

  Quando a Receita publica uma base nova, algumas respostas mudam de verdade: confira as diferenças
  e, se estiverem corretas, grave a nova referência (executar-testes.ps1 -GravarReferencia).
#>
param([switch]$GravarReferencia)

$Suite = 'Referência'
$dirRef = Join-Path $PSScriptRoot 'referencia'
$saida = Join-Path ([IO.Path]::GetTempPath()) ("hub-testes-saida-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory $saida | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$hojeIso = (Get-Date).ToString('yyyy-MM-dd')
$hojeCompacto = (Get-Date).ToString('yyyyMMdd')

function Format-Normalizado([string]$t) {
    $t = [regex]::Replace($t, '"id":"[0-9a-f-]{36}"', '"id":"<ID>"')
    $t = [regex]::Replace($t, '\d{2}/\d{2}/\d{4} \d{2}:\d{2}', '<AGORA>')
    $t = $t.Replace($hojeIso, '<HOJE>').Replace("-$hojeCompacto.xlsx", '-<HOJE>.xlsx')
    $t = [regex]::Replace($t, '"timestamp":"[^"]+"', '"timestamp":"<TS>"')
    $t = [regex]::Replace($t, '(\[[^\[\]]+\] )+', { param($m) ((([regex]::Matches($m.Value, '\[[^\[\]]+\] ') | ForEach-Object Value) | Sort-Object) -join '') })
    $t
}

function Save-Resposta([string]$Nome, $Resposta) {
    $cab = @("HTTP $(Get-Status $Resposta)", "Content-Type: $($Resposta.Content.Headers.ContentType)")
    if ($Resposta.Headers.Contains('x-warning-dados-simulados')) {
        $cab += 'x-warning-dados-simulados: ' + ($Resposta.Headers.GetValues('x-warning-dados-simulados') -join ',')
    }
    [IO.File]::WriteAllText((Join-Path $saida "$Nome.txt"), (($cab -join "`n") + "`n`n" + (Format-Normalizado (Get-Texto $Resposta))), $utf8)
}

<# Planilha: guarda o XML de cada aba (valores, tipos e estilos das células) e a lista de abas. #>
function Save-Excel([string]$Nome, $Resposta) {
    $tmp = Join-Path $saida "$Nome.xlsx"
    [IO.File]::WriteAllBytes($tmp, $Resposta.Content.ReadAsByteArrayAsync().Result)
    $partes = @("HTTP $(Get-Status $Resposta) $(Format-Normalizado ([string]$Resposta.Content.Headers.ContentDisposition))")
    try {
        $z = [IO.Compression.ZipFile]::OpenRead($tmp)
        foreach ($e in ($z.Entries | Where-Object { $_.FullName -like 'xl/worksheets/*.xml' -or $_.FullName -eq 'xl/workbook.xml' } | Sort-Object FullName)) {
            $r = New-Object IO.StreamReader($e.Open()); $partes += "== $($e.FullName)`n" + (Format-Normalizado $r.ReadToEnd()); $r.Dispose()
        }
        $z.Dispose()
    } catch { $partes += "(não é um .xlsx válido: $($_.Exception.Message))" }
    Remove-Item $tmp
    [IO.File]::WriteAllText((Join-Path $saida "$Nome.excel.txt"), ($partes -join "`n"), $utf8)
}

try {
# ---------------------------------------------------------------- respostas gravadas
$adm = Enter-Hub $UsuarioAdmin $SenhaAdmin
$nfe = Join-Path $Dados 'nfe-ibscbs.xml'
$sped = Join-Path $Dados 'sped-efd-icms-ipi.txt'

# lotes: a classificação usa a base oficial e as tabelas da API; a auditoria faz POST real na calculadora
$r = Send-Arquivos $adm '/hub/lote/classificacao?data=2026-03-10' @($sped, $nfe); Save-Resposta 'lote-classificacao' $r
$id = (Get-Texto $r | ConvertFrom-Json).id
Save-Excel 'lote-classificacao' (Invoke-Hub $adm 'GET' "/hub/lote/classificacao/$id/excel")
Save-Resposta 'lote-classificacao-2027' (Send-Arquivos $adm '/hub/lote/classificacao?data=2027-06-01' @($sped))
$r = Send-Arquivos $adm '/hub/lote/auditoria' @($nfe, $sped); Save-Resposta 'lote-auditoria' $r
$id = (Get-Texto $r | ConvertFrom-Json).id
Save-Excel 'lote-auditoria' (Invoke-Hub $adm 'GET' "/hub/lote/auditoria/$id/excel")
Save-Resposta 'lote-vazio' (Send-Arquivos $adm '/hub/lote/classificacao' @((Join-Path $Dados 'vazio.xml')))
Save-Resposta 'lote-xxe' (Send-Arquivos $adm '/hub/lote/auditoria' @((Join-Path $Dados 'xml-xxe.xml')))
Save-Resposta 'lote-quebrado' (Send-Arquivos $adm '/hub/lote/classificacao' @((Join-Path $Dados 'xml-quebrado.xml')))

# catálogo
foreach ($q in @('ncm/10063021?data=2026-03-10', 'ncm/27101259?data=2026-03-10', 'ncm/22030000?data=2027-06-01',
        'ncm/10063021?data=2026-03-10&tipoDfe=65', 'ncm/123', 'ncm/99999999', 'nbs/101011100?data=2026-03-10',
        'nbs/112012000?data=2026-03-10&tipoDfe=91', 'busca/ncm?q=arroz&limite=5', 'busca/ncm?q=1006',
        'busca/nbs?q=consultoria', 'busca/ncm?q=a', 'busca/xyz?q=abc', 'classificacao/200003?data=2026-03-10',
        'classificacao/000001?data=2026-03-10', 'classificacao/zzzzzz')) {
    Save-Resposta ('get-' + ($q -replace '[^\w]+', '_')) (Invoke-Hub $adm 'GET' "/hub/$q")
}

# proxy: repasse transparente às calculadoras (GET e POST, 2026 e 2027, erro de validação, API inexistente)
Save-Resposta 'proxy-rg-versao' (Invoke-Hub $adm 'GET' '/proxy/rg/calculadora/dados-abertos/versao')
Save-Resposta 'proxy-sn-versao' (Invoke-Hub $adm 'GET' '/proxy/sn/versao')
Save-Resposta 'proxy-rg-ufs' (Invoke-Hub $adm 'GET' '/proxy/rg/calculadora/dados-abertos/ufs')
Save-Resposta 'proxy-rg-calculo' (Invoke-Hub $adm 'POST' '/proxy/rg/calculadora/regime-geral' `
    '{"id":"t1","versao":"1.0.0","dhFatoGerador":"2026-03-10T10:00:00-03:00","municipio":3550308,"uf":"SP","tpDoc":55,"itens":[{"numero":1,"ncm":"10063021","cst":"200","cClassTrib":"200003","quantidade":1,"unidade":"KG","baseCalculo":100}]}')
Save-Resposta 'proxy-rg-calculo-2027' (Invoke-Hub $adm 'POST' '/proxy/rg/calculadora/observabilidade/regime-geral' `
    '{"id":"t2","versao":"1.0.0","dhFatoGerador":"2027-06-01T10:00:00-03:00","municipio":3550308,"uf":"SP","tpDoc":55,"itens":[{"numero":1,"ncm":"73181500","cst":"000","cClassTrib":"000001","baseCalculo":100,"aliquotasNominais":{"cbs":8.8,"ibsEstadual":10,"ibsMunicipal":7.7}}]}')
Save-Resposta 'proxy-rg-invalido' (Invoke-Hub $adm 'POST' '/proxy/rg/calculadora/regime-geral' '{"id":"x"}')
Save-Resposta 'proxy-api-inexistente' (Invoke-Hub $adm 'GET' '/proxy/zz/versao')

# exportação genérica para Excel e erros de requisição
Save-Excel 'excel-generico' (Invoke-Hub $adm 'POST' '/hub/excel' `
    '{"titulo":"T","arquivo":"teste x","colunas":[{"titulo":"A","tipo":"texto"},{"titulo":"V","tipo":"moeda"},{"titulo":"P","tipo":"percentual"}],"linhas":[["a",1.5,"2,5"],["=1+1","x",null]],"resumo":[["k","v"],["sub"]]}')
Save-Resposta 'excel-sem-colunas' (Invoke-Hub $adm 'POST' '/hub/excel' '{"titulo":"T"}')
Save-Resposta 'media-errada' (Invoke-Hub $adm 'POST' '/hub/excel' 'x' 'text/plain')
Save-Resposta 'metodo-errado' (Invoke-Hub $adm 'DELETE' '/hub/status')
Save-Resposta 'data-invalida' (Invoke-Hub $adm 'GET' '/hub/ncm/10063021?data=31-12-2026')

# ---------------------------------------------------------------- comparação
if ($GravarReferencia) {
    Remove-Item $dirRef -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item $saida $dirRef -Recurse
    Add-Resultado $Suite "referência gravada: $((Get-ChildItem $dirRef).Count) respostas em testes\referencia" $true
} else {
    $esperadas = Get-ChildItem $dirRef -File -ErrorAction SilentlyContinue
    if (-not $esperadas) {
        Add-Resultado $Suite 'referência existe' $false 'testes\referencia vazia: rode executar-testes.ps1 -GravarReferencia'
    }
    foreach ($f in $esperadas) {
        $obtida = Join-Path $saida $f.Name
        $nome = [IO.Path]::GetFileNameWithoutExtension($f.Name) -replace '\.excel$', ' (Excel)'
        if (-not (Test-Path $obtida)) { Add-Resultado $Suite $nome $false 'resposta não foi gerada'; continue }
        $a = [IO.File]::ReadAllText($f.FullName); $b = [IO.File]::ReadAllText($obtida)
        if ($a -eq $b) { Add-Resultado $Suite $nome $true; continue }
        # mostra a primeira diferença
        $la = $a -split "`n"; $lb = $b -split "`n"; $i = 0
        while ($i -lt [Math]::Min($la.Count, $lb.Count) -and $la[$i] -eq $lb[$i]) { $i++ }
        $pos = 0; $x = if ($i -lt $la.Count) { $la[$i] } else { '' }; $y = if ($i -lt $lb.Count) { $lb[$i] } else { '' }
        while ($pos -lt [Math]::Min($x.Length, $y.Length) -and $x[$pos] -eq $y[$pos]) { $pos++ }
        $ini = [Math]::Max(0, $pos - 40)
        $trecho = { param($s) if ($ini -lt $s.Length) { $s.Substring($ini, [Math]::Min(120, $s.Length - $ini)) } else { '' } }
        Add-Resultado $Suite $nome $false ("linha $($i + 1): esperado «" + (& $trecho $x) + "» | obtido «" + (& $trecho $y) + "»")
    }
}
} finally {
    Remove-Item $saida -Recurse -Force -ErrorAction SilentlyContinue   # mesmo se a suíte parar no meio
}
