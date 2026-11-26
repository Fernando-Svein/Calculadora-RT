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
