#!/usr/bin/env bash
# project-e: Descarga Stremio, renombra app a "H.A.P.E.R Y.C",
# re-firma con keystore del repo y publica release ytp-e-HAPERYC-X.X.X.
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

APP_NAME="H.A.P.E.R Y.C"
APP_SLUG="HAPERYC"

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
    --v3-signing-enabled false \
    --out "$output" "$aligned"
  apksigner verify --print-certs "$output" >/dev/null
  rm -f "$aligned"
  ok "$label firmado: $(du -h "$output" | cut -f1)"
}

install_apktool() {
  command -v apktool &>/dev/null && return 0
  info "Instalando apktool (ultima version)..."
  local VER
  VER=$(curl -fsSL -H "Authorization: token ${GH_TOKEN:-}" \
    "https://api.github.com/repos/iBotPeaches/Apktool/releases/latest" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['tag_name'].lstrip('v'))")
  wget -q "https://github.com/iBotPeaches/Apktool/releases/download/v${VER}/apktool_${VER}.jar" \
    -O /usr/local/bin/apktool.jar
  printf '#!/bin/bash\njava -jar /usr/local/bin/apktool.jar "$@"\n' > /usr/local/bin/apktool
  chmod +x /usr/local/bin/apktool
  ok "apktool ${VER} instalado"
}

