#!/usr/bin/env bash
# Starts the LGTM container with the alert rules of docker/grafana/alerting rendered with the deployment's settings
# (docs/design/13-observability-ops.md section 7). Grafana does not expand environment variables inside a rule's query,
# so every JABIZ_ALERT_ placeholder (a dollar sign and the name) in the files is replaced here; one that is not set, or holds anything other than a plain
# value, stops the container rather than provisioning a rule that never fires.
set -euo pipefail
src=${JABIZ_ALERT_RULES_SOURCE:-/otel-lgtm/jabiz/alerting}
dst=${JABIZ_ALERT_RULES_TARGET:-/otel-lgtm/grafana/conf/provisioning/alerting}
for file in "$src"/*.yaml; do
  content=$(<"$file")
  # Longest names first, so that no name is replaced inside a longer one.
  for name in $(grep -o '\$JABIZ_ALERT_[A-Z][A-Z_]*' "$file" | tr -d '$' | sort -u | awk '{ print length, $0 }' | sort -rn | cut -d' ' -f2); do
    value=${!name-}
    if [[ -z "$value" ]]; then
      echo "alerting: $name is not set" >&2
      exit 1
    fi
    if [[ ! "$value" =~ ^[A-Za-z0-9@._+,\;-]+$ ]]; then
      echo "alerting: $name may hold only letters, digits and @._+,;-" >&2
      exit 1
    fi
    content=${content//\$$name/$value}
  done
  printf '%s\n' "$content" > "$dst/$(basename "$file")"
done
# Tests render without starting the stack (tools/test/alert-render.test.sh).
[[ "${JABIZ_ALERT_RENDER_ONLY:-}" == "true" ]] || exec /otel-lgtm/run-all.sh
