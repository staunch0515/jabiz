#!/bin/sh
# Fills a running jabiz with demonstration data of the orders-and-inventory sample, through the same API the
# generated pages use (no SQL): ledger accounts, a warehouse, products, stock, a few orders, one shipped.
# Idempotent enough to run twice: existing codes are reported and skipped by the platform's unique checks.
#
#   JABIZ_URL=http://localhost:8080 JABIZ_USER=admin JABIZ_PASSWORD=... tools/demo/seed.sh
#
# Needs curl and jq.
set -eu

URL="${JABIZ_URL:-http://localhost:8080}"
USER_NAME="${JABIZ_USER:-admin}"
PASSWORD="${JABIZ_PASSWORD:?set JABIZ_PASSWORD (docker compose logs app shows the generated one)}"

TOKEN=$(curl -sf -X POST "$URL/api/auth/login" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg u "$USER_NAME" --arg p "$PASSWORD" '{userName: $u, password: $p}')" | jq -r .accessToken)

# post PATH JSON: prints the response; a 4xx answer (e.g. already exists) is reported, not fatal.
post() {
    response=$(curl -s -w '\n%{http_code}' -X POST "$URL$1" -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' -d "$2")
    status=$(printf '%s' "$response" | tail -n1)
    body=$(printf '%s' "$response" | sed '$d')
    if [ "$status" -ge 400 ]; then
        echo "  $1 -> $status $(printf '%s' "$body" | jq -c '[.violations[]?.ruleCode]' 2>/dev/null)" >&2
    fi
    printf '%s' "$body"
}

insert() { # dataset attributes
    post "/api/datasets/$1/commit" "{\"changes\":[{\"action\":\"INSERT\",\"attributes\":$2}]}" > /dev/null
}

echo "Ledger accounts"
post /api/processes/LEDGER_ACCOUNT_OPEN/latest '{"accountCode":"1130","accountName":"Accounts receivable","accountType":"ASSET"}' > /dev/null
post /api/processes/LEDGER_ACCOUNT_OPEN/latest '{"accountCode":"4120","accountName":"Sales revenue","accountType":"REVENUE"}' > /dev/null

echo "Warehouse and products"
insert urn:jabiz:dataset:default:Warehouse '{"warehouseCode":"TKY","warehouseName":"Tokyo","active":true}'
insert urn:jabiz:dataset:default:Product '{"sku":"APPLE-1","productName":"Apple","unitPrice":120,"active":true}'
insert urn:jabiz:dataset:default:Product '{"sku":"PEAR-1","productName":"Pear","unitPrice":300,"active":true}'
insert urn:jabiz:dataset:default:Product '{"sku":"MELON-1","productName":"Melon","unitPrice":1800,"active":true}'

echo "Stock"
for item in APPLE-1:100 PEAR-1:40 MELON-1:5; do
    post /api/processes/STOCK_RECEIVE/latest \
        "{\"warehouseCode\":\"TKY\",\"sku\":\"${item%%:*}\",\"quantity\":${item##*:}}" > /dev/null
done

echo "Orders"
ORDER=$(post /api/processes/ORDER_PLACE/latest '{"orderNo":"DEMO-1","customerCode":"CUST-1","warehouseCode":"TKY","lines":[{"sku":"APPLE-1","quantity":6},{"sku":"PEAR-1","quantity":2}]}' | jq -r '.output.orderId // empty')
post /api/processes/ORDER_PLACE/latest '{"orderNo":"DEMO-2","customerCode":"CUST-2","warehouseCode":"TKY","lines":[{"sku":"MELON-1","quantity":1}]}' > /dev/null
if [ -n "$ORDER" ]; then
    post /api/processes/ORDER_SHIP/latest "{\"orderId\":\"$ORDER\"}" > /dev/null
fi

echo "Stock now:"
post /api/queries/commerce.stock_availability '{"params":{"warehouseCode":"TKY"}}' \
    | jq -r '.items[] | "  \(.sku // .SKU)\ton hand \(.onhand // .onHand)\treserved \(.reserved)\tavailable \(.available)"'
