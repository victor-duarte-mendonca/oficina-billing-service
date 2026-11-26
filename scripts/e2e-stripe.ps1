<#
.SYNOPSIS
  Fluxo completo do Billing com a Stripe de verdade (Pix em modo de teste), tudo local:
  GenerateBudget -> aprovacao pelo link -> RequestPayment (Pix) -> "Simulate scan" -> webhook -> estorno.

.DESCRIPTION
  Sobe PostgreSQL + LocalStack + Billing (docker compose, projeto proprio) com PAYMENT_PROVIDER=stripe, encaminha os
  eventos da Stripe com `stripe listen` e injeta os comandos da Saga na fila, como o OS faria. A unica etapa manual e
  pagar o Pix na pagina de teste da Stripe ("Simulate scan"). Nada e impresso do que e segredo.

  Pre-requisitos: Docker Desktop ligado; Stripe CLI logado (`stripe login`); chave de teste em $env:STRIPE_API_KEY ou
  no SSM (/oficina/<env>/stripe_api_key, exige sessao AWS ativa).

.PARAMETER Amount     Valor do pagamento em reais (padrao 10.00; minimo da Stripe: 0.50).
.PARAMETER SsmEnv     Ambiente do parametro SSM com a chave (padrao hml).
.PARAMETER SkipBuild  Nao reconstroi a imagem do Billing.
.PARAMETER NoStripeKey  Teste a seco: sobe sem chave e para depois do RequestPayment (espera PaymentRequestFailed).
.PARAMETER Keep       Nao para os containers no fim.

.EXAMPLE
  .\scripts\e2e-stripe.ps1
#>
[CmdletBinding()]
param(
    [string]$Amount = "10.00",
    [string]$SsmEnv = "hml",
    [switch]$SkipBuild,
    [switch]$NoStripeKey,
    [switch]$Keep
)

# Continue: no PowerShell 5.1 qualquer linha em stderr de um programa nativo viraria erro fatal; os passos falham via Step.
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
$Project = "billing-stripe-e2e"
$Port = 8083
$Results = New-Object System.Collections.Generic.List[object]
$ListenProcess = $null
$Tmp = Join-Path ([System.IO.Path]::GetTempPath()) "billing-e2e"
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null

function Step([string]$Name, [bool]$Ok, [string]$Detail = "") {
    $Results.Add([pscustomobject]@{ Passo = $Name; Resultado = $(if ($Ok) { "OK" } else { "FALHOU" }); Detalhe = $Detail })
    $color = if ($Ok) { "Green" } else { "Red" }
    Write-Host ("[{0}] {1} {2}" -f $(if ($Ok) { "ok" } else { "XX" }), $Name, $Detail) -ForegroundColor $color
    if (-not $Ok) { throw "Passo falhou: $Name" }
}

function Wait-Until([scriptblock]$Condition, [int]$TimeoutSeconds, [int]$EverySeconds = 2) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $value = & $Condition
        if ($value) { return $value }
        Start-Sleep -Seconds $EverySeconds
    }
    return $null
}

function Test-Ready {
    try {
        return ((Invoke-WebRequest -UseBasicParsing "http://localhost:$Port/q/health/ready").StatusCode -eq 200)
    }
    catch {
        return $false
    }
}

function Psql([string]$Sql) {
    $out = docker exec -e PGPASSWORD=billing-local "$Project-postgres-1" psql -U billing -d billing_db -tA -F "|" -c $Sql 2>$null
    if ($null -eq $out) { return $null }
    return ($out | Out-String).Trim()
}

