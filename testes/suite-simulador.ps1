<#
  Suíte do simulador e do auditor: importação de XML/SPED no Simulador do Regime Regular (com cClassTrib
  sugerido quando o documento não traz), consulta de CNPJ (API simulada por testes\mock-cnpj.ps1) e auditoria
  com a CBS calculada lado a lado na alíquota oficial (0,9% em 2026) e no cenário TCU (11,3%).
#>
$Suite = 'Simulador'
$adm = Enter-Hub $UsuarioAdmin $SenhaAdmin
$limS = Enter-Hub $UsuarioLimitado $SenhaLimitado
function Get-Json($r) { Get-Texto $r | ConvertFrom-Json }

# ---------------------------------------------------------------- importação no simulador
$r = Send-Arquivos $adm '/hub/simulador/importar' @((Join-Path $Dados 'nfe-ibscbs.xml'))
$j = Get-Json $r; $op = $j.operacoes[0]
Add-Resultado $Suite 'importar NF-e: data, município, UF e documento da nota' ((Get-Status $r) -eq 200 -and $op.dataHora -like '2026-03-10T10:00*' -and $op.municipio -eq 3550308 -and $op.uf -eq 'SP' -and $op.tpDoc -eq 55) (Get-Texto $r)
$i1 = $op.itens[0]
Add-Resultado $Suite 'importar NF-e: NCM, CST e cClassTrib lidos do XML (origem DOCUMENTO)' ($i1.ncm -eq '10063021' -and $i1.cst -eq '200' -and $i1.cClassTrib -eq '200003' -and $i1.origemClassificacao -eq 'DOCUMENTO' -and $i1.baseCalculo -eq 100) ($i1 | ConvertTo-Json -Compress)
$i2 = $op.itens[1]
Add-Resultado $Suite 'importar NF-e: item com NCM inválido fica sem classificação e explica' ($i2.origemClassificacao -eq 'SEM_CLASSIFICACAO' -and -not $i2.cClassTrib -and $i2.motivo) ($i2 | ConvertTo-Json -Compress)

$r = Send-Arquivos $adm '/hub/simulador/importar' @((Join-Path $Dados 'empresa-nfe-simples.xml'))
$op = (Get-Json $r).operacoes[0]; $it = $op.itens[0]
Add-Resultado $Suite 'XML sem cClassTrib: classificação sugerida pelo NCM e marcada como SUGERIDO' ($it.origemClassificacao -eq 'SUGERIDO' -and $it.cClassTrib -match '^\d{6}$' -and $it.cst -eq $it.cClassTrib.Substring(0, 3) -and $it.motivo -like '*NCM 19059090*') ($it | ConvertTo-Json -Compress)
Add-Resultado $Suite 'XML sem cClassTrib: aviso de itens sugeridos e de emitente do Simples' (($op.avisos -join ' ') -like '*sugerida*' -and ($op.avisos -join ' ') -like '*Simples*') ($op.avisos -join ' | ')

$r = Send-Arquivos $adm '/hub/simulador/importar' @((Join-Path $Dados 'empresa-sped-contribuicoes.txt'))
$op = (Get-Json $r).operacoes[0]; $it = $op.itens[0]
Add-Resultado $Suite 'importar SPED: contribuinte, UF/município do 0000 e itens do 0200 com sugestão' ($op.origem -eq 'EFD Contribuições' -and $op.uf -eq 'RJ' -and $op.municipio -eq 3304557 -and $it.ncm -eq '10063021' -and $it.origemClassificacao -eq 'SUGERIDO') ($op | ConvertTo-Json -Depth 4 -Compress)

