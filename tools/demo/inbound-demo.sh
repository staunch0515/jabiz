#!/bin/sh
# Plays the payment provider of the orders sample (decision D37, docs/design/11-ledger-events-jobs.md section 2.5):
# POSTs a payment_intent.succeeded for an order to the inbound webhook of the provider demo-pay, signed as Stripe
# signs (t=<seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>). The application records the payment on the order
# once its outbox delivers the event (within a second when delivery runs in the background).
# demo-pay is configured in the dev profile only; the secret is the one of application-dev.yml unless
# JABIZ_DEMO_PAY_WEBHOOK_SECRET says otherwise.
#
#   tools/demo/inbound-demo.sh SO-2026-000001 1600
#
# Needs curl and openssl. Run it again with the same EVENT_ID to see the duplicate answered and not kept twice.
#
# With "shop" first, it plays a sender that signs the body only (decision D57; provider demo-shop): an orders/paid
# whose X-Shopify-Hmac-Sha256 is Base64(HMAC-SHA256(secret, body)), the event id, topic and shop in unsigned headers.
# Sending it again with another EVENT_ID is still a duplicate: the same body of the same topic is kept once.
#
#   tools/demo/inbound-demo.sh shop SO-2026-000001
set -eu

URL="${JABIZ_URL:-http://localhost:8080}"

if [ "${1:-}" = "shop" ]; then
    SECRET="${JABIZ_DEMO_SHOP_WEBHOOK_SECRET:-development_only_demo_shop_secret_000000}"
    ORDER_NO="${2:?order number, e.g. SO-2026-000001}"
    EVENT_ID="${EVENT_ID:-demo-$(date +%s)}"
    BODY=$(printf '{"name":"%s","financial_status":"paid","currency":"JPY"}' "$ORDER_NO")
    SIGNATURE=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -binary | openssl base64 -A)
    curl -sS -w '\n%{http_code}\n' -X POST "$URL/api/inbound/demo-shop" -H 'Content-Type: application/json' \
        -H "X-Shopify-Hmac-Sha256: $SIGNATURE" -H "X-Shopify-Event-Id: $EVENT_ID" -H 'X-Shopify-Topic: orders/paid' \
        -H 'X-Shopify-Shop-Domain: demo-shop.example' --data-binary "$BODY"
    exit 0
fi

SECRET="${JABIZ_DEMO_PAY_WEBHOOK_SECRET:-whsec_development_only_demo_pay_secret_0000}"
ORDER_NO="${1:?order number, e.g. SO-2026-000001}"
AMOUNT="${2:?amount received, e.g. 1600}"
EVENT_ID="${EVENT_ID:-evt_demo_$(date +%s)}"

BODY=$(printf '{"id":"%s","object":"event","type":"payment_intent.succeeded","livemode":false,"data":{"object":{"id":"pi_%s","object":"payment_intent","amount_received":%s,"currency":"jpy","metadata":{"orderNo":"%s"}}}}' \
    "$EVENT_ID" "$EVENT_ID" "$AMOUNT" "$ORDER_NO")
T=$(date +%s)
SIGNATURE=$(printf '%s.%s' "$T" "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.*= //')

curl -sS -w '\n%{http_code}\n' -X POST "$URL/api/inbound/demo-pay" -H 'Content-Type: application/json' \
    -H "Stripe-Signature: t=$T,v1=$SIGNATURE" --data-binary "$BODY"
