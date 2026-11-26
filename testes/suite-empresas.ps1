<#
  Suíte de empresas e relatórios: cadastro automático na importação (sem duplicar), cadastro e edição das
  particularidades, permissões, relatório PDF dos simuladores e datas da base no status.
  Usa o banco de empresas temporário da cópia de teste (o cadastro real não é tocado).
#>
$Suite = 'Empresas'
$adm = Enter-Hub $UsuarioAdmin $SenhaAdmin
$lim = Enter-Hub $UsuarioLimitado $SenhaLimitado
$cpl = Enter-Hub $UsuarioCompleto $SenhaCompleto
$spedEmp = Join-Path $Dados 'empresa-sped-contribuicoes.txt'
$nfeEmp = Join-Path $Dados 'empresa-nfe-simples.xml'

function Get-Novas($r) {
    $v = $null
    if ($r.Headers.TryGetValues('X-Hub-Empresas-Novas', [ref]$v)) { [int]($v | Select-Object -First 1) } else { -1 }
}
# PS 5.1: ConvertFrom-Json devolve o array JSON como um objeto só; os parênteses o desmembram em itens
function Get-Empresas { (Get-Texto (Invoke-Hub $adm 'GET' '/hub/empresas') | ConvertFrom-Json) | ForEach-Object { $_ } }

# ---------------------------------------------------------------- cadastro automático
$antes = @(Get-Empresas)
Add-Resultado $Suite 'arquivos de teste antigos (CNPJ com dígito inválido) não geram cadastro' `
    (-not ($antes | Where-Object cnpj -eq '12345678000199')) "cadastradas: $(($antes | ForEach-Object cnpj) -join ', ')"

$r = Send-Arquivos $adm '/hub/lote/classificacao?data=2026-03-10' @($spedEmp, $nfeEmp)
Add-Resultado $Suite 'importação cadastra as 2 empresas novas (SPED + emitente do XML)' ((Get-Status $r) -eq 200 -and (Get-Novas $r) -eq 2) "HTTP $(Get-Status $r), novas=$(Get-Novas $r)"
$lista = @(Get-Empresas)
$sped = $lista | Where-Object cnpj -eq '11222333000181'
$xml = $lista | Where-Object cnpj -eq '12ABC34501DE35'
Add-Resultado $Suite 'SPED Contribuições: razão social, UF, município e regime normal / Lucro Presumido (0110 = 2)' `
    ($sped -and $sped.razaoSocial -ceq 'COMERCIAL EXEMPLO LTDA' -and $sped.uf -eq 'RJ' -and $sped.municipio -eq 3304557 -and
     $sped.regime -eq 'NORMAL' -and $sped.regimeApuracao -eq 'LUCRO_PRESUMIDO' -and $sped.origem -eq 'SPED' -and -not $sped.revisado) ($sped | ConvertTo-Json -Compress)
Add-Resultado $Suite 'NF-e: CNPJ alfanumérico, nome fantasia, município e CRT 1 = Simples Nacional' `
    ($xml -and $xml.nomeFantasia -eq 'Padaria Exemplo' -and $xml.uf -eq 'MG' -and $xml.municipio -eq 3106200 -and $xml.regime -eq 'SIMPLES' -and $xml.origem -eq 'XML') ($xml | ConvertTo-Json -Compress)
Add-Resultado $Suite 'destinatário da NF-e não é cadastrado' (-not ($lista | Where-Object cnpj -eq '98765432000198')) ''

$r = Send-Arquivos $adm '/hub/lote/auditoria' @($nfeEmp, $spedEmp)
$depois = @(Get-Empresas)
Add-Resultado $Suite 'mesmo CNPJ importado de novo não duplica o cadastro' ((Get-Novas $r) -eq 0 -and $depois.Count -eq $lista.Count) "novas=$(Get-Novas $r), antes=$($lista.Count), depois=$($depois.Count)"
$r = Send-Arquivos $lim '/hub/lote/classificacao' @($nfeEmp)
Add-Resultado $Suite 'usuário sem a ferramenta de lote não cadastra empresas pelo lote' ((Get-Status $r) -eq 403) "HTTP $(Get-Status $r)"

# ---------------------------------------------------------------- abrir e editar o cadastro
$alt = @{ razaoSocial = 'PADARIA EXEMPLO ME'; nomeFantasia = 'Padaria Exemplo'; uf = 'MG'; municipio = 3106200; regime = 'SIMPLES'
    fatorR = $true; ibscbsForaSimples = $false; sublimiteExcedido = $false; atividadeSimples = '01'; documentoPadrao = '65'; cClassTribPadrao = '000001'
    aliquotasSimples = @{ I = @{ cbs = '1,1612'; ibs = '0.0129' }; III = @{ cbs = '2,3874'; ibs = '0,0263'; cbsSemIss = '2,3591'; ibsSemIss = '0,026' } } } | ConvertTo-Json -Depth 4 -Compress
$r = Invoke-Hub $adm 'GET' "/hub/empresas/$($xml.id)"
Assert-Resposta $Suite 'abre o cadastro de uma empresa existente' $r 200 '"cnpj":"12ABC34501DE35"'
$r = Invoke-Hub $adm 'PUT' "/hub/empresas/$($xml.id)" $alt
$e = Get-Texto $r | ConvertFrom-Json
Add-Resultado $Suite 'edita as particularidades e marca o cadastro como conferido' `
    ((Get-Status $r) -eq 200 -and $e.revisado -and $e.fatorR -and $e.documentoPadrao -eq '65' -and $e.aliquotasSimples.I.cbs -eq '1.1612' -and $e.aliquotasSimples.III.ibsSemIss -eq '0.026') (Get-Texto $r)