function Send-Command([string]$Type, [long]$WorkOrderId, [guid]$SagaId, $Payload) {
    $body = [ordered]@{
        messageId   = [guid]::NewGuid().ToString()
        type        = $Type
        version     = 1
        kind        = "COMMAND"
        sagaId      = $SagaId.ToString()
        workOrderId = $WorkOrderId
        orderNumber = ("OS-{0:D6}" -f $WorkOrderId)
        correlationId = "e2e-$Type"
        occurredAt  = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
        payload     = $Payload
    }
    $file = Join-Path $Tmp "$Type.json"
    [System.IO.File]::WriteAllText($file, ($body | ConvertTo-Json -Depth 10 -Compress), (New-Object System.Text.UTF8Encoding($false)))
    docker cp $file "$Project-localstack-1:/tmp/$Type.json" | Out-Null
    $queue = "http://localhost:4566/000000000000/oficina-local-billing-commands.fifo"
    docker exec "$Project-localstack-1" awslocal sqs send-message --queue-url $queue `
        --message-group-id "wo-$WorkOrderId" --message-deduplication-id $body.messageId `
        --message-body "file:///tmp/$Type.json" | Out-Null
    return $body.messageId
}

function Outbox([string]$OrderNumber) {
    return Psql "select string_agg(message_type, ',' order by created_at) from outbox where payload->>'orderNumber' = '$OrderNumber' or message_group_id = 'wo-$($OrderNumber.Substring(3).TrimStart('0'))'"
}

try {
    Set-Location $Root

    # --- 0. pre-requisitos --------------------------------------------------------------------------------------
    docker version --format "{{.Server.Version}}" 2>$null | Out-Null
    Step "Docker disponivel" ($LASTEXITCODE -eq 0)
    $null = stripe --version 2>$null
    Step "Stripe CLI instalado" ($LASTEXITCODE -eq 0)

    $apiKey = ""
    if (-not $NoStripeKey) {
        $apiKey = $env:STRIPE_API_KEY
        $why = ""
        if ([string]::IsNullOrWhiteSpace($apiKey)) {
            if (-not $env:AWS_DEFAULT_REGION) { $env:AWS_DEFAULT_REGION = "us-east-1" }
            $ssm = aws ssm get-parameter --name "/oficina/$SsmEnv/stripe_api_key" --with-decryption --query Parameter.Value --output text 2>&1
            if ($LASTEXITCODE -eq 0) { $apiKey = ([string]$ssm).Trim() }
            else { $why = (($ssm | Out-String) -replace "(sk|rk)_(test|live)_\w+", "***").Trim() }
        }
        $valid = { param($k) (-not [string]::IsNullOrWhiteSpace($k)) -and ($k.StartsWith("rk_test_") -or $k.StartsWith("sk_test_")) }
        if (-not (& $valid $apiKey)) {
            # Ultimo recurso: pedir a chave aqui, em prompt oculto (nada aparece na tela nem no historico).
            Write-Host "`nNao achei a chave no ambiente nem no SSM. Copie a chave rk_test_... no Dashboard da Stripe," -ForegroundColor Yellow
            Write-Host "cole abaixo (nao aparece nada ao colar) e tecle Enter." -ForegroundColor Yellow
            $secure = Read-Host "Chave de API de teste" -AsSecureString
            $apiKey = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)).Trim()
        }
        $keyOk = & $valid $apiKey
        Step "Chave de API de TESTE da Stripe obtida (env ou SSM)" $keyOk  $(if ($keyOk) { "(valor nao exibido)" } else { "- defina `$env:STRIPE_API_KEY (rk_test_...) ou ative a sessao AWS. $why" })
    }

    # --- 1. segredo do webhook do `stripe listen` e override do compose ---------------------------------------
    $raw = (stripe listen --print-secret 2>&1 | Out-String)
    $found = [regex]::Match($raw, "whsec_\w+")
    $secret = if ($found.Success) { $found.Value } else { "" }
    $hint = if ($found.Success) { "(valor nao exibido)" } else { "- rode `stripe login` e tente de novo. Saida do CLI: " + ($raw -replace "(sk|rk)_(test|live)_\w+", "***").Trim() }
    Step "Segredo do webhook (stripe listen)" $found.Success $hint
    $env:STRIPE_API_KEY = $apiKey
    $env:STRIPE_WEBHOOK_SECRET = $secret

    $override = Join-Path $Tmp "override.yml"
    @"