$r = Send-Arquivos $adm '/hub/simulador/importar' @((Join-Path $Dados 'nfe-ibscbs.xml'), (Join-Path $Dados 'empresa-nfe-simples.xml'), (Join-Path $Dados 'empresa-sped-contribuicoes.txt'))
Add-Resultado $Suite 'vários arquivos: uma operação por documento (a tela escolhe qual simular)' (@((Get-Json $r).operacoes).Count -eq 3) ''
Assert-Resposta $Suite 'importar: usuário sem o Simulador Regime Regular não pode' (Send-Arquivos $limS '/hub/simulador/importar' @((Join-Path $Dados 'nfe-ibscbs.xml'))) 403
Assert-Resposta $Suite 'importar: arquivo inválido é explicado' (Send-Arquivos $adm '/hub/simulador/importar' @((Join-Path $Dados 'xml-quebrado.xml'))) 422 'XML inválido'

# o item sugerido é aceito pela calculadora oficial (operação montada como a tela monta)
$sug = (Get-Json (Send-Arquivos $adm '/hub/simulador/importar' @((Join-Path $Dados 'empresa-nfe-simples.xml')))).operacoes[0].itens[0]
$opCalc = @{ id = 't-imp'; versao = '1.0.0'; dhFatoGerador = '2026-03-10T10:00:00-03:00'; municipio = 3106200; uf = 'MG'; tpDoc = 55
    itens = @(@{ numero = 1; ncm = $sug.ncm; cst = $sug.cst; cClassTrib = $sug.cClassTrib; quantidade = 2; unidade = 'KG'; baseCalculo = 30 }) } | ConvertTo-Json -Depth 4 -Compress
Assert-Resposta $Suite 'classificação sugerida é aceita pela calculadora oficial' (Invoke-Hub $adm 'POST' '/proxy/rg/calculadora/regime-geral' $opCalc) 200 '"vBC"'

# ---------------------------------------------------------------- consulta de CNPJ (API simulada)
$mockUrl = "http://127.0.0.1:$PortaMockCnpj"
Assert-Resposta $Suite 'CNPJ: consulta disponível' (Invoke-Hub $adm 'GET' '/hub/empresas/consulta-cnpj') 200 '"disponivel":true'
$r = Invoke-Hub $adm 'GET' '/hub/empresas/consulta-cnpj/11.222.333.0001-81'
$c = Get-Json $r
Add-Resultado $Suite 'CNPJ: cadastro completo normalizado (razão, fantasia, situação, CNAE, endereço)' ((Get-Status $r) -eq 200 -and $c.razaoSocial -eq 'COMERCIAL EXEMPLO LTDA' -and $c.nomeFantasia -eq 'EXEMPLO ATACADO' -and $c.situacaoCadastral -eq 'ATIVA' -and "$($c.cnaePrincipal.codigo)" -eq '4639701' -and $c.endereco.codigoMunicipioIbge -eq 3304557 -and $c.endereco.logradouro -eq 'RUA DAS FLORES') (Get-Texto $r)
Add-Resultado $Suite 'CNPJ: quadro societário (QSA) com 2 sócios' (@($c.qsa).Count -eq 2 -and $c.qsa[0].nome -eq 'MARIA DA SILVA' -and $c.qsa[0].qualificacao -eq 'Sócio-Administrador') ($c.qsa | ConvertTo-Json -Compress)
Add-Resultado $Suite 'CNPJ: regime do ano mais recente (2025, Lucro Presumido) e CNAE secundário vazio descartado' ($c.regimeTributario.ano -eq 2025 -and $c.regimeSugerido.regime -eq 'NORMAL' -and $c.regimeSugerido.regimeApuracao -eq 'LUCRO_PRESUMIDO' -and @($c.cnaesSecundarios).Count -eq 1) ($c | ConvertTo-Json -Depth 4 -Compress)
Invoke-Hub $adm 'GET' '/hub/empresas/consulta-cnpj/11222333000181' | Out-Null
$n = (Invoke-RestMethod "$mockUrl/contagem/11222333000181").consultas
Add-Resultado $Suite 'CNPJ: segunda consulta vem do cache (1 chamada ao serviço)' ($n -eq 1) "chamadas ao serviço: $n"
Assert-Resposta $Suite 'CNPJ inexistente: 404 com mensagem clara' (Invoke-Hub $adm 'GET' '/hub/empresas/consulta-cnpj/45678912000155') 404 'não encontrado'
Assert-Resposta $Suite 'CNPJ: limite de requisições do serviço vira 429 com orientação' (Invoke-Hub $adm 'GET' '/hub/empresas/consulta-cnpj/98765432000198') 429 'Limite de consultas'
Assert-Resposta $Suite 'CNPJ: serviço com erro vira 502 sem travar o cadastro manual' (Invoke-Hub $adm 'GET' '/hub/empresas/consulta-cnpj/12345678000195') 502 'manualmente'
Assert-Resposta $Suite 'CNPJ inválido nem chega ao serviço' (Invoke-Hub $adm 'GET' '/hub/empresas/consulta-cnpj/11222333000182') 422 'CNPJ inválido'
Add-Resultado $Suite 'CNPJ inválido: nenhuma chamada ao serviço' ((Invoke-RestMethod "$mockUrl/contagem/11222333000182").consultas -eq 0) ''
Assert-Resposta $Suite 'CNPJ: usuário sem a ferramenta Empresas não consulta' (Invoke-Hub $limS 'GET' '/hub/empresas/consulta-cnpj/11222333000181') 403 'SEM_PERMISSAO'