$normal = ($alt | ConvertFrom-Json); $normal.regime = 'NORMAL'; $normal | Add-Member regimeApuracao 'LUCRO_REAL' -Force
$e = Get-Texto (Invoke-Hub $adm 'PUT' "/hub/empresas/$($xml.id)" ($normal | ConvertTo-Json -Depth 4 -Compress)) | ConvertFrom-Json
Add-Resultado $Suite 'regime normal descarta fator r e alíquotas do DAS (só valem no Simples)' (-not $e.fatorR -and @($e.aliquotasSimples.PSObject.Properties).Count -eq 0 -and $e.regimeApuracao -eq 'LUCRO_REAL') ($e | ConvertTo-Json -Compress)
Invoke-Hub $adm 'PUT' "/hub/empresas/$($xml.id)" $alt | Out-Null
$ruim = $alt.Replace('"III"', '"VI"')
Assert-Resposta $Suite 'anexo inexistente é recusado' (Invoke-Hub $adm 'PUT' "/hub/empresas/$($xml.id)" $ruim) 422 'Anexo do Simples inválido'
Assert-Resposta $Suite 'alíquota acima de 100% é recusada' (Invoke-Hub $adm 'PUT' "/hub/empresas/$($xml.id)" $alt.Replace('1,1612', '150')) 422 'fora do intervalo'
Assert-Resposta $Suite 'UF inválida é recusada' (Invoke-Hub $adm 'PUT' "/hub/empresas/$($xml.id)" $alt.Replace('"MG"', '"XX"')) 422 'UF inválida'
Assert-Resposta $Suite 'empresa inexistente' (Invoke-Hub $adm 'GET' '/hub/empresas/999999') 404

# ---------------------------------------------------------------- cadastro manual
$nova = @{ cnpj = '45.678.912/0001-55'; razaoSocial = 'SERVICOS MANUAIS LTDA'; regime = 'NORMAL' } | ConvertTo-Json -Compress
Assert-Resposta $Suite 'CNPJ com dígito verificador errado é recusado' (Invoke-Hub $adm 'POST' '/hub/empresas' $nova.Replace('0001-55', '0001-56')) 422 'CNPJ inválido'
$r = Invoke-Hub $adm 'POST' '/hub/empresas' $nova
Assert-Resposta $Suite 'cadastro manual (CNPJ com pontuação)' $r 200 '"cnpj":"45678912000155"'
$idManual = (Get-Texto $r | ConvertFrom-Json).id
Assert-Resposta $Suite 'cadastro manual com CNPJ já cadastrado é recusado' (Invoke-Hub $adm 'POST' '/hub/empresas' $nova) 422 'Já existe'
Assert-Resposta $Suite 'razão social obrigatória' (Invoke-Hub $adm 'POST' '/hub/empresas' '{"cnpj":"98765432000198"}') 422 'razão social'

