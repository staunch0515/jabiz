#!/bin/sh
# check-env.sh <.env> <required-env>: whether the environment's .env gives every variable of the list (required-env in
# this directory), each non-empty and as long as the list asks. Run by the deploy workflow on the server before the
# image is pulled. Prints the names of what is missing or too short, never a value; exits 1 if anything is.
set -eu

env_file="$1"
required="$2"
[ -r "$env_file" ] || { echo "cannot read $env_file" >&2; exit 1; }

problems=
while read -r name min rest; do
  case "$name" in '' | '#'*) continue ;; esac
  # The last definition counts, as in compose; a value in one pair of quotes is the text inside them.
  value=$(sed -n "s/^${name}=//p" "$env_file" | tail -n 1)
  case "$value" in
    \'*\') value=${value#\'}; value=${value%\'} ;;
    \"*\") value=${value#\"}; value=${value%\"} ;;
  esac
  if [ -z "$value" ]; then
    problems="$problems
  $name is not set"
  elif [ -n "$min" ] && [ "${#value}" -lt "$min" ]; then
    problems="$problems
  $name is shorter than $min characters"
  fi
done <"$required"

if [ -n "$problems" ]; then
  echo "$env_file lacks what the application needs to start (deploy/quizbuks/DEPLOY.md):$problems" >&2
  exit 1
fi