rename_app() {
  local apk_in="$1" apk_out="$2"
  step "Renombrando app a \"${APP_NAME}\" con apktool"
  install_apktool

  local decoded="/tmp/stremio-decoded-$$"
  rm -rf "$decoded"

  # Decode sin recompilar recursos para evitar problemas de SDK
  apktool d -f "$apk_in" -o "$decoded"

  # Obtener targetSdkVersion original para preservarlo
  local orig_sdk
  orig_sdk=$(aapt dump badging "$apk_in" 2>/dev/null | grep "targetSdkVersion" | grep -o "'[0-9]*'" | tr -d "'" || echo "34")
  info "targetSdkVersion original: $orig_sdk"

  # Cambiar app_name en todos los strings.xml (todas las configuraciones de idioma)
  local changed=0
  while IFS= read -r -d '' f; do
    if grep -q 'app_name\|app_label\|application_name' "$f" 2>/dev/null; then
      sed -i \
        -e "s|<string name=\"app_name\">.*</string>|<string name=\"app_name\">${APP_NAME}</string>|g" \
        -e "s|<string name=\"app_label\">.*</string>|<string name=\"app_label\">${APP_NAME}</string>|g" \
        "$f"
      changed=$((changed+1))
    fi
  done < <(find "$decoded/res" -name "strings.xml" -print0 2>/dev/null)
  info "strings.xml modificados: $changed"

  # AndroidManifest.xml: asegurar targetSdkVersion correcto
  if [ -f "$decoded/apktool.yml" ]; then
    python3 -c "
import sys, re
with open('$decoded/apktool.yml') as f:
    content = f.read()
content = re.sub(r\"targetSdkVersion: '[0-9]+'\", \"targetSdkVersion: '$orig_sdk'\", content)
content = re.sub(r\"targetSdkVersion: [0-9]+\", \"targetSdkVersion: $orig_sdk\", content)
with open('$decoded/apktool.yml', 'w') as f:
    f.write(content)
print('[INFO]  apktool.yml targetSdkVersion fijado a $orig_sdk')
"
  fi

  # Rebuild con aapt2
  apktool b --use-aapt2 "$decoded" -o "$apk_out"
  rm -rf "$decoded"
  ok "APK reconstruido: $(du -h "$apk_out" | cut -f1)"
}

# ── Keystore ──────────────────────────────────────────────────────────
step "Preparando keystore"
[ -n "${ANDROID_KEYSTORE_BASE64:-}"   ] || die "Falta ANDROID_KEYSTORE_BASE64"
[ -n "${ANDROID_KEYSTORE_PASSWORD:-}" ] || die "Falta ANDROID_KEYSTORE_PASSWORD"
[ -n "${ANDROID_KEY_ALIAS:-}"         ] || die "Falta ANDROID_KEY_ALIAS"
[ -n "${ANDROID_KEY_PASSWORD:-}"      ] || die "Falta ANDROID_KEY_PASSWORD"
KS_PATH="$TOOLS_DIR/release.jks"
printf "%s" "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KS_PATH"
[ -s "$KS_PATH" ] || die "Keystore vacio"

# ── Version ───────────────────────────────────────────────────────────
step "Buscando ultima version de Stremio"
RELEASE_JSON=$(curl -fsSL -H "Authorization: token ${GH_TOKEN:-}" \
  "https://api.github.com/repos/perpetus/stremio-android/releases/latest")
VERSION=$(echo "$RELEASE_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['tag_name'].lstrip('v'))")
info "Version: $VERSION"
TAG="ytp-e-${APP_SLUG}-${VERSION}"

if gh release view "$TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  ok "Release $TAG ya existe. Nada nuevo."
  shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"; exit 0
fi
info "Version nueva $VERSION — procesando."

# ── arm64-v8a ─────────────────────────────────────────────────────────
step "Procesando arm64-v8a"
URL_ARM64=$(echo "$RELEASE_JSON" | python3 -c "
import sys,json
assets=json.load(sys.stdin)['assets']
for a in assets:
    if 'arm64-v8a' in a['name'] and '.apk' in a['name'] and 'mapping' not in a['name']:
        print(a['browser_download_url']); break
")
[ -n "$URL_ARM64" ] || die "No encontre APK arm64-v8a"
RAW_ARM64="/tmp/stremio-arm64-raw.apk"
curl -fsSL --location "$URL_ARM64" -o "$RAW_ARM64"
RENAMED_ARM64="/tmp/stremio-arm64-renamed.apk"
rename_app "$RAW_ARM64" "$RENAMED_ARM64"
SIGNED_ARM64="$OUT_DIR/${APP_SLUG}-${VERSION}-arm64.apk"
sign_apk "$RENAMED_ARM64" "$SIGNED_ARM64" "arm64-v8a"

# ── armeabi-v7a ───────────────────────────────────────────────────────
step "Procesando armeabi-v7a"
URL_ARM32=$(echo "$RELEASE_JSON" | python3 -c "
import sys,json
assets=json.load(sys.stdin)['assets']
for a in assets:
    if 'armeabi-v7a' in a['name'] and '.apk' in a['name']:
        print(a['browser_download_url']); break
")
SIGNED_ARM32=""
if [ -n "$URL_ARM32" ]; then
  RAW_ARM32="/tmp/stremio-arm32-raw.apk"
  curl -fsSL --location "$URL_ARM32" -o "$RAW_ARM32"
  RENAMED_ARM32="/tmp/stremio-arm32-renamed.apk"
  rename_app "$RAW_ARM32" "$RENAMED_ARM32"
  SIGNED_ARM32="$OUT_DIR/${APP_SLUG}-${VERSION}-armeabi-v7a.apk"
  sign_apk "$RENAMED_ARM32" "$SIGNED_ARM32" "armeabi-v7a"
fi

shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"

# ── Metadata + Release ────────────────────────────────────────────────
SHA64=$(sha256sum "$SIGNED_ARM64" | cut -d' ' -f1)
cat > "$META_DIR/stremio.json" << METAEOF
{"version":"$VERSION","tag":"$TAG","signed_at":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","sha256_arm64":"$SHA64","apk_arm64":"${APP_SLUG}-${VERSION}-arm64.apk"}
METAEOF

step "Publicando release $TAG"
cat > /tmp/notes.md << NOTESEOF
# H.A.P.E.R Y.C $VERSION
App de streaming personalizada. Firmada con keystore propio.

## Instalar
- **${APP_SLUG}-${VERSION}-arm64.apk** para Samsung, Huawei, Honor, Claro TV Box
- **${APP_SLUG}-${VERSION}-armeabi-v7a.apk** para dispositivos 32-bit

## Torrentio (pegar en Addons):
\`https://torrentio.strem.fun/manifest.json\`
Para configurar maximas peliculas en espanol: entra a https://torrentio.strem.fun/configure

SHA-256: \`$SHA64\`
NOTESEOF

UPLOAD_ARGS=("$SIGNED_ARM64")
[ -n "$SIGNED_ARM32" ] && [ -f "$SIGNED_ARM32" ] && UPLOAD_ARGS+=("$SIGNED_ARM32")
gh release create "$TAG" \
  --repo "$GITHUB_REPOSITORY" \
  --title "H.A.P.E.R Y.C $VERSION" \
  --notes-file /tmp/notes.md \
  "${UPLOAD_ARGS[@]}" && ok "Release $TAG publicado."
rm -f /tmp/notes.md
