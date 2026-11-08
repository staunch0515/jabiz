#!/usr/bin/env bash
# Tests of deploy/quizbuks/server/prune-images.sh (F-F13, T8) against a fake `docker`: each environment keeps its last
# N tags (QB_DEPLOY_KEEP_IMAGES, 3 when unset), staging never deletes production's, images a container uses and other
# repositories' images stay, an untagged image of the repository goes, the history is bounded, and a failing Docker
# never fails the deployment.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
script="$root/deploy/quizbuks/server/prune-images.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0
repo=ghcr.io/example/jabiz-quizbuks

# The fake docker: images in $work/state/images ("<repository> <tag> <id>"), containers in $work/state/containers
# ("<container id> <image id>"), deletions logged to $work/state/removed. DOCKER_FAIL=ps|ls|rm makes that command fail.
mkdir -p "$work/bin" "$work/state"
cat >"$work/bin/docker" <<'FAKE'
#!/usr/bin/env bash
state="$FAKE_STATE"
case "$1 ${2:-}" in
  "ps -aq")
    [ "${DOCKER_FAIL:-}" = ps ] && { echo "cannot connect" >&2; exit 1; }
    cut -d' ' -f1 "$state/containers" ;;
  "inspect --format")
    shift 3
    for c in "$@"; do awk -v c="$c" '$1 == c { print $2 }' "$state/containers"; done ;;
  "image ls")
    [ "${DOCKER_FAIL:-}" = ls ] && { echo "cannot connect" >&2; exit 1; }
    awk -v r="${!#}" '$1 == r { print $2, $3 }' "$state/images" ;;
  "image rm")
    [ "${DOCKER_FAIL:-}" = rm ] && { echo "conflict" >&2; exit 1; }
    ref=$3
    if [[ $ref == sha256:* ]]; then
      awk -v id="$ref" '$3 != id' "$state/images" >"$state/images.new"
    else
      awk -v ref="$ref" '$1 ":" $2 != ref' "$state/images" >"$state/images.new"
    fi
    mv "$state/images.new" "$state/images"
    echo "$ref" >>"$state/removed" ;;
  *) echo "fake docker: unexpected $*" >&2; exit 2 ;;
esac
FAKE
chmod +x "$work/bin/docker"
export PATH="$work/bin:$PATH" FAKE_STATE="$work/state"

apps="$work/apps"

