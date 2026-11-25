<#
  Suíte funcional: entradas inválidas, arquivos ruins e limites. Cada caso diz o que se espera.
#>
$Suite = 'Funcional'
$adm = Enter-Hub $UsuarioAdmin $SenhaAdmin
$nfe = Join-Path $Dados 'nfe-ibscbs.xml'
$sped = Join-Path $Dados 'sped-efd-icms-ipi.txt'

# binário com extensão .xml (gerado aqui para não versionar lixo)
$binario = Join-Path ([IO.Path]::GetTempPath()) 'hub-teste-binario.xml'
[IO.File]::WriteAllBytes($binario, [byte[]](0..255))

# ---------------------------------------------------------------- lotes
$r = Send-Arquivos $adm '/hub/lote/classificacao?data=2026-03-10' @($nfe)
Assert-Resposta $Suite 'classificação de NF-e com HTML no texto e item sem NCM' $r 200 '"SEM_NOMENCLATURA":1'
Assert-Resposta $Suite 'SPED: lê cadastro 0200 e movimentos C170' (Send-Arquivos $adm '/hub/lote/classificacao' @($sped)) 200 '6 itens no 0200, 4 movimentos'
Assert-Resposta $Suite 'XML quebrado é recusado com explicação' (Send-Arquivos $adm '/hub/lote/classificacao' @((Join-Path $Dados 'xml-quebrado.xml'))) 422 'XML inválido ou corrompido'
Assert-Resposta $Suite 'XML com DOCTYPE/entidade externa (XXE) é recusado' (Send-Arquivos $adm '/hub/lote/classificacao' @((Join-Path $Dados 'xml-xxe.xml'))) 422 'DOCTYPE'
Assert-Resposta $Suite 'arquivo vazio é explicado' (Send-Arquivos $adm '/hub/lote/classificacao' @((Join-Path $Dados 'vazio.xml'))) 422 'Arquivo vazio'
Assert-Resposta $Suite 'binário com extensão .xml é ignorado' (Send-Arquivos $adm '/hub/lote/classificacao' @($binario)) 422 'Formato não reconhecido'
Assert-Resposta $Suite 'data em formato errado' (Send-Arquivos $adm '/hub/lote/classificacao?data=31-12-2026' @($nfe)) 400 'AAAA-MM-DD'
Assert-Resposta $Suite 'lote sem arquivo' (Invoke-Hub $adm 'POST' '/hub/lote/classificacao' $null) 400 'Nenhum arquivo'
$r = Send-Arquivos $adm '/hub/lote/auditoria' @($nfe)
Assert-Resposta $Suite 'auditoria recalcula na calculadora oficial (diverge: 200003 tem redução de 100%)' $r 200 '"DIVERGENTE":1'
Assert-Resposta $Suite 'auditoria com SPED avisa que ele não é usado' (Send-Arquivos $adm '/hub/lote/auditoria' @($sped)) 200 'Arquivos SPED não são usados'
Assert-Resposta $Suite 'Excel de resultado inexistente' (Invoke-Hub $adm 'GET' '/hub/lote/classificacao/nao-existe/excel') 422 'Resultado não encontrado'
Remove-Item $binario -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- consultas
Assert-Resposta $Suite 'NCM válido' (Invoke-Hub $adm 'GET' '/hub/ncm/10063021?data=2026-03-10') 200 '"valida":true'
Assert-Resposta $Suite 'NCM com letras é inválido' (Invoke-Hub $adm 'GET' '/hub/ncm/abc') 200 '"valida":false'
Assert-Resposta $Suite 'NCM gigante é inválido (sem erro)' (Invoke-Hub $adm 'GET' ('/hub/ncm/' + ('9' * 200))) 200 '"valida":false'
Assert-Resposta $Suite 'cClassTrib inexistente' (Invoke-Hub $adm 'GET' '/hub/classificacao/zzzzzz') 422 'não encontrado'
Assert-Resposta $Suite 'busca com 1 caractere devolve lista vazia' (Invoke-Hub $adm 'GET' '/hub/busca/ncm?q=a') 200 '[]'
Assert-Resposta $Suite 'busca: % é texto, não curinga' (Invoke-Hub $adm 'GET' '/hub/busca/ncm?q=%25%25%25') 200 '[]'
$r = Invoke-Hub $adm 'GET' '/hub/busca/ncm?q=de&limite=-1'   # "de" aparece em milhares de descrições
$qtd = @(Get-Texto $r | ConvertFrom-Json).Count
Add-Resultado $Suite 'busca: limite negativo não remove o teto de 300' ($qtd -le 300) "devolveu $qtd linhas"
Assert-Resposta $Suite 'busca com tipo inválido' (Invoke-Hub $adm 'GET' '/hub/busca/xyz?q=abc') 400 'use ncm ou nbs'
Assert-Resposta $Suite 'situação do sistema' (Invoke-Hub $adm 'GET' '/hub/status') 200 '"regimeGeral"'

