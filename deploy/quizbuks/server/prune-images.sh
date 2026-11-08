#!/bin/sh
# prune-images.sh <apps dir> <app name> <image repository> <deployed tag>: after a successful deployment, the server
# keeps only the latest images of each environment and deletes the other images of the repository (F-F13, T8: every
# deployment pulled a new image and none was ever deleted, until the disk was full and the health check, and with it the
# rollback, failed).
#
# Run by the deploy workflow on the server right after deploy-app.sh succeeded (DEPLOY.md, "旧镜像的清理"):
#   1. The deployed tag is appended to <apps dir>/<app name>/deployed-images, the environment's history.
#   2. Kept: for every environment directory under <apps dir> with such a history, the last N distinct tags of this
#      repository it deployed, N being that environment's QB_DEPLOY_KEEP_IMAGES (its .env; 3 when unset, at least 2:
#      the running image and one to roll back to); and every image any container uses, running or stopped.
#      Staging and production share the server and the repository, so neither deletes the other's.
#   3. Every other image of this repository is deleted (`docker image rm` without force: Docker itself refuses an image
#      a container uses). Images of other repositories, other applications' and the database's, are never touched.
# An image deleted here stays in the registry: a rollback to it pulls it again (DEPLOY.md, "回滚").
# The deployment has already succeeded, so a failure here is reported and the script exits 0; QB_DEPLOY_KEEP_IMAGES
# itself is checked before the deployment (required-env, form keep-images).
set -u

apps=$1
app=$2
repo=$3
tag=$4
dir=$apps/$app
history_limit=200

# keep_of DIR: the environment's QB_DEPLOY_KEEP_IMAGES (last definition, quotes removed), 3 when unset or not a whole
# number of at least 2.
keep_of() {
  v=$(sed -n 's/^QB_DEPLOY_KEEP_IMAGES=//p' "$1/.env" 2>/dev/null | tail -n 1)
  case "$v" in
    \'*\') v=${v#\'}; v=${v%\'} ;;
    \"*\") v=${v#\"}; v=${v%\"} ;;
  esac
  case "$v" in
    '' | *[!0-9]*) echo 3 ;;
    *) if [ "$v" -ge 2 ]; then echo "$v"; else echo 3; fi ;;
  esac
}

work=$(mktemp -d) || { echo "prune-images: cannot make a temporary directory; nothing deleted" >&2; exit 0; }
trap 'rm -rf "$work"' EXIT

# 1. The history, bounded.
if ! printf '%s:%s\n' "$repo" "$tag" >>"$dir/deployed-images"; then
  echo "prune-images: cannot write $dir/deployed-images; nothing deleted" >&2
  exit 0
fi
tail -n "$history_limit" "$dir/deployed-images" >"$work/history" && cat "$work/history" >"$dir/deployed-images"

# 2. The tags to keep: the last N distinct ones of this repository in each environment's history.
: >"$work/keep"
for history in "$apps"/*/deployed-images; do
  [ -f "$history" ] || continue
  n=$(keep_of "$(dirname "$history")")
  awk -v prefix="$repo:" 'index($0, prefix) == 1 { line[++count] = substr($0, length(prefix) + 1) }
    END { for (i = count; i > 0; i--) print line[i] }' "$history" |
    awk '!seen[$0]++' | head -n "$n" >>"$work/keep"
done

# The images (full IDs) of every container, running or stopped.
if ! containers=$(docker ps -aq); then
  echo "prune-images: cannot list the containers; nothing deleted" >&2
  exit 0
fi
: >"$work/used"
if [ -n "$containers" ]; then
  # shellcheck disable=SC2086 # one argument per container ID
  if ! docker inspect --format '{{.Image}}' $containers >"$work/used"; then
    echo "prune-images: cannot inspect the containers; nothing deleted" >&2
    exit 0
  fi
fi

if ! docker image ls --no-trunc --format '{{.Tag}} {{.ID}}' "$repo" >"$work/images"; then
  echo "prune-images: cannot list the images of $repo; nothing deleted" >&2
  exit 0
fi

# An ID is kept when any of its tags is kept or a container uses it.
awk 'NR == FNR { keep[$0] = 1; next } $1 in keep { print $2 }' "$work/keep" "$work/images" | cat - "$work/used" | sort -u >"$work/kept-ids"

# 3. Delete the rest: by tag (an image with another kept tag is only untagged, which cannot happen for a kept ID), an
# untagged one by its ID.
deleted=0
failed=0
while read -r image_tag id; do
  grep -qxF "$id" "$work/kept-ids" && continue
  if [ "$image_tag" = "<none>" ]; then ref=$id; else ref=$repo:$image_tag; fi
  if docker image rm "$ref" >/dev/null; then
    deleted=$((deleted + 1))
  else
    failed=$((failed + 1))
  fi
done <"$work/images"

kept=$(sort -u "$work/keep" | tr '\n' ' ')
echo "prune-images: deleted $deleted image(s) of $repo, $failed could not be deleted; kept tags: ${kept% }"
exit 0
