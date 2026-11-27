#!/usr/bin/env bash
# Aponta o endpoint de webhook da Stripe para o API Gateway do ambiente (spec 05 S3, spec 11 §2).
# A URL do gateway muda a cada sessao do Academy; o segredo de assinatura (whsec_) do endpoint NAO muda.
#
# Entrada (ambiente):
#   STACK_ENV             hml | prod
#   WEBHOOK_ENDPOINT_ID   we_... do endpoint daquele ambiente
#   STRICT                1 = falta de gateway/endpoint e erro (disparo manual); 0 = aviso (padrao, deploy)
# Le /oficina/<env>/api_gateway_url e /oficina/<env>/stripe_api_key do SSM. Nada secreto e impresso.
set -euo pipefail

: "${STACK_ENV:?defina STACK_ENV (hml ou prod)}"
strict="${STRICT:-0}"

skip() {
  if [ "$strict" = "1" ]; then
    echo "::error::$1"
    exit 1
  fi
  echo "::notice::Webhook da Stripe nao atualizado: $1"
  exit 0
}

[ -n "${WEBHOOK_ENDPOINT_ID:-}" ] || skip "defina a variavel STRIPE_WEBHOOK_ENDPOINT_ID no environment (we_...)"

if ! gateway_url="$(aws ssm get-parameter --name "/oficina/${STACK_ENV}/api_gateway_url" \
      --query Parameter.Value --output text 2>/dev/null)"; then
  skip "sem /oficina/${STACK_ENV}/api_gateway_url (aplique o oficina-auth-lambda e rode de novo)"
fi

if ! api_key="$(aws ssm get-parameter --name "/oficina/${STACK_ENV}/stripe_api_key" --with-decryption \
      --query Parameter.Value --output text 2>/dev/null)"; then
  skip "sem /oficina/${STACK_ENV}/stripe_api_key no SSM"
fi
echo "::add-mask::${api_key}"
case "$api_key" in
  rk_test_* | sk_test_*) ;;
  *) echo "::error::/oficina/${STACK_ENV}/stripe_api_key nao parece uma chave de teste da Stripe (esperado rk_test_ ou sk_test_)"; exit 1 ;;
esac

url="${gateway_url%/}/webhooks/stripe"
response="$(mktemp)"
trap 'rm -f "$response"' EXIT

# A chave segue por stdin (-K -): nao aparece na linha de comando nem no log.
printf 'user = "%s:"\n' "$api_key" | curl -sS --fail-with-body -K - \
  -X POST "https://api.stripe.com/v1/webhook_endpoints/${WEBHOOK_ENDPOINT_ID}" \
  --data-urlencode "url=${url}" -o "$response"

status="$(jq -r '.status' "$response")"
new_url="$(jq -r '.url' "$response")"
if [ "$new_url" != "$url" ] || [ "$status" != "enabled" ]; then
  echo "::error::A Stripe nao refletiu a mudanca (url=${new_url}, status=${status})"
  exit 1
fi
echo "Webhook ${WEBHOOK_ENDPOINT_ID} (${STACK_ENV}) -> ${url} [${status}]"
