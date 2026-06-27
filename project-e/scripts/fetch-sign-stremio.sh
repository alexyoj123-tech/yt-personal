#!/usr/bin/env bash
# project-e: Descarga Stremio oficial, re-firma con keystore del repo,
# publica release ytp-e-stremio-X.X.X si la version cambio.
set -euo pipefail

# ── Colores ──────────────────────────────────────────────────────────
info()  { echo "[INFO]  $*"; }
ok()    { echo "[OK]    $*"; }
warn()  { echo "[WARN]  $*"; }
die()   { echo "[ERR]   $*" >&2; exit 1; }
step()  { echo ""; echo "──── $* ────"; }

# ── Dirs ─────────────────────────────────────────────────────────────
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD_DIR="$REPO_ROOT/project-e/build"
OUT_DIR="$BUILD_DIR/out"
META_DIR="$BUILD_DIR/meta"
TOOLS_DIR="$BUILD_DIR/tools"
mkdir -p "$OUT_DIR" "$META_DIR" "$TOOLS_DIR"

# ── Fuentes oficiales (en orden de preferencia) ──────────────────────
STREMIO_URL_LATEST="https://dl.strem.io/android/latest/stremio.apk"
STREMIO_RAW="/tmp/stremio-raw.apk"

step "Descargando Stremio"
if curl -fsSL --retry 3 --connect-timeout 30 \
     "$STREMIO_URL_LATEST" -o "$STREMIO_RAW"; then
  ok "Descargado desde CDN oficial"
else
  warn "CDN falló — usando última release de perpetus/stremio-android"
  FALLBACK_URL=$(curl -fsSL \
    -H "Authorization: token ${GH_TOKEN:-}" \
    "https://api.github.com/repos/perpetus/stremio-android/releases/latest" \
    | jq -r '.assets[] | select(.name | test("arm64-v8a")) | .browser_download_url')
  [ -n "$FALLBACK_URL" ] || die "No se encontro URL de fallback"
  curl -fsSL --location "$FALLBACK_URL" -o "$STREMIO_RAW"
  ok "Descargado desde fallback: $FALLBACK_URL"
fi

[ -s "$STREMIO_RAW" ] || die "APK descargado esta vacio"
info "Tamaño: $(du -h "$STREMIO_RAW" | cut -f1)"

# ── Detectar version via aapt ─────────────────────────────────────────
step "Detectando version"
if command -v aapt2 &>/dev/null; then
  AAPT_OUT=$(aapt2 dump badging "$STREMIO_RAW" 2>/dev/null || true)
elif command -v aapt &>/dev/null; then
  AAPT_OUT=$(aapt dump badging "$STREMIO_RAW" 2>/dev/null || true)
else
  die "No hay aapt/aapt2 en PATH"
fi

VERSION=$(echo "$AAPT_OUT" | grep -oP "versionName='[^']+'" | cut -d"'" -f2 || true)
if [ -z "$VERSION" ]; then
  VERSION="$(date -u +%Y.%m.%d)"
  warn "No se pudo leer versionName — usando fecha: $VERSION"
fi
info "Version detectada: $VERSION"

TAG="ytp-e-stremio-${VERSION}"

# ── Chequeo idempotente ───────────────────────────────────────────────
step "Verificando si el release ya existe"
if gh release view "$TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  ok "Release $TAG ya existe — nada nuevo. Saliendo."
  exit 0
fi
info "Version nueva detectada: $VERSION — procediendo a firmar y publicar."

# ── Firma ─────────────────────────────────────────────────────────────
step "Firmando con keystore del repo"
[ -n "${ANDROID_KEYSTORE_BASE64:-}"   ] || die "Falta ANDROID_KEYSTORE_BASE64"
[ -n "${ANDROID_KEYSTORE_PASSWORD:-}" ] || die "Falta ANDROID_KEYSTORE_PASSWORD"
[ -n "${ANDROID_KEY_ALIAS:-}"         ] || die "Falta ANDROID_KEY_ALIAS"
[ -n "${ANDROID_KEY_PASSWORD:-}"      ] || die "Falta ANDROID_KEY_PASSWORD"

KS_PATH="$TOOLS_DIR/release.jks"
printf "%s" "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KS_PATH"
[ -s "$KS_PATH" ] || die "Keystore vacio"

ALIGNED="/tmp/stremio-aligned.apk"
SIGNED_APK="$OUT_DIR/stremio-personal-${VERSION}.apk"

zipalign -p -f 4 "$STREMIO_RAW" "$ALIGNED"
info "zipalign OK"

apksigner sign \
  --ks "$KS_PATH" \
  --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
  --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --key-pass "pass:$ANDROID_KEY_PASSWORD" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$SIGNED_APK" \
  "$ALIGNED"

apksigner verify --print-certs "$SIGNED_APK" >/dev/null
ok "Firmado y verificado: $SIGNED_APK ($(du -h "$SIGNED_APK" | cut -f1))"

shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"
rm -f "$ALIGNED"

# ── Metadata ──────────────────────────────────────────────────────────
SHA=$(sha256sum "$SIGNED_APK" | cut -d' ' -f1)
cat > "$META_DIR/stremio.json" << EOF
{
  "version": "$VERSION",
  "tag": "$TAG",
  "signed_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "sha256": "$SHA",
  "apk": "stremio-personal-${VERSION}.apk"
}
EOF

# ── Publicar release ──────────────────────────────────────────────────
step "Publicando release $TAG"
BODY=$(printf '# Stremio Personal %s\n\nStremio re-firmado con el keystore del repo. Misma firma que YouTube, YouTube Music y GmsCore.\n\n## Configuracion inicial (una sola vez)\n\n### 1. Instalar addon Torrentio\nAbre Stremio → Buscar → Addons → pega esta URL:\n```\nhttps://torrentio.strem.fun/providers=yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents/sort=qualitysize/language=spanish,multi/manifest.json\n```\nO entra a https://torrentio.strem.fun, configura a tu gusto, copia el link generado.\n\n### 2. Cuenta Stremio\nCreate una cuenta gratis en https://www.stremio.com — sincroniza tu lista en todos los dispositivos.\n\n## Integridad\nSHA-256: `%s`\n\n## Fuente\nStremio oficial: https://www.stremio.com\n' "$VERSION" "$SHA")

printf '%s' "$BODY" > /tmp/stremio-release-notes.md
gh release create "$TAG" \
  --repo "$GITHUB_REPOSITORY" \
  --title "Stremio Personal $VERSION" \
  --notes-file /tmp/stremio-release-notes.md \
  "$SIGNED_APK" && ok "Release $TAG publicado." || die "Fallo al crear release"

rm -f /tmp/stremio-release-notes.md
