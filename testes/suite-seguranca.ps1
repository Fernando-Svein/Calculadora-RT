<#
  Suíte de segurança: permissões por ferramenta (inclusive tentativas de burlar pelo endereço),
  área de administração, CSRF, sessões, isolamento dos resultados, cabeçalhos e força bruta.
  A força bruta fica por último: ela bloqueia o endereço IP de teste por 15 minutos.
#>
$Suite = 'Segurança'
$adm = Enter-Hub $UsuarioAdmin $SenhaAdmin
$lim = Enter-Hub $UsuarioLimitado $SenhaLimitado     # só tem a ferramenta "Tabela cClassTrib"
$op = '{"id":"x","versao":"1.0.0","dhFatoGerador":"2026-03-10T10:00:00-03:00","municipio":3550308,"uf":"SP","itens":[]}'

# ---------------------------------------------------------------- permissões por ferramenta
Assert-Resposta $Suite 'ferramenta liberada funciona' (Invoke-Hub $lim 'GET' '/proxy/rg/calculadora/dados-abertos/versao') 200
foreach ($caminho in '/proxy/rg/calculadora/regime-geral', '/proxy/RG/calculadora/regime-geral',
    '/proxy/regime-geral/calculadora/regime-geral', '/proxy/rg/calculadora/regime%2Dgeral',
    '/proxy/rg/calculadora/regime-geral/', '/proxy/Rg/calculadora/pedagio') {
    Assert-Resposta $Suite "ferramenta não liberada bloqueada: POST $caminho" (Invoke-Hub $lim 'POST' $caminho $op) 403
}
Assert-Resposta $Suite 'lote não liberado bloqueado' (Invoke-Hub $lim 'POST' '/hub/lote/classificacao' $null) 403
Assert-Resposta $Suite 'área de administração bloqueada para usuário comum' (Invoke-Hub $lim 'GET' '/admin/usuarios') 403
Assert-Resposta $Suite 'API sem login → 401' (Invoke-Hub (New-SessaoHub) 'GET' '/hub/status') 401
$r = Invoke-Hub (New-SessaoHub) 'GET' '/index.html' -Cabecalhos @{ Accept = 'text/html' }
Add-Resultado $Suite 'tela sem login redireciona para o login' ((Get-Status $r) -eq 302 -and "$($r.Headers.Location)" -like '*login.html') "HTTP $(Get-Status $r) $($r.Headers.Location)"

# ---------------------------------------------------------------- CSRF
Assert-Resposta $Suite 'POST sem token CSRF → 403' (Invoke-Hub $adm 'POST' '/admin/usuarios/999/redefinir-senha' -SemToken) 403
Assert-Resposta $Suite 'POST vindo de outro site, sem token → 403' (Invoke-Hub $adm 'POST' '/admin/usuarios/999/redefinir-senha' -SemToken `
        -Cabecalhos @{ 'Sec-Fetch-Site' = 'cross-site'; Origin = 'http://site-malicioso.example' }) 403
Assert-Resposta $Suite 'POST com token errado → 403' (Invoke-Hub $adm 'POST' '/admin/usuarios/999/redefinir-senha' -SemToken `
        -Cabecalhos @{ 'X-XSRF-TOKEN' = 'token-falso' }) 403

# ---------------------------------------------------------------- cabeçalhos de segurança
$r = Invoke-Hub $adm 'GET' '/index.html'
foreach ($par in @(@('Content-Security-Policy', "script-src 'self'"), @('X-Frame-Options', 'SAMEORIGIN'),
        @('X-Content-Type-Options', 'nosniff'), @('Referrer-Policy', 'same-origin'), @('Permissions-Policy', 'camera=()'))) {
    $v = if ($r.Headers.Contains($par[0])) { $r.Headers.GetValues($par[0]) -join ', ' } else { '' }
    Add-Resultado $Suite "cabeçalho $($par[0])" ($v.Contains($par[1])) "valor: '$v'"
}
foreach ($arq in '/js/tema.js', '/js/login.js', '/js/marca.js', '/login.html', '/css/app.css') {
    Assert-Resposta $Suite "arquivo público antes do login: $arq" (Invoke-Hub (New-SessaoHub) 'GET' $arq) 200
}