services:
  billing:
    environment:
      PAYMENT_PROVIDER: stripe
      PAYMENT_SIMULATION_ENABLED: "false"
      STRIPE_API_KEY: `${STRIPE_API_KEY}
      STRIPE_WEBHOOK_SECRET: `${STRIPE_WEBHOOK_SECRET}
"@ | Set-Content -Path $override -Encoding ASCII

    $up = @("compose", "-p", $Project, "-f", "docker-compose.yml", "-f", $override, "up", "-d")
    if (-not $SkipBuild) { $up += "--build" }
    & docker @up 2>&1 | Select-Object -Last 3 | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray }
    Step "Containers no ar" ($LASTEXITCODE -eq 0)

    $ready = Wait-Until { Test-Ready } 150 3
    Step "Billing pronto (/q/health/ready)" ([bool]$ready)

    # --- 2. eventos da Stripe chegando no Billing ---------------------------------------------------------------
    $listenLog = Join-Path $Tmp "listen.log"
    Remove-Item $listenLog, "$listenLog.err" -ErrorAction SilentlyContinue
    $ListenProcess = Start-Process -FilePath stripe -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $listenLog -RedirectStandardError "$listenLog.err" `
        -ArgumentList "listen", "--forward-to", "localhost:$Port/webhooks/stripe", `
        "--events", "payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,charge.refunded,refund.updated"
    $listening = Wait-Until { ((Get-Content "$listenLog.err", $listenLog -ErrorAction SilentlyContinue) -join "`n") -match "Ready" } 30 1
    Step "stripe listen encaminhando para o Billing" ([bool]$listening)

    # --- 3. orcamento --------------------------------------------------------------------------------------------
    $workOrderId = [long](Get-Random -Minimum 200000 -Maximum 899999)
    $orderNumber = "OS-{0:D6}" -f $workOrderId
    $sagaId = [guid]::NewGuid()
    $total = $Amount
    Write-Host "`nOrdem de servico de teste: $orderNumber (saga $sagaId)" -ForegroundColor Cyan

    $items = @(
        @{ kind = "SERVICE"; refId = 3; name = "Servico de teste e2e"; quantity = 1; unitPrice = $total }
    )
    $customer = @{ name = "Cliente E2E"; email = "cliente.e2e@exemplo.com" }
    $null = Send-Command "GenerateBudget" $workOrderId $sagaId @{
        clientId = 42; customer = $customer; items = $items; total = $total; approvalTtlSeconds = 3600
    }
    $budget = Wait-Until { Psql "select id, status from budget where order_number = '$orderNumber'" } 40 2
    Step "GenerateBudget -> orcamento SENT" ($budget -and $budget.EndsWith("|SENT")) $budget
    $budgetId = $budget.Split("|")[0]

    # --- 4. aprovacao pelo link do e-mail (codigo vem do log do mailer em modo mock) ------------------------------
    $token = Wait-Until {
        # O log persiste entre execucoes: vale o e-mail mais recente que cita a OS desta execucao.
        $lines = @(docker logs "$Project-billing-1" 2>&1 | Where-Object { $_ -match $orderNumber -and $_ -match "autoriza" })
        if ($lines.Count -gt 0) {
            $m = [regex]::Match([string]$lines[$lines.Count - 1], "C.digo de autoriza..o: ([A-Za-z0-9_-]+)")
            if ($m.Success) { $m.Groups[1].Value } else { $null }
        }
        else { $null }
    } 30 2
    Step "Codigo de autorizacao lido do e-mail (log do mailer mock)" ([bool]$token) "(valor nao exibido)"
    $approveBody = @{ token = $token } | ConvertTo-Json -Compress
    $approval = Invoke-RestMethod -Method Post -ErrorAction Stop -Uri "http://localhost:$Port/public/budgets/$orderNumber/approve" `
        -ContentType "application/json" -Body $approveBody
    Step "Aprovacao do orcamento pelo link" ($null -ne $approval)

    # --- 5. pagamento Pix na Stripe -------------------------------------------------------------------------------
    $null = Send-Command "RequestPayment" $workOrderId $sagaId @{ budgetId = $budgetId; amount = $total; attempt = 1 }

    if ($NoStripeKey) {
        $failed = Wait-Until { (Outbox $orderNumber) -match "PaymentRequestFailed" } 40 2
        Step "(teste a seco) sem chave: RequestPayment vira PaymentRequestFailed" ([bool]$failed) (Outbox $orderNumber)
        return
    }

    $payment = Wait-Until { Psql "select id, provider_payment_id, status, ticket_url from payment where order_number = '$orderNumber' and provider_payment_id is not null" } 60 2
    Step "RequestPayment -> PaymentIntent Pix criado na Stripe" ([bool]$payment) ($payment -replace "\|https?://[^|]*$", "|...")
    $fields = $payment.Split("|")
    $paymentId = $fields[0]; $intentId = $fields[1]; $ticketUrl = $fields[3]
    Step "Evento PaymentRequested publicado" ((Outbox $orderNumber) -match "PaymentRequested") (Outbox $orderNumber)

    Write-Host "`n>>> ACAO MANUAL: abra a URL abaixo, clique em 'Simulate scan' e AUTORIZE o pagamento." -ForegroundColor Yellow
    Write-Host "    $ticketUrl" -ForegroundColor Yellow
    Write-Host "    PaymentIntent: $intentId`n"
    Start-Process $ticketUrl
    $null = Read-Host "Depois de autorizar, tecle Enter"

    $paid = Wait-Until { (Psql "select status from payment where id = '$paymentId'") -eq "APPROVED" } 90 3
    Step "Webhook payment_intent.succeeded -> pagamento APPROVED" ([bool]$paid)
    Step "Evento PaymentApproved publicado" ((Outbox $orderNumber) -match "PaymentApproved") (Outbox $orderNumber)
    $notice = Psql "select string_agg(source || ':' || provider_status, ' ') from payment_event where payment_id = '$paymentId'"
    Step "Notificacao registrada (trilha payment_event)" ($notice -match "WEBHOOK:APPROVED") $notice

    # --- 6. estorno (compensacao da Saga) --------------------------------------------------------------------------
    $null = Send-Command "RefundPayment" $workOrderId $sagaId @{ paymentId = $paymentId; reason = "e2e: execucao falhou" }
    $refunded = Wait-Until { (Psql "select status from payment where id = '$paymentId'") -eq "REFUNDED" } 150 3
    $final = Psql "select status from payment where id = '$paymentId'"
    Step "Estorno concluido (REFUND_PENDING -> REFUNDED por webhook ou reconciliacao)" ([bool]$refunded) $final
    Step "Evento PaymentRefunded publicado" ((Outbox $orderNumber) -match "PaymentRefunded") (Outbox $orderNumber)
    $trail = Psql "select string_agg(source || ':' || provider_status, ' ' order by id) from payment_event where payment_id = '$paymentId'"
    Write-Host "`nTrilha do provedor: $trail" -ForegroundColor Cyan
}
catch {
    Write-Host "`nERRO: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Logs do Billing: docker logs $Project-billing-1" -ForegroundColor DarkGray
}
finally {
    if ($ListenProcess -and -not $ListenProcess.HasExited) { Stop-Process -Id $ListenProcess.Id -Force }
    $env:STRIPE_API_KEY = $null; $env:STRIPE_WEBHOOK_SECRET = $null
    if (-not $Keep) { docker compose -p $Project stop 2>&1 | Out-Null }
    Write-Host "`n=== Resumo ===" -ForegroundColor Cyan
    $Results | Format-Table -AutoSize -Wrap | Out-String | Write-Host
    if (($Results | Where-Object Resultado -eq "FALHOU").Count -gt 0) { exit 1 }
}
