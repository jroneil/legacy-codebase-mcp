#!/bin/sh
set -eu

fail() {
  printf 'run-local.sh: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: ./run-local.sh [--check] [REPOSITORY]

Without REPOSITORY, opens a native folder picker using zenity or kdialog on Linux,
or osascript on macOS. --check validates and prints the resolved mount without
starting Docker.
USAGE
}

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
check_only=false
if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then usage; exit 0; fi
if [ "${1:-}" = "--check" ]; then check_only=true; shift; fi
[ "$#" -le 1 ] || fail "expected at most one repository path"
selected=${1:-}

if [ -z "$selected" ]; then
  case "$(uname -s)" in
    Darwin)
      command -v osascript >/dev/null 2>&1 || fail "osascript is unavailable; pass an explicit repository path"
      if ! selected=$(osascript -e 'POSIX path of (choose folder with prompt "Select repository folder")'); then
        fail "repository selection was cancelled"
      fi
      ;;
    Linux)
      if command -v zenity >/dev/null 2>&1; then
        if ! selected=$(zenity --file-selection --directory --title='Select repository folder'); then
          fail "repository selection was cancelled"
        fi
      elif command -v kdialog >/dev/null 2>&1; then
        if ! selected=$(kdialog --getexistingdirectory "${HOME:-/}"); then
          fail "repository selection was cancelled"
        fi
      else
        fail "no supported folder picker found (install zenity or kdialog, or pass a path)"
      fi
      ;;
    *) fail "no folder picker is available on this platform; pass an explicit repository path" ;;
  esac
fi

[ -n "$selected" ] || fail "repository path is empty"
[ -d "$selected" ] || fail "repository path does not exist or is not a directory: $selected"
if ! repository=$(CDPATH= cd -- "$selected" 2>/dev/null && pwd -P); then
  fail "repository path cannot be resolved: $selected"
fi
[ -n "$repository" ] || fail "repository path resolved to an empty value"
if printf '%s' "$repository" | LC_ALL=C grep -q '[[:cntrl:]]'; then
  fail "repository path contains a control character"
fi
case "$repository" in
  /|/home|/Users) fail "refusing to mount a broad host filesystem root: $repository" ;;
esac
if [ -n "${HOME:-}" ] && [ -d "$HOME" ]; then
  home_root=$(CDPATH= cd -- "$HOME" 2>/dev/null && pwd -P) || home_root=
  [ "$repository" != "$home_root" ] || fail "refusing to mount the entire user home: $repository"
fi
repository_name=${repository##*/}
[ -n "$repository_name" ] || fail "repository name is empty"

printf 'Repository: %s\n' "$repository_name"
printf 'Host path: %s\n' "$repository"
printf 'Container mount: /workspace/target (read-only)\n'

if [ "$check_only" = true ]; then exit 0; fi
command -v docker >/dev/null 2>&1 || fail "docker is not installed or not on PATH"
docker compose version >/dev/null 2>&1 || fail "Docker Compose is unavailable"
if [ ! -f "$script_dir/.env" ] && [ -z "${POSTGRES_PASSWORD:-}" ]; then
  fail "create .env from .env.example and set POSTGRES_PASSWORD"
fi

export LEGACY_REPOSITORY_ROOT=$repository
export LEGACY_REPOSITORY_NAME=$repository_name
export LEGACY_REPOSITORY_CONFIGURED=true
cd "$script_dir"
existing=true
for service in postgres backend frontend; do
  [ -n "$(docker compose ps --all --quiet "$service")" ] || existing=false
done
if [ "$existing" = true ]; then
  docker compose up -d --wait postgres
  docker compose up -d --wait --no-deps --force-recreate backend
  docker compose up -d --wait --no-deps --no-recreate frontend
else
  docker compose up --build -d --wait
fi
printf '\nReady: http://127.0.0.1:%s/scan\n' "${FRONTEND_PORT:-3000}"
