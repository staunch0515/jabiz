#!/bin/sh
# check-env.sh <.env> <required-env>: whether the environment's .env gives every variable of the list (required-env in
# this directory), each non-empty, as long as the list asks and starting as it asks; a name marked `?` may be absent,
# but when given is checked the same. Run by the deploy workflow on the server before the image is pulled. Prints the
# names of what is missing, too short or of the wrong form, never a value; exits 1 if anything is.
set -eu

env_file="$1"
required="$2"
[ -r "$env_file" ] || { echo "cannot read $env_file" >&2; exit 1; }

problems=
while read -r name min prefix rest; do
  case "$name" in '' | '#'*) continue ;; esac
  optional=
  case "$name" in '?'*) optional=yes; name=${name#?} ;; esac
  [ "$min" = - ] && min=
  # The last definition counts, as in compose; a value in one pair of quotes is the text inside them.
  value=$(sed -n "s/^${name}=//p" "$env_file" | tail -n 1)
  case "$value" in
    \'*\') value=${value#\'}; value=${value%\'} ;;
    \"*\") value=${value#\"}; value=${value%\"} ;;
  esac
  if [ -z "$value" ]; then
    [ -n "$optional" ] || problems="$problems
  $name is not set"
  elif [ -n "$prefix" ] && [ "${value#"$prefix"}" = "$value" ]; then
    problems="$problems
  $name must start with $prefix"
  elif [ -n "$min" ] && [ "${#value}" -lt "$min" ]; then
    problems="$problems
  $name is shorter than $min characters"
  fi
done <"$required"

if [ -n "$problems" ]; then
  echo "$env_file lacks what the application needs to start (deploy/quizbuks/DEPLOY.md):$problems" >&2
  exit 1
fi