# ---------------------------------------------------------------- logo do escritório (envio só do administrador)
$anon = New-SessaoHub
Assert-Resposta $Suite 'logo: estado público antes do login, sem logo' (Invoke-Hub $anon 'GET' '/marca') 200 '"logo":false'
Assert-Resposta $Suite 'logo: sem logo cadastrada → 404' (Invoke-Hub $anon 'GET' '/marca/logo') 404
Assert-Resposta $Suite 'logo: usuário comum não pode enviar' (Send-Arquivos $lim '/admin/marca/logo' @((Join-Path $Dados 'logo-teste.png')) 'arquivo') 403
Assert-Resposta $Suite 'logo: envio sem token CSRF → 403' (Invoke-Hub $adm 'POST' '/admin/marca/logo' -SemToken) 403
Assert-Resposta $Suite 'logo: SVG (pode conter script) é recusado' (Send-Arquivos $adm '/admin/marca/logo' @((Join-Path $Dados 'logo-script.svg')) 'arquivo') 422 'Formato não aceito'
Assert-Resposta $Suite 'logo: texto com extensão .png é recusado' (Send-Arquivos $adm '/admin/marca/logo' @((Join-Path $Dados 'logo-falsa.png')) 'arquivo') 422 'Formato não aceito'
Assert-Resposta $Suite 'logo: PNG corrompido é recusado' (Send-Arquivos $adm '/admin/marca/logo' @((Join-Path $Dados 'logo-corrompida.png')) 'arquivo') 422 'Não foi possível ler'
$grande = Join-Path ([IO.Path]::GetTempPath()) 'logo-grande-teste.png'
[IO.File]::WriteAllBytes($grande, [byte[]](0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + (New-Object byte[] (2200 * 1024)))
Assert-Resposta $Suite 'logo: arquivo acima de 2 MB é recusado' (Send-Arquivos $adm '/admin/marca/logo' @($grande) 'arquivo') 422 'mais de 2 MB'
Remove-Item $grande -Force
Assert-Resposta $Suite 'logo: PNG válido é aceito' (Send-Arquivos $adm '/admin/marca/logo' @((Join-Path $Dados 'logo-teste.png')) 'arquivo') 200 '"logo":true'
$r = Invoke-Hub $anon 'GET' '/marca/logo'
$b = $r.Content.ReadAsByteArrayAsync().Result
$largura = if ($b.Length -gt 24) { ([int]$b[16] -shl 24) + ([int]$b[17] -shl 16) + ([int]$b[18] -shl 8) + [int]$b[19] } else { 0 }
Add-Resultado $Suite 'logo: servida antes do login como PNG, com nosniff' ((Get-Status $r) -eq 200 -and "$($r.Content.Headers.ContentType)" -eq 'image/png' -and
    ($r.Headers.GetValues('X-Content-Type-Options') -join '') -eq 'nosniff') "HTTP $(Get-Status $r) $($r.Content.Headers.ContentType)"
Add-Resultado $Suite 'logo: imagem regravada e reduzida (1200 px → 640 px)' ($largura -eq 640) "largura guardada: $largura"
Assert-Resposta $Suite 'logo: usuário comum não pode remover' (Invoke-Hub $lim 'DELETE' '/admin/marca/logo') 403
Assert-Resposta $Suite 'logo: JPEG é aceito' (Send-Arquivos $adm '/admin/marca/logo' @((Join-Path $Dados 'logo-teste.jpg')) 'arquivo') 200 '"logo":true'
$b = (Invoke-Hub $anon 'GET' '/marca/logo').Content.ReadAsByteArrayAsync().Result
Add-Resultado $Suite 'logo: JPEG convertido para PNG' ($b.Length -gt 8 -and $b[0] -eq 0x89 -and $b[1] -eq 0x50) "primeiros bytes: $($b[0..3] -join ' ')"
Assert-Resposta $Suite 'logo: administrador remove' (Invoke-Hub $adm 'DELETE' '/admin/marca/logo') 200 '"logo":false'
Assert-Resposta $Suite 'logo: depois de removida → 404' (Invoke-Hub $anon 'GET' '/marca/logo') 404
Assert-Resposta $Suite 'demais scripts exigem login' (Invoke-Hub (New-SessaoHub) 'GET' '/js/app.js') 401

# ---------------------------------------------------------------- isolamento dos resultados de lote
$r = Send-Arquivos $adm '/hub/lote/classificacao' @((Join-Path $Dados 'nfe-ibscbs.xml'))
$id = (Get-Texto $r | ConvertFrom-Json).id
$outro = Enter-Hub $UsuarioCompleto $SenhaCompleto
Assert-Resposta $Suite 'dono baixa o Excel do próprio resultado' (Invoke-Hub $adm 'GET' "/hub/lote/classificacao/$id/excel") 200
Assert-Resposta $Suite 'outro usuário não baixa o resultado alheio' (Invoke-Hub $outro 'GET' "/hub/lote/classificacao/$id/excel") 422

# ---------------------------------------------------------------- sessões
$pcA = Enter-Hub $UsuarioCompleto $SenhaCompleto
$pcB = Enter-Hub $UsuarioCompleto $SenhaCompleto
$novaSenha = 'Nova' + (Get-Random -Minimum 100000 -Maximum 999999)
Assert-Resposta $Suite 'troca da própria senha' (Invoke-Hub $pcA 'POST' '/auth/senha' (@{ senhaAtual = $SenhaCompleto; novaSenha = $novaSenha } | ConvertTo-Json -Compress)) 204
Assert-Resposta $Suite 'sessão que trocou a senha continua' (Invoke-Hub $pcA 'GET' '/hub/status') 200
Assert-Resposta $Suite 'outras sessões com a senha antiga são encerradas' (Invoke-Hub $pcB 'GET' '/hub/status') 401
$script:SenhaCompleto = $novaSenha
$r = Invoke-Hub $adm 'PUT' '/admin/usuarios/999999' '{"nome":"x","perfil":"USUARIO","ativo":true}'
Assert-Resposta $Suite 'alterar usuário inexistente' $r 422

# ---------------------------------------------------------------- força bruta (por último)
$s = New-SessaoHub
$ult = $null
foreach ($i in 1..6) { $ult = Invoke-Hub $s 'POST' '/auth/login' (@{ usuario = $UsuarioLimitado; senha = "errada$i" } | ConvertTo-Json -Compress) }
Assert-Resposta $Suite '5 senhas erradas bloqueiam o usuário' $ult 429
Assert-Resposta $Suite 'senha certa não entra durante o bloqueio' (Invoke-Hub $s 'POST' '/auth/login' (@{ usuario = $UsuarioLimitado; senha = $SenhaLimitado } | ConvertTo-Json -Compress)) 429
$bloqueouEm = $null
foreach ($i in 1..25) {
    $r = Invoke-Hub $s 'POST' '/auth/login' (@{ usuario = "inexistente$i"; senha = 'Senha123' } | ConvertTo-Json -Compress)
    if ((Get-Status $r) -eq 429) { $bloqueouEm = $i; break }
}
Add-Resultado $Suite 'erros em vários usuários bloqueiam o computador (password spraying)' ($null -ne $bloqueouEm) `
    "nenhum bloqueio em 25 tentativas"