$emp = (Get-Texto (Invoke-Hub $adm 'GET' '/hub/empresas') | ConvertFrom-Json) | ForEach-Object { $_ } | Where-Object cnpj -eq '11222333000181'
$corpo = @{ razaoSocial = $c.razaoSocial; nomeFantasia = $c.nomeFantasia; uf = 'RJ'; municipio = 3304557; regime = 'NORMAL'; regimeApuracao = 'LUCRO_PRESUMIDO'; dadosCadastrais = $c } | ConvertTo-Json -Depth 6 -Compress
$r = Invoke-Hub $adm 'PUT' "/hub/empresas/$($emp.id)" $corpo
$e = Get-Json $r
Add-Resultado $Suite 'CNPJ: dados públicos e QSA gravados no cadastro da empresa' ((Get-Status $r) -eq 200 -and @($e.dadosCadastrais.qsa).Count -eq 2 -and $e.dadosCadastrais.situacaoCadastral -eq 'ATIVA') (Get-Texto $r)
$outro = $corpo.Replace('"cnpj":"11222333000181"', '"cnpj":"45678912000155"')
Assert-Resposta $Suite 'CNPJ: dados de outro CNPJ não são gravados' (Invoke-Hub $adm 'PUT' "/hub/empresas/$($emp.id)" $outro) 422 'outro CNPJ'

# serviço reserva: o principal responde 503 (como a BrasilAPI faz com alguns CNPJs) e o seguinte da lista atende
$r = Invoke-Hub $adm 'GET' '/hub/empresas/consulta-cnpj/33999888000177'
$cont = Invoke-RestMethod "$mockUrl/contagem/33999888000177"
Add-Resultado $Suite 'CNPJ: principal com 503 → consulta atendida pelo serviço reserva' ((Get-Status $r) -eq 200 -and (Get-Json $r).razaoSocial -eq 'COMERCIAL EXEMPLO LTDA' -and $cont.consultas -eq 1 -and $cont.reserva -eq 1) "HTTP $(Get-Status $r), principal=$($cont.consultas), reserva=$($cont.reserva)"
Add-Resultado $Suite 'CNPJ inexistente: os dois serviços consultados antes de responder 404' ((Invoke-RestMethod "$mockUrl/contagem/45678912000155").reserva -ge 1) ''