# ---------------------------------------------------------------- cenário de CBS (alíquota alternativa)
# Entradas: respostas reais da calculadora oficial em 2026 (CBS de teste 0,90%). Esperado com 11,3%:
#   item 1: 000001, base 1.000,00 → 1.000 × 11,3% = 113,00
#   item 2: 200030, redução 60%, base 300,00 → efetiva 11,3 × 0,4 = 4,52% → 300 × 4,52% = 13,56
#   total CBS 126,56; IBS inalterado (1,12)
$cfg = Get-Texto (Invoke-Hub $adm 'GET' '/hub/cenario-cbs') | ConvertFrom-Json
Add-Resultado $Suite 'cenário de CBS configurado (hub.cenario-cbs)' ($cfg.disponivel -and $cfg.aliquota -gt 0) "resposta: $($cfg | ConvertTo-Json -Compress)"
$oficialRg = '{"objetos":[{"nObj":1,"estadoItem":"CALCULADO","tribCalc":{"IBSCBS":{"CST":"000","cClassTrib":"000001","gIBSCBS":{"vBC":1000.00,"gIBSUF":{"pIBSUF":0.10,"vIBSUF":1.00},"gIBSMun":{"pIBSMun":0.00,"vIBSMun":0.00},"vIBS":1.00,"gCBS":{"pCBS":0.90,"vCBS":9.00}},"memoriaCalculo":{"cbs":"A base de cálculo utilizada é de R$ 1.000,00, com alíquota de 0,90%."}}}},{"nObj":2,"estadoItem":"CALCULADO","tribCalc":{"IBSCBS":{"CST":"200","cClassTrib":"200030","gIBSCBS":{"vBC":300.00,"gIBSUF":{"pIBSUF":0.10,"gRed":{"pRedAliq":60.00,"pAliqEfet":0.04},"vIBSUF":0.12},"gIBSMun":{"pIBSMun":0.00,"vIBSMun":0.00},"vIBS":0.12,"gCBS":{"pCBS":0.90,"gRed":{"pRedAliq":60.00,"pAliqEfet":0.36},"vCBS":1.08}}}}},{"nObj":3,"estadoItem":"INCONSISTENCIA_ENTRADA","erros":[{"code":"REG-011"}]}],"total":{"tribCalc":{"IBSCBSTot":{"vBCIBSCBS":1300.00,"gIBS":{"vIBS":1.12},"gCBS":{"vDif":0.00,"vDevTrib":0.00,"vCBS":10.08,"vCredPres":0.00}}}}}'
$r = Invoke-Hub $adm 'POST' '/hub/cenario-cbs/regime-geral?data=2026-03-10' $oficialRg
$j = Get-Texto $r | ConvertFrom-Json
$i1 = $j.objetos[0].tribCalc.IBSCBS; $i2 = $j.objetos[1].tribCalc.IBSCBS
Add-Resultado $Suite 'cenário: item integral → CBS 11,3% de 1.000,00 = 113,00' (($i1.gIBSCBS.gCBS.pCBS -eq 11.3) -and ($i1.gIBSCBS.gCBS.vCBS -eq 113)) "pCBS=$($i1.gIBSCBS.gCBS.pCBS) vCBS=$($i1.gIBSCBS.gCBS.vCBS)"
Add-Resultado $Suite 'cenário: redução de 60% preservada → efetiva 4,52%, CBS 13,56' (($i2.gIBSCBS.gCBS.gRed.pAliqEfet -eq 4.52) -and ($i2.gIBSCBS.gCBS.vCBS -eq 13.56) -and ($i2.gIBSCBS.gCBS.gRed.pRedAliq -eq 60)) "efetiva=$($i2.gIBSCBS.gCBS.gRed.pAliqEfet) vCBS=$($i2.gIBSCBS.gCBS.vCBS)"
Add-Resultado $Suite 'cenário: total da CBS = 126,56' ($j.total.tribCalc.IBSCBSTot.gCBS.vCBS -eq 126.56) "total=$($j.total.tribCalc.IBSCBSTot.gCBS.vCBS)"
Add-Resultado $Suite 'cenário: IBS e base não mudam' (($j.total.tribCalc.IBSCBSTot.gIBS.vIBS -eq 1.12) -and ($i2.gIBSCBS.gIBSUF.vIBSUF -eq 0.12) -and ($i1.gIBSCBS.vBC -eq 1000)) "IBS=$($j.total.tribCalc.IBSCBSTot.gIBS.vIBS)"
Add-Resultado $Suite 'cenário: item com erro fica como veio' ($j.objetos[2].estadoItem -eq 'INCONSISTENCIA_ENTRADA' -and $j.objetos[2].erros[0].code -eq 'REG-011') ($j.objetos[2] | ConvertTo-Json -Compress -Depth 5)
Add-Resultado $Suite 'cenário: memória de cálculo indica as duas alíquotas' ($i1.memoriaCalculo.cbs -like '*11,30%*oficial 0,90%*') $i1.memoriaCalculo.cbs
Add-Resultado $Suite 'cenário: resultado identificado (rótulo, alíquota oficial, itens ajustados)' ($j.cenarioCbs.aliquotaOficial -eq 0.9 -and $j.cenarioCbs.itensAjustados -eq 2 -and $j.cenarioCbs.rotulo) ($j.cenarioCbs | ConvertTo-Json -Compress)

