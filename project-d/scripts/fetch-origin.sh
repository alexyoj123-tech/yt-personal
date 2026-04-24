#!/usr/bin/env bash
# Fetch YouTube Origin for Google TV desde gitlab.com/energylove/originproject.
#
# Flujo:
#   1. Lista 'Releases/' via GitLab tree API, encuentra la versión más reciente
#      CON binarios .rar (skip placeholders vacíos como v1.4.8 en abril 2026).
#   2. Descarga el archivo arm64-v8a (~78 MB compressed, ~198 MB uncompressed).
#   3. Extrae con unrar a project-d/build/extracted/.
#   4. Renombra a youtube-origin-<ver>.apk en project-d/build/out/.
#   5. Genera project-d/build/meta/fetch.json con metadata.
#
# IMPORTANTE: Este script NO firma el APK. El APK se distribuye con la firma
# original de energylove — re-firmar rompería Widevine DRM (attestation
# ligada a signature). Ver docs/MIGRATION-GUIDE-TV.md §firma.
#
# Env overrides (todos opcionales):
#   ORIGIN_PROJECT_ID — ID numérico de GitLab. Default 62144433.
#   ORIGIN_ARCH       — arm64-v8a (default) / armeabi-v7a / x86_64.

set -euo pipefail

# Reutilizamos helpers de logging del proyecto A (info, ok, die, step).
# common.sh calcula REPO_ROOT correctamente desde su propia ubicación,
# independiente de desde dónde se invoque este script.
source "$(dirname "$0")/../../project-a/scripts/common.sh"

require_cmd curl
require_cmd jq
require_cmd unrar

PROJECT_ID="${ORIGIN_PROJECT_ID:-62144433}"
ARCH="${ORIGIN_ARCH:-arm64-v8a}"

BUILD_DIR="$REPO_ROOT/project-d/build"
OUT_DIR="$BUILD_DIR/out"
EXTRACT_DIR="$BUILD_DIR/extracted"
PROJECT_D_META="$BUILD_DIR/meta"
mkdir -p "$OUT_DIR" "$EXTRACT_DIR" "$PROJECT_D_META"

GITLAB_API="https://gitlab.com/api/v4/projects/$PROJECT_ID"
GITLAB_RAW="https://gitlab.com/energylove/originproject/-/raw/main"

# ── 1. Listar versiones disponibles en Releases/ ─────────────────────
step "Listando versiones en Releases/ (GitLab tree API)"
versions_json="$PROJECT_D_META/_versions.json"
curl -fsSL --retry 5 --retry-delay 3 --retry-all-errors \
  "$GITLAB_API/repository/tree?path=Releases&per_page=100" \
  -o "$versions_json"

versions_sorted=$(jq -r '.[] | select(.type == "tree") | .name' "$versions_json" | sort -V)
if [ -z "$versions_sorted" ]; then
  die "No hay directorios en Releases/ — estructura del repo cambió?"
fi
info "Versiones visibles: $(echo "$versions_sorted" | tr '\n' ' ' | sed 's/  *$//')"

# ── 2. Buscar última versión CON archivos .rar (skip placeholders) ──
step "Buscando última versión con binarios (skip placeholders vacíos)"
latest_with_binaries=""
latest_rar_file=""
for v in $(echo "$versions_sorted" | tac); do
  tree_json="$PROJECT_D_META/_tree_${v}.json"
  if ! curl -fsSL --retry 3 --retry-delay 2 \
       "$GITLAB_API/repository/tree?path=Releases/$v&per_page=50" \
       -o "$tree_json" 2>/dev/null; then
    warn "No pude listar Releases/$v — skip"
    continue
  fi
  # Buscar archivo .rar para la arch objetivo
  rar_file=$(jq -r --arg arch "$ARCH" '
    .[] | select(.type == "blob")
        | select(.name | test("_" + $arch + "_release.*\\.rar$"))
        | .name
  ' "$tree_json" | head -1)
  if [ -n "$rar_file" ]; then
    latest_with_binaries="$v"
    latest_rar_file="$rar_file"
    ok "Encontrado: Releases/$v/$rar_file"
    break
  fi
  info "  $v: sin archivo .rar para $ARCH (placeholder o arch distinta) — skip"
done

[ -n "$latest_with_binaries" ] && [ -n "$latest_rar_file" ] || \
  die "No se encontró ninguna versión con archivo .rar para arch=$ARCH"

# ── 3. Descargar el RAR ──────────────────────────────────────────────
step "Descargando $latest_rar_file desde $latest_with_binaries"
rar_url="$GITLAB_RAW/Releases/$latest_with_binaries/$latest_rar_file"
rar_path="$BUILD_DIR/$latest_rar_file"

if [ -s "$rar_path" ]; then
  info "Ya existe $rar_path — reuso."
else
  fetch "$rar_url" "$rar_path"
fi

# Validar magic bytes (RAR5 = 'Rar!' + specific sequence)
magic=$(head -c 8 "$rar_path" | xxd -p 2>/dev/null || echo "")
if [[ "$magic" != 526172211a070100* ]]; then
  die "El archivo descargado no tiene magic bytes RAR5. Primeros 8 bytes: $magic"
fi
ok "RAR válido: $(du -h "$rar_path" | cut -f1)"

# ── 4. Extraer APK ───────────────────────────────────────────────────
step "Extrayendo APK con unrar"
rm -rf "$EXTRACT_DIR"/*  # clean para no confundir con extracciones anteriores
unrar x -o+ "$rar_path" "$EXTRACT_DIR/" > /dev/null

apk_in_rar=$(find "$EXTRACT_DIR" -maxdepth 3 -type f -name '*.apk' | head -1)
[ -n "$apk_in_rar" ] && [ -f "$apk_in_rar" ] || die "No hay .apk dentro del RAR tras extraer"

# Validar magic bytes del APK (ZIP = PK)
apk_magic=$(head -c 4 "$apk_in_rar" | xxd -p 2>/dev/null || echo "")
[[ "$apk_magic" == 504b0304* ]] || die "El APK extraído no es un ZIP válido. Magic: $apk_magic"

# ── 5. Copiar al output canónico + meta ──────────────────────────────
version_clean="${latest_with_binaries#v}"   # v1.4.6 → 1.4.6
out_apk="$OUT_DIR/youtube-origin-$version_clean.apk"
cp -f "$apk_in_rar" "$out_apk"
ok "APK copiado: $out_apk ($(du -h "$out_apk" | cut -f1))"

sha256=$(sha256sum "$out_apk" | awk '{print $1}')
info "SHA-256 del APK: $sha256"

cat > "$PROJECT_D_META/fetch.json" <<EOF
{
  "fetched_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "source": "gitlab.com/energylove/originproject",
  "version_tag": "$latest_with_binaries",
  "version_clean": "$version_clean",
  "arch": "$ARCH",
  "rar_source": "$rar_url",
  "rar_filename": "$latest_rar_file",
  "apk_filename": "youtube-origin-$version_clean.apk",
  "apk_sha256": "$sha256",
  "signer_notice": "Firma original de energylove — NO re-firmado. Re-firmar rompería Widevine DRM (attestation ligada a signature). Única app del repo con firma externa. Ver docs/MIGRATION-GUIDE-TV.md §firma."
}
EOF

ok "Fetch completo. Meta: $PROJECT_D_META/fetch.json"