# reset: two environments, no history, no images, no containers.
reset() {
  rm -rf "$apps" "$work/state"/*
  mkdir -p "$apps/quizbuks" "$apps/quizbuks-staging" "$apps/other-site"
  echo "APP_NAME=quizbuks" >"$apps/quizbuks/.env"
  echo "APP_NAME=quizbuks-staging" >"$apps/quizbuks-staging/.env"
  : >"$work/state/images"
  : >"$work/state/containers"
  : >"$work/state/removed"
}

image() { echo "$1 $2 sha256:$3" >>"$work/state/images"; }
container() { echo "$1 sha256:$2" >>"$work/state/containers"; }

# deploy <app> <tag>: the image was pulled, the app's container now runs it (replacing the app's previous one), and
# the script runs as the workflow runs it.
deploy() {
  image "$repo" "$2" "id-$2"
  awk -v c="$1-app" '$1 != c' "$work/state/containers" >"$work/state/c.new"
  mv "$work/state/c.new" "$work/state/containers"
  container "$1-app" "id-$2"
  status=0
  out=$(sh "$script" "$apps" "$1" "$repo" "$2" 2>&1) || status=$?
}

has() { grep -q " $1 " <(awk '{ print " " $2 " " }' "$work/state/images"); }

check() {
  local name=$1 ok=$2
  if [ "$ok" = yes ]; then echo "ok   $name"; else echo "FAIL $name: $out"; failures=$((failures + 1)); fi
}

tags() { awk '{ print $2 }' "$work/state/images" | sort | tr '\n' ' '; }

reset
for t in a1 a2 a3 a4 a5; do deploy quizbuks-staging "$t"; done
[ "$(tags)" = "a3 a4 a5 " ] && r=yes || r=no
check "staging keeps its last 3 (default)" $r
[ "$status" -eq 0 ] && grep -q "deleted 1 image" <<<"$out" && r=yes || r=no
check "reports what it deleted, exits 0" $r

reset
deploy quizbuks p1
deploy quizbuks p2
for t in s1 s2 s3 s4 s5 s6; do deploy quizbuks-staging "$t"; done
[ "$(tags)" = "p1 p2 s4 s5 s6 " ] && r=yes || r=no
check "staging's deployments keep production's images" $r

reset
echo "QB_DEPLOY_KEEP_IMAGES=2" >>"$apps/quizbuks-staging/.env"
for t in b1 b2 b3 b4; do deploy quizbuks-staging "$t"; done
[ "$(tags)" = "b3 b4 " ] && r=yes || r=no
check "QB_DEPLOY_KEEP_IMAGES=2" $r

reset
echo 'QB_DEPLOY_KEEP_IMAGES="5"' >>"$apps/quizbuks-staging/.env"
for t in c1 c2 c3 c4 c5 c6 c7; do deploy quizbuks-staging "$t"; done
[ "$(tags)" = "c3 c4 c5 c6 c7 " ] && r=yes || r=no
check "QB_DEPLOY_KEEP_IMAGES=\"5\" in quotes" $r

reset
echo "QB_DEPLOY_KEEP_IMAGES=1" >>"$apps/quizbuks-staging/.env"
for t in d1 d2 d3 d4; do deploy quizbuks-staging "$t"; done
[ "$(tags)" = "d2 d3 d4 " ] && r=yes || r=no
check "an invalid value counts as 3 (the check before deploying refuses it)" $r

# A rollback deploys an older tag again (pulled again from the registry): it is the latest in the history, and the
# two it follows stay.
reset
for t in e1 e2 e3 e4 e5; do deploy quizbuks-staging "$t"; done
has e2 && r=no || r=yes
check "before the rollback e2 is gone" $r
deploy quizbuks-staging e2
[ "$(tags)" = "e2 e4 e5 " ] && r=yes || r=no
check "a rollback to an older tag keeps it and the two latest before it" $r

# Images a container uses stay, whatever the history; other repositories' images and other applications' are untouched.
reset
image "$repo" old1 old1
image "$repo" old2 old2
container "maintenance-job" old1
image "postgres-pgbackrest" 16 pg
image "ghcr.io/example/other-site" v9 site
image "$repo" "<none>" dangling
for t in f1 f2 f3 f4; do deploy quizbuks-staging "$t"; done
has old1 && ! has old2 && ! has "<none>" && has 16 && has v9 && r=yes || r=no
check "used images and other repositories stay, an unused untagged one goes" $r
grep -qx "sha256:dangling" "$work/state/removed" && r=yes || r=no
check "the untagged image is deleted by its ID" $r

# The same image under two tags: kept when one of them is.
reset
for t in g1 g2 g3; do deploy quizbuks-staging "$t"; done
image "$repo" latest id-g3
deploy quizbuks-staging g4
has latest && r=yes || r=no
check "a second tag of a kept image stays" $r

reset
for i in $(seq 1 250); do echo "$repo:h$i" >>"$apps/quizbuks-staging/deployed-images"; done
deploy quizbuks-staging h251
[ "$(wc -l <"$apps/quizbuks-staging/deployed-images")" -eq 200 ] && tail -n 1 "$apps/quizbuks-staging/deployed-images" | grep -qx "$repo:h251" && r=yes || r=no
check "the history keeps its last 200 lines" $r

# A history line of another repository is not this repository's tag.
reset
echo "ghcr.io/example/jabiz-quizbuks-other:k1" >>"$apps/quizbuks-staging/deployed-images"
image "$repo" k1 k1
for t in k2 k3 k4; do deploy quizbuks-staging "$t"; done
! has k1 && r=yes || r=no
check "another repository's tag in the history does not keep this repository's image" $r

for fail in ps ls rm; do
  reset
  for t in m1 m2 m3; do deploy quizbuks-staging "$t"; done
  export DOCKER_FAIL=$fail
  deploy quizbuks-staging m4
  unset DOCKER_FAIL
  [ "$status" -eq 0 ] && has m1 && r=yes || r=no
  check "docker failing ($fail): exits 0, nothing lost" $r
done

reset
chmod a-w "$apps/quizbuks-staging"
touch "$apps/quizbuks-staging/deployed-images" 2>/dev/null || true
if [ "$(id -u)" -ne 0 ]; then
  deploy quizbuks-staging n1
  [ "$status" -eq 0 ] && grep -q "nothing deleted" <<<"$out" && r=yes || r=no
  check "an unwritable history: exits 0, nothing deleted" $r
fi
chmod u+w "$apps/quizbuks-staging"

[ "$failures" -eq 0 ] || { echo "$failures failure(s)"; exit 1; }
echo "all prune-images tests passed"