# pedágio: 200,00 em dois trechos (10 km e 5 km). Oficial 0,90% → 1,20 + 0,60. Cenário: 15,07 + 7,53 = 22,60
$oficialPed = '{"baseCalculo":200.00,"extensaoTotal":15,"total":{"cbsTotal":{"baseCalculo":"200.00","valorApurado":"1.80","valorDevido":"1.80","valorTributo":"1.80"},"ibsEstadualTotal":{"valorTributo":"0.20"}},"trechos":[{"baseCalculo":200.00,"cbs":{"aliquota":0.9,"aliquotaEfetiva":0.6000,"tributoCalculado":1.20},"extensaoTrecho":10,"ibsEstadual":{"aliquota":0.1,"aliquotaEfetiva":0.0667,"tributoCalculado":0.13},"numero":1},{"baseCalculo":200.00,"cbs":{"aliquota":0.9,"aliquotaEfetiva":0.3000,"tributoCalculado":0.60},"extensaoTrecho":5,"ibsEstadual":{"aliquota":0.1,"aliquotaEfetiva":0.0333,"tributoCalculado":0.07},"numero":2}]}'
$p = Get-Texto (Invoke-Hub $adm 'POST' '/hub/cenario-cbs/pedagio' $oficialPed) | ConvertFrom-Json
Add-Resultado $Suite 'cenário no pedágio: trechos 15,07 + 7,53 e total "22.60"' (($p.trechos[0].cbs.tributoCalculado -eq 15.07) -and ($p.trechos[1].cbs.tributoCalculado -eq 7.53) -and ($p.total.cbsTotal.valorTributo -eq '22.60') -and ($p.total.cbsTotal.valorDevido -eq '22.60')) "trechos=$($p.trechos[0].cbs.tributoCalculado)/$($p.trechos[1].cbs.tributoCalculado) total=$($p.total.cbsTotal.valorTributo)"
Add-Resultado $Suite 'cenário no pedágio: IBS não muda' ($p.trechos[0].ibsEstadual.tributoCalculado -eq 0.13 -and $p.total.ibsEstadualTotal.valorTributo -eq '0.20') "IBS trecho 1=$($p.trechos[0].ibsEstadual.tributoCalculado)"
Assert-Resposta $Suite 'cenário: entrada que não é a resposta da calculadora → 400' (Invoke-Hub $adm 'POST' '/hub/cenario-cbs/regime-geral?data=2026-03-10' '[1,2]') 400

