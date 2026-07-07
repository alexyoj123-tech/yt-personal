#!/usr/bin/env bash
# project-e: Descarga Stremio, renombra a "H.A.P.E.R Y.C", firma y publica.
set -euo pipefail

info()  { echo "[INFO]  $*"; }
ok()    { echo "[OK]    $*"; }
die()   { echo "[ERR]   $*" >&2; exit 1; }
step()  { echo ""; echo "──── $* ────"; }

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD_DIR="$REPO_ROOT/project-e/build"
OUT_DIR="$BUILD_DIR/out"; META_DIR="$BUILD_DIR/meta"; TOOLS_DIR="$BUILD_DIR/tools"
mkdir -p "$OUT_DIR" "$META_DIR" "$TOOLS_DIR"

APP_NAME="H.A.P.E.R Y.C"; APP_SLUG="HAPERYC"

sign_apk() {
  local aligned="/tmp/aligned-$(basename "$1")"
  zipalign -p -f 4 "$1" "$aligned"
  apksigner sign \
    --ks "$KS_PATH" --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
    --ks-key-alias "$ANDROID_KEY_ALIAS" --key-pass "pass:$ANDROID_KEY_PASSWORD" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
    --out "$2" "$aligned"
  apksigner verify --print-certs "$2" >/dev/null
  rm -f "$aligned"
  ok "$3 firmado: $(du -h "$2" | cut -f1)"
}

install_apktool() {
  command -v apktool &>/dev/null && return 0
  local VER; VER=$(curl -fsSL -H "Authorization: token ${GH_TOKEN:-}" \
    "https://api.github.com/repos/iBotPeaches/Apktool/releases/latest" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['tag_name'].lstrip('v'))")
  wget -q "https://github.com/iBotPeaches/Apktool/releases/download/v${VER}/apktool_${VER}.jar" \
    -O /usr/local/bin/apktool.jar
  printf '#!/bin/bash\njava -jar /usr/local/bin/apktool.jar "$@"\n' > /usr/local/bin/apktool
  chmod +x /usr/local/bin/apktool
  ok "apktool ${VER}"
}

rename_app() {
  local apk_in="$1" apk_out="$2"
  step "Renombrando a \"${APP_NAME}\""
  install_apktool
  local decoded="/tmp/dec-$$"; rm -rf "$decoded"

  # -c = copia recursos originales sin redecodificarlos (evita fallo en React Native)
  apktool d -f -c "$apk_in" -o "$decoded"

  # Cambiar nombre en strings.xml de todas las configuraciones
  local n=0
  while IFS= read -r -d '' f; do
    sed -i \
      -e "s|<string name=\"app_name\">[^<]*</string>|<string name=\"app_name\">${APP_NAME}</string>|g" \
      -e "s|<string name=\"app_label\">[^<]*</string>|<string name=\"app_label\">${APP_NAME}</string>|g" \
      "$f" 2>/dev/null && n=$((n+1))
  done < <(find "$decoded/res" -name "strings.xml" -print0 2>/dev/null)
  info "strings modificados: $n"

  # Rebuild — con -c los recursos se copian tal cual, sin recompilar
  apktool b "$decoded" -o "$apk_out"
  rm -rf "$decoded"
  ok "APK renombrado: $(du -h "$apk_out" | cut -f1)"
}

# ── Keystore ──────────────────────────────────────────────────────────
step "Keystore"
[ -n "${ANDROID_KEYSTORE_BASE64:-}" ] || die "Falta ANDROID_KEYSTORE_BASE64"
KS_PATH="$TOOLS_DIR/release.jks"
printf "%s" "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KS_PATH"

# ── Version ───────────────────────────────────────────────────────────
step "Version"
RELEASE_JSON=$(curl -fsSL -H "Authorization: token ${GH_TOKEN:-}" \
  "https://api.github.com/repos/perpetus/stremio-android/releases/latest")
VERSION=$(echo "$RELEASE_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['tag_name'].lstrip('v'))")
TAG="ytp-e-${APP_SLUG}-${VERSION}"
info "Version: $VERSION | Tag: $TAG"

if gh release view "$TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  ok "Release $TAG ya existe."; shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"; exit 0
fi

get_url() {
  echo "$RELEASE_JSON" | python3 -c "
import sys,json; assets=json.load(sys.stdin)['assets']
for a in assets:
    if '$1' in a['name'] and '.apk' in a['name'] and 'mapping' not in a['name']:
        print(a['browser_download_url']); break"
}

# ── arm64 ─────────────────────────────────────────────────────────────
step "arm64-v8a"
URL64=$(get_url "arm64-v8a"); [ -n "$URL64" ] || die "No hay APK arm64"
curl -fsSL --location "$URL64" -o /tmp/raw64.apk
rename_app /tmp/raw64.apk /tmp/renamed64.apk
SIGNED64="$OUT_DIR/${APP_SLUG}-${VERSION}-arm64.apk"
sign_apk /tmp/renamed64.apk "$SIGNED64" "arm64"

# ── armeabi ───────────────────────────────────────────────────────────
step "armeabi-v7a"
URL32=$(get_url "armeabi-v7a"); SIGNED32=""
if [ -n "$URL32" ]; then
  curl -fsSL --location "$URL32" -o /tmp/raw32.apk
  rename_app /tmp/raw32.apk /tmp/renamed32.apk
  SIGNED32="$OUT_DIR/${APP_SLUG}-${VERSION}-armeabi-v7a.apk"
  sign_apk /tmp/renamed32.apk "$SIGNED32" "armeabi"
fi

shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"

# ── Release ───────────────────────────────────────────────────────────
SHA64=$(sha256sum "$SIGNED64" | cut -d' ' -f1)
printf '{"version":"%s","tag":"%s","sha256_arm64":"%s"}\n' \
  "$VERSION" "$TAG" "$SHA64" > "$META_DIR/stremio.json"

cat > /tmp/notes.md << EOF
# H.A.P.E.R Y.C $VERSION
App de streaming. Firmada con tu keystore personal.

## Que APK instalar
- **${APP_SLUG}-${VERSION}-arm64.apk** — Samsung, Huawei, Honor, TV Box Claro
- **${APP_SLUG}-${VERSION}-armeabi-v7a.apk** — dispositivos 32-bit

## Agregar Torrentio (peliculas en espanol)
Copia y pega en Stremio → Addons:
\`https://torrentio.strem.fun/manifest.json\`
Luego toca Configure → activa idioma Spanish → reinstala.

SHA-256: \`$SHA64\`
EOF

UPLOADS=("$SIGNED64")
[ -n "$SIGNED32" ] && [ -f "$SIGNED32" ] && UPLOADS+=("$SIGNED32")
gh release create "$TAG" --repo "$GITHUB_REPOSITORY" \
  --title "H.A.P.E.R Y.C $VERSION" --notes-file /tmp/notes.md "${UPLOADS[@]}"
ok "Release $TAG publicado."
