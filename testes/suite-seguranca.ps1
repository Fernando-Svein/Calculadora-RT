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
foreach ($arq in '/js/tema.js', '/js/login.js', '/login.html', '/css/app.css') {
    Assert-Resposta $Suite "arquivo público antes do login: $arq" (Invoke-Hub (New-SessaoHub) 'GET' $arq) 200
}
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
