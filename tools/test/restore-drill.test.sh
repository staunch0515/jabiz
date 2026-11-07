#!/usr/bin/env bash
# Tests of the restore step of tools/ops/restore-drill.sh (ROADMAP 17zz3): the pgBackRest restore it composes - to the
# time T, promoted, never archiving - and the settings it refuses. A stub stands for pgBackRest; nothing is restored.
set -euo pipefail

script="$(cd "$(dirname "$0")/.." && pwd)/ops/restore-drill.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

cat >"$work/pgbackrest" <<END
#!/usr/bin/env bash
printf '%s\n' "\$@" >"$work/args"
END
chmod +x "$work/pgbackrest"

# run <exit> <name> <VAR=value...>: the drill with these settings only.
run() {
  local want=$1 name=$2 status
  shift 2
  rm -f "$work/args"
  set +e
  (cd "$work" && env -i PATH="$PATH" HOME="$work" "$@" bash "$script" >"$work/out" 2>&1)
  status=$?
  set -e
  if [[ "$status" != "$want" ]]; then
    echo "FAIL $name (exit $status, want $want)"; sed 's/^/     /' "$work/out"; failures=$((failures + 1))
    return 1
  fi
  echo "ok   $name"
}
has_arg() {
  if grep -qx -- "$1" "$work/args"; then echo "ok   argument $1"; else echo "FAIL argument $1"; cat "$work/args"; failures=$((failures + 1)); fi
}

T=2026-11-06T12:00:00Z
if run 0 'a pgBackRest restore only' DRILL_TARGET_TIME=$T DRILL_PGBACKREST="$work/pgbackrest" \
  DRILL_PGBACKREST_PG_PATH=/var/lib/postgresql/drill DRILL_RESTORE_ONLY=true DRILL_RECORD="$work/record.md"; then
  has_arg --archive-mode=off
  has_arg --type=time
  has_arg '--target=2026-11-06 12:00:00+00'
  has_arg --target-action=promote
  has_arg --repo=1
  has_arg --stanza=main
  has_arg --pg1-path=/var/lib/postgresql/drill
  has_arg restore
  grep -q 'Restore: ' "$work/record.md" && echo "ok   the record" || { echo "FAIL the record"; failures=$((failures + 1)); }
fi
run 0 'another repository and stanza' DRILL_TARGET_TIME=$T DRILL_PGBACKREST="$work/pgbackrest" \
  DRILL_PGBACKREST_PG_PATH=/x DRILL_PGBACKREST_REPO=2 DRILL_PGBACKREST_STANZA=shop DRILL_RESTORE_ONLY=true \
  DRILL_RECORD="$work/r2.md" && has_arg --repo=2 && has_arg --stanza=shop
run 2 'no data directory' DRILL_TARGET_TIME=$T DRILL_PGBACKREST="$work/pgbackrest" DRILL_RESTORE_ONLY=true || true
run 2 'both restore settings' DRILL_TARGET_TIME=$T DRILL_PGBACKREST="$work/pgbackrest" DRILL_PGBACKREST_PG_PATH=/x \
  DRILL_RESTORE_COMMAND=true DRILL_RESTORE_ONLY=true || true
run 2 'a hand-written pgBackRest restore that would archive' DRILL_TARGET_TIME=$T \
  DRILL_RESTORE_COMMAND="pgbackrest --stanza=main --type=time restore" DRILL_RESTORE_ONLY=true || true
run 2 'restore only without a restore' DRILL_TARGET_TIME=$T DRILL_RESTORE_ONLY=true || true
run 2 'not a time' DRILL_TARGET_TIME=yesterday-ish DRILL_PGBACKREST="$work/pgbackrest" DRILL_PGBACKREST_PG_PATH=/x \
  DRILL_RESTORE_ONLY=true || true
run 2 'verification still needs its settings' DRILL_TARGET_TIME=$T || true

if ((failures > 0)); then
  echo "$failures failed"
  exit 1
fi
