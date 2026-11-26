<#
  Simulação (mock) da API pública de CNPJ para os testes: responde em http://127.0.0.1:<Porta>/cnpj/<cnpj>
  com o mesmo formato da BrasilAPI, sem acessar a internet. Usa TcpListener (não exige administrador).

    11222333000181  200 – cadastro completo, com quadro societário (testes\dados\cnpj-11222333000181.json)
    45678912000155  404 – CNPJ inexistente
    98765432000198  429 – limite de requisições
    12345678000195  500 – serviço com erro
    /contagem/<cnpj>      quantas consultas chegaram para o CNPJ (confere o cache do hub)
#>
param([int]$Porta = 8093, [string]$Dados)
$contagem = @{}
$ouvinte = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $Porta)
$ouvinte.Start()
try {
    while ($true) {
        $cli = $ouvinte.AcceptTcpClient()
        try {
            $fluxo = $cli.GetStream()
            $leitor = New-Object IO.StreamReader($fluxo, [Text.Encoding]::ASCII, $false, 1024, $true)
            $linha = $leitor.ReadLine()
            while (($h = $leitor.ReadLine()) -ne $null -and $h -ne '') { }
            $caminho = if ($linha -match '^\w+ (\S+)') { $Matches[1] } else { '/' }
            $status = 404; $corpo = '{"message":"Recurso não encontrado.","type":"not_found"}'
            if ($caminho -match '^/contagem/(\w+)$') {
                $status = 200; $corpo = '{"consultas":' + [int]$contagem[$Matches[1]] + '}'
            } elseif ($caminho -match '^/cnpj/(\w+)$') {
                $c = $Matches[1]; $contagem[$c] = [int]$contagem[$c] + 1
                switch ($c) {
                    '11222333000181' { $status = 200; $corpo = [IO.File]::ReadAllText((Join-Path $Dados 'cnpj-11222333000181.json'), [Text.Encoding]::UTF8) }
                    '98765432000198' { $status = 429; $corpo = '{"message":"Too many requests"}' }
                    '12345678000195' { $status = 500; $corpo = '{"message":"Erro interno"}' }
                    default { $status = 404; $corpo = '{"message":"CNPJ ' + $c + ' não encontrado.","type":"not_found","name":"NotFoundError"}' }
                }
            }
            $bytes = [Text.Encoding]::UTF8.GetBytes($corpo)
            $frase = @{ 200 = 'OK'; 404 = 'Not Found'; 429 = 'Too Many Requests'; 500 = 'Internal Server Error' }[$status]
            $cab = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 $status $frase`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n")
            $fluxo.Write($cab, 0, $cab.Length); $fluxo.Write($bytes, 0, $bytes.Length); $fluxo.Flush()
        } catch { } finally { $cli.Close() }
    }
} finally { $ouvinte.Stop() }