# importação no simulador também pergunta sobre as empresas novas (nada é cadastrado sozinho)
$antesSim = @((Get-Texto (Invoke-Hub $adm 'GET' '/hub/empresas') | ConvertFrom-Json) | ForEach-Object { $_ }).Count
$j = Get-Json (Send-Arquivos $adm '/hub/simulador/importar' @((Join-Path $Dados 'nfe-auditoria-cenario.xml')))
$depoisSim = @((Get-Texto (Invoke-Hub $adm 'GET' '/hub/empresas') | ConvertFrom-Json) | ForEach-Object { $_ }).Count
Add-Resultado $Suite 'importar no simulador: devolve as empresas encontradas sem cadastrar' ($j.empresasEncontradas -and $antesSim -eq $depoisSim) "token=$($j.empresasEncontradas), antes=$antesSim, depois=$depoisSim"
# ---------------------------------------------------------------- auditor: CBS com 0,9% (oficial) e 11,3% (cenário TCU)
$r = Send-Arquivos $adm '/hub/lote/auditoria' @((Join-Path $Dados 'nfe-auditoria-cenario.xml'))
$a = Get-Json $r
$l = @{}; $a.linhas | ForEach-Object { $l[[int]$_.nItem] = $_ }
Add-Resultado $Suite 'auditor: cenário informado na resposta (Cenário TCU, 11,3%)' ($a.cenarioCbs.rotulo -eq 'Cenário TCU' -and $a.cenarioCbs.aliquota -eq 11.3) ($a.cenarioCbs | ConvertTo-Json -Compress)
Add-Resultado $Suite 'auditor: R$ 1.000 → CBS 9,00 (0,9%) e 113,00 (11,3%), status pela oficial' ($l[1].calcVCBS -eq 9 -and $l[1].calcPCBS -eq 0.9 -and $l[1].cenVCBS -eq 113 -and $l[1].cenPCBS -eq 11.3 -and $l[1].status -eq 'OK') ($l[1] | Select-Object status, calcPCBS, calcVCBS, cenPCBS, cenVCBS | ConvertTo-Json -Compress)
Add-Resultado $Suite 'auditor: redução de 60% (R$ 300) → 1,08 e 13,56' ($l[2].calcVCBS -eq 1.08 -and $l[2].cenVCBS -eq 13.56 -and $l[2].status -eq 'OK') ($l[2] | Select-Object status, calcVCBS, cenVCBS, mensagens | ConvertTo-Json -Compress)
Add-Resultado $Suite 'auditor: empate de arredondamento (R$ 6.465) → 58,18 e 730,54 (meio para o par)' ($l[3].calcVCBS -eq 58.18 -and $l[3].cenVCBS -eq 730.54) ($l[3] | Select-Object calcVCBS, cenVCBS | ConvertTo-Json -Compress)
Add-Resultado $Suite 'auditor: totais lado a lado (68,26 oficial × 857,10 cenário) e IBS igual' ($a.totais.calcVCBS -eq 68.26 -and $a.totais.cenVCBS -eq 857.10 -and $a.totais.calcVIBS -eq $a.totais.xmlVIBS) ($a.totais | ConvertTo-Json -Compress)
$x = Invoke-Hub $adm 'GET' "/hub/lote/auditoria/$($a.id)/excel"
$tmpX = Join-Path ([IO.Path]::GetTempPath()) "auditoria-cenario-$PID.xlsx"; [IO.File]::WriteAllBytes($tmpX, $x.Content.ReadAsByteArrayAsync().Result)
$z = [IO.Compression.ZipFile]::OpenRead($tmpX); $strings = ($z.Entries | Where-Object FullName -like 'xl/*.xml' | ForEach-Object { $sr = New-Object IO.StreamReader($_.Open()); $sr.ReadToEnd(); $sr.Dispose() }) -join "`n"; $z.Dispose(); Remove-Item $tmpX
Add-Resultado $Suite 'auditor: planilha com as duas colunas de CBS (oficial e Cenário TCU)' ($strings -like '*vCBS calc. (oficial)*' -and $strings -like '*vCBS Cenário TCU*' -and $strings -like '*CBS calculada – Cenário TCU (11,3%)*') ''