# ponta a ponta: cálculo real de 2026 na calculadora oficial e reescala pelo hub
$real = Get-Texto (Invoke-Hub $adm 'POST' '/proxy/rg/calculadora/regime-geral' '{"id":"c","versao":"1.0.0","dhFatoGerador":"2026-03-10T10:00:00-03:00","municipio":3550308,"uf":"SP","tpDoc":55,"itens":[{"numero":1,"ncm":"73181500","cst":"000","cClassTrib":"000001","baseCalculo":250}]}')
$e2e = Get-Texto (Invoke-Hub $adm 'POST' '/hub/cenario-cbs/regime-geral?data=2026-03-10' $real) | ConvertFrom-Json
$g = $e2e.objetos[0].tribCalc.IBSCBS.gIBSCBS.gCBS
Add-Resultado $Suite 'cenário ponta a ponta: calculadora oficial (0,90%) + hub → 11,30% de 250,00 = 28,25' (($g.pCBS -eq 11.3) -and ($g.vCBS -eq 28.25) -and ($e2e.cenarioCbs.aliquotaOficial -eq 0.9)) "pCBS=$($g.pCBS) vCBS=$($g.vCBS) oficial=$($e2e.cenarioCbs.aliquotaOficial)"

# regressão (QA do cenário): diferimento e suspensão. Oráculo: a própria calculadora oficial com CBS 11,3%
# (aceita alíquota informada a partir de 2027). A CBS do hub em 2026 tem de bater centavo a centavo.
foreach ($caso in @(
        @{ nome = 'diferimento 510001'; item = '"ncm":"73181500","cst":"510","cClassTrib":"510001"' },
        @{ nome = 'diferimento com redução 515001'; item = '"ncm":"01022110","cst":"515","cClassTrib":"515001"' },
        @{ nome = 'suspensão 550001 (tributação regular)'; item = '"ncm":"73181500","cst":"550","cClassTrib":"550001","tributacaoRegular":{"cst":"000","cClassTrib":"000001"}' })) {
    $op26 = '{"id":"q","versao":"1.0.0","dhFatoGerador":"2026-03-10T10:00:00-03:00","municipio":3550308,"uf":"SP","tpDoc":55,"itens":[{"numero":1,' + $caso.item + ',"quantidade":1,"unidade":"UN","baseCalculo":1234.57}]}'
    $op27 = $op26.Replace('2026-03-10', '2027-03-10').Replace('"baseCalculo":1234.57', '"baseCalculo":1234.57,"aliquotasNominais":{"cbs":' + $cfg.aliquota + ',"ibsEstadual":0.1,"ibsMunicipal":0}')
    $oficial = Get-Texto (Invoke-Hub $adm 'POST' '/proxy/rg/calculadora/observabilidade/regime-geral' $op26)
    $hubG = (Get-Texto (Invoke-Hub $adm 'POST' '/hub/cenario-cbs/regime-geral?data=2026-03-10' $oficial) | ConvertFrom-Json).objetos[0].tribCalc.IBSCBS.gIBSCBS
    $calcG = (Get-Texto (Invoke-Hub $adm 'POST' '/proxy/rg/calculadora/observabilidade/regime-geral' $op27) | ConvertFrom-Json).objetos[0].tribCalc.IBSCBS.gIBSCBS
    $campos = @(@('vCBS', { param($g) $g.gCBS.vCBS }), @('vDif', { param($g) $g.gCBS.gDif.vDif }), @('pAliqEfet', { param($g) $g.gCBS.gRed.pAliqEfet }),
        @('pAliqEfetRegCBS', { param($g) $g.gTribRegular.pAliqEfetRegCBS }), @('vTribRegCBS', { param($g) $g.gTribRegular.vTribRegCBS }))
    $dif = @($campos | ? { $a = & $_[1] $hubG; $b = & $_[1] $calcG; [string]([decimal]("0$a")) -ne [string]([decimal]("0$b")) } | % { "$($_[0]): hub $(& $_[1] $hubG) × calculadora $(& $_[1] $calcG)" })
    Add-Resultado $Suite "cenário = calculadora oficial a 11,3%: $($caso.nome)" (-not $dif -and $hubG) ($dif -join '; ')
}

