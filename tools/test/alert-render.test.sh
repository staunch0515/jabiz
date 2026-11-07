#!/usr/bin/env bash
# Tests of docker/grafana/alerting/render.sh (ROADMAP 17zu): the alert rules rendered with the deployment's settings.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
script="$root/docker/grafana/alerting/render.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0
settings=(JABIZ_ALERT_EMAIL=oncall@example.test JABIZ_ALERT_SERVICE=jabiz JABIZ_ALERT_DISK_PERCENT=80
  JABIZ_ALERT_REFRESH_GRANTED_MIN=10 JABIZ_ALERT_REFRESH_GRANTED_RATIO=0.02 JABIZ_ALERT_REFRESH_REVOKED_MAX=20)

render() {  # source-dir setting... -> exit status; output in $work/out, messages in $work/err
  local src="$1"
  shift
  rm -rf "$work/out" && mkdir -p "$work/out"
  env -i PATH="$PATH" JABIZ_ALERT_RULES_SOURCE="$src" JABIZ_ALERT_RULES_TARGET="$work/out" JABIZ_ALERT_RENDER_ONLY=true \
    "$@" bash "$script" 2>"$work/err"
}

check() {  # name condition...
  local name="$1"
  shift
  if "$@"; then
    echo "ok   $name"
  else
    echo "FAIL $name"
    failures=$((failures + 1))
  fi
}

render "$root/docker/grafana/alerting" "${settings[@]}"
out="$work/out/jabiz-alerts.yaml"
check 'the platform rules render' test -s "$out"
check 'no placeholder is left' bash -c "! grep -q '\\\$JABIZ_ALERT_' '$out'"
check 'the disk threshold is filled in' grep -q 'disk_total_bytes{job="jabiz"})) > 80' "$out"
check 'the ratio is not cut at a longer name' grep -q '> 0.02' "$out"
check 'the contact point gets the address' grep -q 'addresses: oncall@example.test' "$out"
check 'every rule has a title and a uid of its own' test "$(grep -c '^        title: ' "$out")" -eq \
  "$(grep '^      - uid: jabiz-' "$out" | grep -v oncall | sort -u | wc -l)"

check 'a missing setting stops the container' bash -c \
  "! env -i PATH='$PATH' JABIZ_ALERT_RULES_SOURCE='$root/docker/grafana/alerting' JABIZ_ALERT_RULES_TARGET='$work' \
     JABIZ_ALERT_RENDER_ONLY=true JABIZ_ALERT_SERVICE=jabiz bash '$script' 2>/dev/null"

mkdir -p "$work/app"
printf 'expr: up{job="$JABIZ_ALERT_SERVICE"} > $JABIZ_ALERT_LIMIT\n' > "$work/app/app-alerts.yaml"
check 'a value with other characters is refused' bash -c \
  "! env -i PATH='$PATH' JABIZ_ALERT_RULES_SOURCE='$work/app' JABIZ_ALERT_RULES_TARGET='$work' JABIZ_ALERT_RENDER_ONLY=true \
     JABIZ_ALERT_SERVICE='jabiz\"} or vector(1) #' JABIZ_ALERT_LIMIT=1 bash '$script' 2>/dev/null"
render "$work/app" JABIZ_ALERT_SERVICE=shop JABIZ_ALERT_LIMIT=3
check "an application's own file renders too" grep -q 'up{job="shop"} > 3' "$work/out/app-alerts.yaml"

if ((failures > 0)); then
  echo "$failures failed"
  exit 1
fi
