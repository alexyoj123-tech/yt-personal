#!/usr/bin/env bash
# Helpers compartidos por los scripts del Proyecto A.
# Source este archivo desde cada script: `source "$(dirname "$0")/common.sh"`.

set -euo pipefail

# ── Paths ─────────────────────────────────────────────────────────────
# Repo root = 2 niveles arriba de este archivo (project-a/scripts/common.sh)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_A_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROJECT_A_DIR/.." && pwd)"

# Workspaces dentro de project-a/ (ignorados por .gitignore del repo)
APKS_DIR="${APKS_DIR:-$PROJECT_A_DIR/build/apks}"         # APKs oficiales descargados
PATCHED_DIR="${PATCHED_DIR:-$PROJECT_A_DIR/build/patched}" # APKs parcheados
SIGNED_DIR="${SIGNED_DIR:-$PROJECT_A_DIR/build/signed}"    # APKs firmados (los que se publican)
TOOLS_DIR="${TOOLS_DIR:-$PROJECT_A_DIR/build/tools}"       # revanced-cli, patches, etc.
META_DIR="${META_DIR:-$PROJECT_A_DIR/build/meta}"          # versiones resueltas, manifests

mkdir -p "$APKS_DIR" "$PATCHED_DIR" "$SIGNED_DIR" "$TOOLS_DIR" "$META_DIR"

# ── Logging ───────────────────────────────────────────────────────────
if [ -t 1 ] && command -v tput >/dev/null 2>&1 && [ "$(tput colors 2>/dev/null || echo 0)" -ge 8 ]; then
  C_RESET="$(tput sgr0)"; C_RED="$(tput setaf 1)"; C_GREEN="$(tput setaf 2)"
  C_YELLOW="$(tput setaf 3)"; C_BLUE="$(tput setaf 4)"; C_DIM="$(tput dim)"
else
  C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_DIM=""
fi

log()   { printf "%s[%s]%s %s\n" "$C_DIM" "$(date +%H:%M:%S)" "$C_RESET" "$*"; }
info()  { printf "%s[INFO]%s  %s\n"  "$C_BLUE"   "$C_RESET" "$*"; }
ok()    { printf "%s[OK]%s    %s\n"  "$C_GREEN"  "$C_RESET" "$*"; }
warn()  { printf "%s[WARN]%s  %s\n"  "$C_YELLOW" "$C_RESET" "$*" >&2; }
err()   { printf "%s[ERR]%s   %s\n"  "$C_RED"    "$C_RESET" "$*" >&2; }
die()   { err "$*"; exit 1; }

# ── Step delimiter ────────────────────────────────────────────────────
step() {
  printf "\n%s═══ %s ═══%s\n" "$C_BLUE" "$*" "$C_RESET"
}

# ── Utilities ─────────────────────────────────────────────────────────
require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Falta comando: $1"
}

# Descarga (curl con retry + resume + fallback).
fetch() {
  local url="$1" out="$2"
  info "GET $url"
  curl -fL --retry 8 --retry-delay 3 --retry-all-errors --connect-timeout 30 \
       -C - -o "$out" "$url"
  ok "Guardado en $out ($(du -h "$out" | cut -f1))"
}

# Último asset de un release de GitHub que matchee un regex sobre el nombre.
# Uso: gh_latest_asset <owner/repo> <regex>
gh_latest_asset() {
  local repo="$1" regex="$2"
  require_cmd gh
  gh release view --repo "$repo" --json assets --jq \
    ".assets[] | select(.name | test(\"$regex\")) | .url" | head -1
}

# Nombre de archivo del último asset que matchee.
gh_latest_asset_name() {
  local repo="$1" regex="$2"
  require_cmd gh
  gh release view --repo "$repo" --json assets --jq \
    ".assets[] | select(.name | test(\"$regex\")) | .name" | head -1
}

# Tag del último release de un repo.
gh_latest_tag() {
  local repo="$1"
  require_cmd gh
  gh release view --repo "$repo" --json tagName --jq '.tagName'
}

# Ruta al tool dentro de TOOLS_DIR o download-and-return.
# Uso: ensure_tool <nombre_local> <owner/repo> <regex>
ensure_tool() {
  local name="$1" repo="$2" regex="$3"
  local dest="$TOOLS_DIR/$name"
  if [ -f "$dest" ]; then
    info "Ya existe $name en $dest — reuso."
    printf "%s" "$dest"
    return 0
  fi
  local url
  url="$(gh_latest_asset "$repo" "$regex")"
  [ -n "$url" ] || die "No encontré asset '$regex' en $repo"
  fetch "$url" "$dest"
  printf "%s" "$dest"
}

# Export selected variables for child scripts (if they re-source this, harmless).
export SCRIPT_DIR PROJECT_A_DIR REPO_ROOT APKS_DIR PATCHED_DIR SIGNED_DIR TOOLS_DIR META_DIR