# regressão: compra governamental — o grupo gTribCompraGov (CBS que seria devida) também vai para o cenário
$opGov = '{"id":"g","versao":"1.0.0","dhFatoGerador":"2026-03-10T10:00:00-03:00","municipio":3550308,"uf":"SP","tpDoc":55,"gCompraGov":{"tpEnteGov":2},"itens":[{"numero":1,"ncm":"30049099","cst":"200","cClassTrib":"200030","baseCalculo":300}]}'
$gov = (Get-Texto (Invoke-Hub $adm 'POST' '/hub/cenario-cbs/regime-geral?data=2026-03-10' (Get-Texto (Invoke-Hub $adm 'POST' '/proxy/rg/calculadora/observabilidade/regime-geral' $opGov))) | ConvertFrom-Json).objetos[0].tribCalc.IBSCBS.gIBSCBS.gTribCompraGov
Add-Resultado $Suite 'cenário: compra governamental com redução de 60% → gTribCompraGov 4,52% / 13,56' ($gov.pAliqCBS -eq 4.52 -and $gov.vTribCBS -eq 13.56) "pAliqCBS=$($gov.pAliqCBS) vTribCBS=$($gov.vTribCBS)"

# regressão: arredondamento igual ao da calculadora oficial (HALF_EVEN): 6.465,00 × 11,3% = 730,545 → 730,54
$empate = Get-Texto (Invoke-Hub $adm 'POST' '/proxy/rg/calculadora/observabilidade/regime-geral' '{"id":"e","versao":"1.0.0","dhFatoGerador":"2026-03-10T10:00:00-03:00","municipio":3550308,"uf":"SP","tpDoc":55,"itens":[{"numero":1,"ncm":"73181500","cst":"000","cClassTrib":"000001","baseCalculo":6465}]}')
$vEmp = (Get-Texto (Invoke-Hub $adm 'POST' '/hub/cenario-cbs/regime-geral?data=2026-03-10' $empate) | ConvertFrom-Json).objetos[0].tribCalc.IBSCBS.gIBSCBS.gCBS.vCBS
Add-Resultado $Suite 'cenário: empate de arredondamento segue a calculadora (730,545 → 730,54)' ($vEmp -eq 730.54) "vCBS=$vEmp"

# ---------------------------------------------------------------- exportação e erros de requisição
Assert-Resposta $Suite 'Excel genérico' (Invoke-Hub $adm 'POST' '/hub/excel' '{"colunas":[{"titulo":"A"}],"linhas":[["x"]]}') 200
Assert-Resposta $Suite 'Excel sem colunas' (Invoke-Hub $adm 'POST' '/hub/excel' '{"titulo":"x"}') 400 'Informe as colunas'
Assert-Resposta $Suite 'Excel com coluna sem título' (Invoke-Hub $adm 'POST' '/hub/excel' '{"colunas":[{"tipo":"texto"}],"linhas":[]}') 200
Assert-Resposta $Suite 'tipo de conteúdo errado → 415' (Invoke-Hub $adm 'POST' '/hub/excel' 'x' 'text/plain') 415
Assert-Resposta $Suite 'método errado → 405' (Invoke-Hub $adm 'DELETE' '/hub/status') 405
$r = Invoke-Hub $adm 'POST' '/hub/excel' '{"colunas":"nao-e-lista"}'
Assert-Resposta $Suite 'JSON em formato errado → 400' $r 400 'formato inválido'
Add-Resultado $Suite 'erros não expõem nomes de classes Java' (-not ((Get-Texto $r) -match 'Exception|java\.|br\.com\.')) (Get-Texto $r)