# ---------------------------------------------------------------- vendas por NCM (classificação e auditoria)
$nfeCen = Join-Path $Dados 'nfe-auditoria-cenario.xml'
$v = (Get-Json (Send-Arquivos $adm '/hub/lote/auditoria' @($nfeCen))).vendasPorNcm
$l7318 = $v.linhas | Where-Object ncm -eq '73181500'
Add-Resultado $Suite 'vendas por NCM (auditoria): soma por NCM, % da empresa e total' ($v.itens -eq 3 -and $v.valorLiquido -eq 7765 -and $l7318.itens -eq 2 -and $l7318.valorLiquido -eq 7465 -and $l7318.percentual -eq 96.14 -and $l7318.descricao) ($v | ConvertTo-Json -Depth 4 -Compress)
# SPED com a mesma nota do XML (chave igual), uma venda só no SPED, uma entrada, uma transferência e uma bonificação
$chave = '35260355123456000130550010000000771000000771'
$spedV = @"
|0000|017|0|01032026|31032026|COMERCIAL EXEMPLO LTDA|55123456000130||SP|123|3550308|||A|1|
|0200|A1|PARAFUSO|||UN|00|73181500||10|||
|0200|R1|ARROZ|||KG|00|10063021||10|||
|C100|1|0||55|00|1|77|$chave|10032026|10032026|1000,00|
|C170|1|A1||10,000|UN|1000,00|0,00|0|000|5102|001|
|C100|1|0||55|00|1|90|35260355123456000130550010000000901000000901|10032026|10032026|50,00|
|C170|1|R1||10,000|KG|50,00|5,00|0|000|5102|001|
|C170|2|R1||1,000|KG|20,00|0,00|0|000|5152|001|
|C170|3|R1||1,000|KG|7,00|0,00|0|000|5910|001|
|C100|0|1||55|00|1|91|35260311222333000181550010000000911000000911|10032026|10032026|300,00|
|C170|1|R1||30,000|KG|300,00|0,00|0|000|1102|001|
|9999|11|
"@
$arqV = Join-Path $tmp 'sped-vendas.txt'; [IO.File]::WriteAllText($arqV, $spedV, [Text.Encoding]::GetEncoding(28591))
$c = Get-Json (Send-Arquivos $adm '/hub/lote/classificacao?data=2026-03-10' @($nfeCen, $arqV))
$v = $c.vendasPorNcm; $arroz = $v.linhas | Where-Object ncm -eq '10063021'
Add-Resultado $Suite 'vendas por NCM: venda só do SPED entra com desconto (50,00 − 5,00)' ($arroz.itens -eq 1 -and $arroz.valorBruto -eq 50 -and $arroz.descontos -eq 5 -and $arroz.valorLiquido -eq 45 -and $arroz.quantidade -eq 10 -and $arroz.unidade -eq 'KG') ($arroz | ConvertTo-Json -Compress)
Add-Resultado $Suite 'vendas por NCM: nota no SPED e no XML conta uma vez; entrada, transferência e bonificação ficam de fora' ($v.itens -eq 4 -and $v.valorLiquido -eq 7810 -and @($v.empresas).Count -eq 1 -and ($v.observacoes -join ' ') -like '*1 item(ns) de entrada*' -and ($v.observacoes -join ' ') -like '*2 item(ns) de outras saídas*' -and ($v.observacoes -join ' ') -like '*1 item(ns) do SPED já constavam*') (($v.observacoes -join ' | ') + " | total=$($v.valorLiquido) itens=$($v.itens)")
$x = Invoke-Hub $adm 'GET' "/hub/lote/classificacao/$($c.id)/excel"
$tmpV = Join-Path ([IO.Path]::GetTempPath()) "vendas-$PID.xlsx"; [IO.File]::WriteAllBytes($tmpV, $x.Content.ReadAsByteArrayAsync().Result)
$z = [IO.Compression.ZipFile]::OpenRead($tmpV); $xmlV = ($z.Entries | Where-Object FullName -like 'xl/*.xml' | ForEach-Object { $sr = New-Object IO.StreamReader($_.Open()); $sr.ReadToEnd(); $sr.Dispose() }) -join "`n"; $z.Dispose(); Remove-Item $tmpV
Add-Resultado $Suite 'vendas por NCM: aba na planilha com o total geral' ($xmlV -like '*Vendas por NCM*' -and $xmlV -like '*TOTAL GERAL*' -and $xmlV -like '*Total vendido (líquido)*') ''

