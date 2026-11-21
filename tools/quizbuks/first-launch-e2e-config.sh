#!/usr/bin/env bash
# The first launch's configuration for the end-to-end test (quizbuks-web/e2e/first-launch, F-LAUNCH Q4): the sample
# handed to the requester (docs/quizbuks/first-launch-sample/), copied to a directory of the run's own, with the
# publication's end moved to 60 days from now (the sample's fixed date would some day be past, or more than the
# 366 days a publication may run from its start). Everything else is the sample as it is.
#
#   tools/quizbuks/first-launch-e2e-config.sh <directory>     # prints the path of first-launch.yml
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SAMPLE=$ROOT/docs/quizbuks/first-launch-sample
OUT=${1:?usage: $0 <directory>}

rm -rf "$OUT"
mkdir -p "$OUT"
cp -R "$SAMPLE"/. "$OUT"/
END=$(date -u -d '+60 days' +%Y-%m-%dT23:59+00:00 2>/dev/null || date -u -v+60d +%Y-%m-%dT23:59+00:00)
grep -q '^        endAt: ' "$OUT/first-launch.yml" || { echo "no endAt in the sample" >&2; exit 1; }
sed -i.bak -E "s|^(        endAt: )[^ ]+|\1$END|" "$OUT/first-launch.yml"
rm -f "$OUT/first-launch.yml.bak"
echo "$OUT/first-launch.yml"
