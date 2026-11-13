#!/usr/bin/env bash
# Whether a pull request needs the full check of .github/workflows/quizbuks.yml (the jobs web, package, deploy, the
# end-to-end shards and local-demo). Reads the changed paths, one per line, on stdin and prints "true" or "false".
#
# "false" only when EVERY changed path is under docs/ (then the quick job `docs` is all that is needed: it runs the
# checks that read documents). One path outside docs/ (the workflow itself, a script, the root CLAUDE.md, a help page
# inside backend/) or an empty list means "true": when in doubt, everything runs. The patterns are not configurable on
# purpose (the platform's tools/ci-backend-scope.sh with .jabiz-ci-no-backend also lists source folders, which would
# skip the end-to-end tests for a change to the pages they drive).
set -euo pipefail

seen=0
while IFS= read -r path || [[ -n "$path" ]]; do
  [[ -z "$path" ]] && continue
  seen=1
  # Anchored at the start; a path that only contains "docs/" later on (backend/x/docs/a.md) is code.
  case "$path" in
    docs/*) ;;
    *) echo true; exit 0 ;;
  esac
done

if [[ "$seen" == 0 ]]; then echo true; else echo false; fi