# ---------------------------------------------------------------- classificação conferida na calculadora oficial
# um item por situação; os códigos de erro são os que a calculadora oficial devolve (REG-011, 006, 005, 029)
$r = Send-Arquivos $adm '/hub/lote/classificacao?data=2026-03-10' @((Join-Path $Dados 'nfe-classificacao-conferencia.xml'))
$cf = Get-Json $r; $li = @{}; $cf.linhas | ForEach-Object { $li[$_.codigo] = $_ }
foreach ($caso in @(
        @('C1', 'CONFERIDO', 'Aceita', 'cClassTrib correto'),
        @('C2', 'DIVERGENTE', 'Rejeitada: REG-011', 'NCM fora do anexo'),
        @('C3', 'DIVERGENTE', 'Rejeitada: REG-006', 'CST incompatível'),
        @('C4', 'DIVERGENTE', 'Rejeitada: REG-005', 'cClassTrib inexistente'),
        @('C5', 'DIVERGENTE', 'Rejeitada: REG-029', 'cClassTrib não admitido na NF-e'),
        @('C6', 'REVISAR', 'Aceita', 'benefício não aplicado'),
        @('C7', 'CONFERIDO', 'Aceita', 'tributação integral correta'))) {
    $l = $li[$caso[0]]
    Add-Resultado $Suite "conferência oficial: $($caso[3]) → $($caso[1]), calculadora '$($caso[2])'" ($l.status -eq $caso[1] -and "$($l.oficialAtual)".StartsWith($caso[2]) -and $l.oficialSugerido -eq 'Aceita' -and ([bool]$l.revisar -eq ($caso[1] -ne 'CONFERIDO'))) ($l | Select-Object status, oficialAtual, oficialSugerido, revisar | ConvertTo-Json -Compress)
}
Add-Resultado $Suite 'conferência oficial: resumo com aceitas e rejeitadas' ($cf.conferenciaOficial -like '*5 aceita(s), 4 rejeitada(s), 0 não conferida(s)*') $cf.conferenciaOficial
$x = Invoke-Hub $adm 'GET' "/hub/lote/classificacao/$($cf.id)/excel"
$tmpC = Join-Path ([IO.Path]::GetTempPath()) "conferencia-$PID.xlsx"; [IO.File]::WriteAllBytes($tmpC, $x.Content.ReadAsByteArrayAsync().Result)
$z = [IO.Compression.ZipFile]::OpenRead($tmpC); $xmlC = ($z.Entries | Where-Object FullName -like 'xl/*.xml' | ForEach-Object { $sr = New-Object IO.StreamReader($_.Open()); $sr.ReadToEnd(); $sr.Dispose() }) -join "`n"; $z.Dispose(); Remove-Item $tmpC
Add-Resultado $Suite 'conferência oficial: planilha com "O que revisar" e "Calculadora oficial", sem as colunas a preencher' ($xmlC -like '*O que revisar*' -and $xmlC -like '*Calculadora oficial (atual)*' -and $xmlC -like '*REG-011*' -and $xmlC -notlike '*(preencher)*' -and $xmlC -notlike '*Ocorrências*') ''