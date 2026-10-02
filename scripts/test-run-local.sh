#!/bin/sh
set -eu
project=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

repo="$tmp/repository with spaces"
mkdir -p "$repo"
printf 'class A {}\n' > "$repo/A.java"
output=$($project/run-local.sh --check "$repo")
printf '%s\n' "$output" | grep -F "Host path: $repo" >/dev/null
printf '%s\n' "$output" | grep -F 'Container mount: /workspace/target (read-only)' >/dev/null

special="$tmp/repo ; \$(touch INJECTED)"
mkdir -p "$special"
(cd "$tmp" && "$project/run-local.sh" --check "$special" >/dev/null)
[ ! -e "$tmp/INJECTED" ]

file="$tmp/not-a-directory"
printf x > "$file"
if $project/run-local.sh --check "$file" >/dev/null 2>&1; then
  echo 'non-directory was accepted' >&2; exit 1
fi
if $project/run-local.sh --check "$tmp/missing" >/dev/null 2>&1; then
  echo 'missing path was accepted' >&2; exit 1
fi

mkdir -p "$tmp/physical/repository"
ln -s "$tmp/physical/repository" "$tmp/repository-link"
canonical=$($project/run-local.sh --check "$tmp/repository-link")
printf '%s\n' "$canonical" | grep -F "Host path: $tmp/physical/repository" >/dev/null

normalized=$($project/run-local.sh --check "$tmp/physical/../physical/repository")
printf '%s\n' "$normalized" | grep -F "Host path: $tmp/physical/repository" >/dev/null

echo 'run-local.sh: 6 validation cases passed'