# ---------------------------------------------------------------- permissões
Assert-Resposta $Suite 'usuário sem a ferramenta Empresas consulta (seletor dos simuladores)' (Invoke-Hub $lim 'GET' '/hub/empresas') 200
Assert-Resposta $Suite 'usuário sem a ferramenta Empresas não altera' (Invoke-Hub $lim 'PUT' "/hub/empresas/$($xml.id)" $alt) 403 'SEM_PERMISSAO'
Assert-Resposta $Suite 'usuário sem a ferramenta Empresas não exclui' (Invoke-Hub $lim 'DELETE' "/hub/empresas/$idManual") 403 'SEM_PERMISSAO'
Assert-Resposta $Suite 'usuário com todas as ferramentas altera' (Invoke-Hub $cpl 'PUT' "/hub/empresas/$($xml.id)" $alt) 200
Assert-Resposta $Suite 'alteração sem token CSRF é recusada' (Invoke-Hub $adm 'PUT' "/hub/empresas/$($xml.id)" $alt -SemToken) 403 'CSRF'
Assert-Resposta $Suite 'sem login não consulta empresas' (Invoke-Hub (New-SessaoHub) 'GET' '/hub/empresas') 401
Assert-Resposta $Suite 'exclusão' (Invoke-Hub $adm 'DELETE' "/hub/empresas/$idManual") 204
Assert-Resposta $Suite 'excluída não abre mais' (Invoke-Hub $adm 'GET' "/hub/empresas/$idManual") 404

# ---------------------------------------------------------------- relatório PDF dos simuladores
$pedido = @{ titulo = 'Simulação – Regime Regular'; arquivo = 'simulacao-teste'
    empresa = @('PADARIA EXEMPLO ME', 'CNPJ 12.ABC.345/01DE-35 · Simples Nacional · Belo Horizonte/MG')
    resumo = @(, @('Base IBS/CBS', 'R$ 1.000,00')); observacoes = @('Cálculo ≥ teste → símbolos fora do WinAnsi')
    colunas = @(@{ titulo = 'Item'; tipo = 'numero' }, @{ titulo = 'Descrição'; tipo = 'texto' }, @{ titulo = 'CBS'; tipo = 'moeda' }, @{ titulo = 'Alíq.'; tipo = 'percentual' })
    linhas = @(1..120 | ForEach-Object { , @($_, "Item com descrição longa número $_ para testar a quebra de linha e de página do relatório", 9.5, 0.9) }) } | ConvertTo-Json -Depth 5 -Compress
$r = Invoke-Hub $adm 'POST' '/hub/relatorio/pdf' $pedido
$bytes = $r.Content.ReadAsByteArrayAsync().Result
$ehPdf = $bytes.Length -gt 3000 -and [Text.Encoding]::ASCII.GetString($bytes, 0, 5) -eq '%PDF-' -and [Text.Encoding]::ASCII.GetString($bytes).TrimEnd().EndsWith('%%EOF')
Add-Resultado $Suite 'relatório PDF com 120 linhas (quebra de página, acentos e símbolos)' ((Get-Status $r) -eq 200 -and $ehPdf -and "$($r.Content.Headers.ContentType)" -eq 'application/pdf') "HTTP $(Get-Status $r), pdf=$ehPdf, $($bytes.Length) bytes"
Add-Resultado $Suite 'PDF vem como anexo com o nome do simulador' ("$($r.Content.Headers.ContentDisposition)" -match 'simulacao-teste-\d{8}\.pdf') "$($r.Content.Headers.ContentDisposition)"
$muitas = @{ titulo = 'x'; colunas = @(1..41 | ForEach-Object { @{ titulo = "c$_" } }); linhas = @() } | ConvertTo-Json -Depth 4 -Compress
Assert-Resposta $Suite 'PDF com colunas demais é recusado (orienta usar o Excel)' (Invoke-Hub $adm 'POST' '/hub/relatorio/pdf' $muitas) 400 'Excel'
Assert-Resposta $Suite 'PDF sem título é recusado' (Invoke-Hub $adm 'POST' '/hub/relatorio/pdf' '{"colunas":[]}') 400
Assert-Resposta $Suite 'PDF sem login' (Invoke-Hub (New-SessaoHub) 'POST' '/hub/relatorio/pdf' $pedido) 401, 403
Assert-Resposta $Suite 'usuário limitado também gera relatório (como o Excel)' (Invoke-Hub $lim 'POST' '/hub/relatorio/pdf' $pedido) 200

# ---------------------------------------------------------------- datas da base no status
$s = Get-Texto (Invoke-Hub $adm 'GET' '/hub/status') | ConvertFrom-Json
Add-Resultado $Suite 'status traz a data da última atualização da base e o agendamento' `
    ($s.base -and $s.base.ultimaAtualizacao -match '^\d{4}-\d{2}-\d{2}T' -and $null -ne $s.base.agendada) ($s.base | ConvertTo-Json -Compress)
