#!/usr/bin/env bash
# project-e: Descarga Stremio (arm64 + armeabi-v7a), re-firma con keystore
# del repo y publica release ytp-e-stremio-X.X.X si la version cambio.
set -euo pipefail

info()  { echo "[INFO]  $*"; }
ok()    { echo "[OK]    $*"; }
warn()  { echo "[WARN]  $*"; }
die()   { echo "[ERR]   $*" >&2; exit 1; }
step()  { echo ""; echo "──── $* ────"; }

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD_DIR="$REPO_ROOT/project-e/build"
OUT_DIR="$BUILD_DIR/out"
META_DIR="$BUILD_DIR/meta"
TOOLS_DIR="$BUILD_DIR/tools"
mkdir -p "$OUT_DIR" "$META_DIR" "$TOOLS_DIR"

sign_apk() {
  local input="$1" output="$2" label="$3"
  local aligned="/tmp/aligned-$(basename "$input")"
  zipalign -p -f 4 "$input" "$aligned"
  apksigner sign \
    --ks "$KS_PATH" \
    --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
    --ks-key-alias "$ANDROID_KEY_ALIAS" \
    --key-pass "pass:$ANDROID_KEY_PASSWORD" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$output" "$aligned"
  apksigner verify --print-certs "$output" >/dev/null
  rm -f "$aligned"
  ok "$label firmado: $(du -h "$output" | cut -f1)"
}

# ── Preparar keystore ─────────────────────────────────────────────────
step "Preparando keystore"
[ -n "${ANDROID_KEYSTORE_BASE64:-}"   ] || die "Falta ANDROID_KEYSTORE_BASE64"
[ -n "${ANDROID_KEYSTORE_PASSWORD:-}" ] || die "Falta ANDROID_KEYSTORE_PASSWORD"
[ -n "${ANDROID_KEY_ALIAS:-}"         ] || die "Falta ANDROID_KEY_ALIAS"
[ -n "${ANDROID_KEY_PASSWORD:-}"      ] || die "Falta ANDROID_KEY_PASSWORD"
KS_PATH="$TOOLS_DIR/release.jks"
printf "%s" "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KS_PATH"
[ -s "$KS_PATH" ] || die "Keystore vacio"

# ── Obtener version y URLs desde fuente ───────────────────────────────
step "Buscando ultima version de Stremio"
API_URL="https://api.github.com/repos/perpetus/stremio-android/releases/latest"
RELEASE_JSON=$(curl -fsSL -H "Authorization: token ${GH_TOKEN:-}" "$API_URL")
VERSION=$(echo "$RELEASE_JSON" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['tag_name'].lstrip('v'))")
info "Version: $VERSION"

TAG="ytp-e-StremioAleS-${VERSION}"

# ── Chequeo idempotente ───────────────────────────────────────────────
if gh release view "$TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  ok "Release $TAG ya existe. Nada nuevo."
  shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"
  exit 0
fi
info "Version nueva: $VERSION — descargando y firmando."

# ── Descargar arm64-v8a (telefono + TV box moderno) ──────────────────
step "Descargando arm64-v8a"
URL_ARM64=$(echo "$RELEASE_JSON" | python3 -c "
import sys,json
assets = json.load(sys.stdin)['assets']
for a in assets:
    if 'arm64-v8a' in a['name'] and '.apk' in a['name'] and 'mapping' not in a['name']:
        print(a['browser_download_url']); break
")
[ -n "$URL_ARM64" ] || die "No encontre APK arm64-v8a"
RAW_ARM64="/tmp/stremio-arm64-raw.apk"
curl -fsSL --location "$URL_ARM64" -o "$RAW_ARM64"
SIGNED_ARM64="$OUT_DIR/StremioAleS-${VERSION}-arm64.apk"
sign_apk "$RAW_ARM64" "$SIGNED_ARM64" "arm64-v8a"

# ── Descargar armeabi-v7a (dispositivos 32-bit) ───────────────────────
step "Descargando armeabi-v7a"
URL_ARM32=$(echo "$RELEASE_JSON" | python3 -c "
import sys,json
assets = json.load(sys.stdin)['assets']
for a in assets:
    if 'armeabi-v7a' in a['name'] and '.apk' in a['name']:
        print(a['browser_download_url']); break
")
SIGNED_ARM32=""
if [ -n "$URL_ARM32" ]; then
  RAW_ARM32="/tmp/stremio-arm32-raw.apk"
  curl -fsSL --location "$URL_ARM32" -o "$RAW_ARM32"
  SIGNED_ARM32="$OUT_DIR/StremioAleS-${VERSION}-armeabi-v7a.apk"
  sign_apk "$RAW_ARM32" "$SIGNED_ARM32" "armeabi-v7a"
else
  warn "No se encontro APK armeabi-v7a"
fi

shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"

# ── Metadata ──────────────────────────────────────────────────────────
SHA64=$(sha256sum "$SIGNED_ARM64" | cut -d' ' -f1)
cat > "$META_DIR/stremio.json" << METAEOF
{
  "version": "$VERSION",
  "tag": "$TAG",
  "signed_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "sha256_arm64": "$SHA64",
  "apk_arm64": "StremioAleS-${VERSION}-arm64.apk"
}
METAEOF

# ── Publicar release ──────────────────────────────────────────────────
step "Publicando release $TAG"
printf '# Stremio Personal %s\n\n**Firmado con keystore del repo** — misma firma que YouTube, YouTube Music y GmsCore.\n\n## Cual APK instalar?\n\n- **stremio-personal-%s-arm64.apk** → Todos los telefonos modernos (2017+) y TV Boxes modernos (Claro, Xiaomi, etc.)\n- **stremio-personal-%s-armeabi-v7a.apk** → Dispositivos antiguos de 32-bit solamente\n\n## Configurar Torrentio (una sola vez)\n\n1. Abre el navegador de tu telefono y entra a:\n   `https://torrentio.strem.fun/configure`\n2. Configura: Sorting = **By quality then size** | Priority language = **Spanish**\n3. Toca **Install** — se abre Stremio automaticamente con el addon instalado\n\nO pega directamente en Stremio → Addons → URL:\n`https://torrentio.strem.fun/manifest.json`\n\n## Addons recomendados adicionales\n- **Cinemeta** (ya viene) — catalogo de peliculas y series\n- **WatchHub** (ya viene) — muestra en que servicio esta cada contenido\n\n## Integridad\nSHA-256 arm64: `%s`\n' "$VERSION" "$VERSION" "$VERSION" "$SHA64" > /tmp/stremio-notes.md

UPLOAD_ARGS=("$SIGNED_ARM64")
[ -n "$SIGNED_ARM32" ] && [ -f "$SIGNED_ARM32" ] && UPLOAD_ARGS+=("$SIGNED_ARM32")

gh release create "$TAG" \
  --repo "$GITHUB_REPOSITORY" \
  --title "StremioAleS $VERSION" \
  --notes-file /tmp/stremio-notes.md \
  "${UPLOAD_ARGS[@]}" && ok "Release $TAG publicado con ${#UPLOAD_ARGS[@]} APK(s)." \
  || die "Fallo al crear release"
rm -f /tmp/stremio-notes.md
