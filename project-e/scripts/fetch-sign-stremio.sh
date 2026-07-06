#!/usr/bin/env bash
# project-e: Descarga Stremio, renombra app a "H.A.P.E.R Y.C" via apktool,
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
  apksigner sign     --ks "$KS_PATH"     --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD"     --ks-key-alias "$ANDROID_KEY_ALIAS"     --key-pass "pass:$ANDROID_KEY_PASSWORD"     --v1-signing-enabled true     --v2-signing-enabled true     --v3-signing-enabled false     --out "$output" "$aligned"
  apksigner verify --print-certs "$output" >/dev/null
  rm -f "$aligned"
  ok "$label firmado: $(du -h "$output" | cut -f1)"
}

rename_app() {
  local apk_in="$1" apk_out="$2"
  step "Renombrando app a \"$APP_NAME\" con apktool"

  if ! command -v apktool &>/dev/null; then
    info "Instalando apktool..."
    local apktool_jar="/usr/local/bin/apktool.jar"
    local apktool_bin="/usr/local/bin/apktool"
    wget -q "https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar" -O "$apktool_jar"
    printf '#!/bin/bash\njava -jar %s "$@"\n' "$apktool_jar" > "$apktool_bin"
    chmod +x "$apktool_bin"
    ok "apktool instalado"
  fi

  local decoded="/tmp/stremio-decoded-$$"
  apktool d -f -q "$apk_in" -o "$decoded" 2>/dev/null || apktool d -f "$apk_in" -o "$decoded"

  # Cambiar app_name en todos los strings.xml
  local changed=0
  while IFS= read -r -d '' f; do
    if grep -q 'app_name' "$f" 2>/dev/null; then
      sed -i "s|<string name=\"app_name\">.*</string>|<string name=\"app_name\">$APP_NAME</string>|g" "$f"
      changed=$((changed+1))
    fi
  done < <(find "$decoded/res" -name "strings.xml" -print0 2>/dev/null)

  # Cambiar label en AndroidManifest.xml si tiene valor hardcodeado
  if [ -f "$decoded/AndroidManifest.xml" ]; then
    sed -i "s|android:label=\"[^\"]\+\"|android:label=\"$APP_NAME\"|g" "$decoded/AndroidManifest.xml" 2>/dev/null || true
  fi

  info "strings.xml modificados: $changed"
  apktool b -q "$decoded" -o "$apk_out" 2>/dev/null || apktool b "$decoded" -o "$apk_out"
  rm -rf "$decoded"
  ok "APK rebuildeado con nombre $APP_NAME"
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

# ── Version desde GitHub ──────────────────────────────────────────────
step "Buscando ultima version de Stremio"
API_URL="https://api.github.com/repos/perpetus/stremio-android/releases/latest"
RELEASE_JSON=$(curl -fsSL -H "Authorization: token ${GH_TOKEN:-}" "$API_URL")
VERSION=$(echo "$RELEASE_JSON" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['tag_name'].lstrip('v'))")
info "Version: $VERSION"
TAG="ytp-e-${APP_SLUG}-${VERSION}"

# ── Idempotente ───────────────────────────────────────────────────────
if gh release view "$TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  ok "Release $TAG ya existe. Nada nuevo."
  shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"; exit 0
fi
info "Version nueva $VERSION — procesando."

# ── Descargar arm64 ───────────────────────────────────────────────────
step "Descargando arm64-v8a"
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

# Renombrar app con apktool
RENAMED_ARM64="/tmp/stremio-arm64-renamed.apk"
rename_app "$RAW_ARM64" "$RENAMED_ARM64"

SIGNED_ARM64="$OUT_DIR/${APP_SLUG}-${VERSION}-arm64.apk"
sign_apk "$RENAMED_ARM64" "$SIGNED_ARM64" "arm64-v8a"

# ── Descargar armeabi-v7a ─────────────────────────────────────────────
step "Descargando armeabi-v7a"
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

# ── Metadata ──────────────────────────────────────────────────────────
SHA64=$(sha256sum "$SIGNED_ARM64" | cut -d' ' -f1)
cat > "$META_DIR/stremio.json" << METAEOF
{"version":"$VERSION","tag":"$TAG","signed_at":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","sha256_arm64":"$SHA64","apk_arm64":"${APP_SLUG}-${VERSION}-arm64.apk"}
METAEOF

# ── Release ───────────────────────────────────────────────────────────
step "Publicando release $TAG"
printf '# H.A.P.E.R Y.C (Stremio) %s\n\nApp personalizada firmada con tu keystore. App renombrada a **H.A.P.E.R Y.C**.\n\n## Cual APK instalar?\n- **%s-arm64.apk** → Samsung, Huawei, Honor, Claro TV Box (arm64)\n- **%s-armeabi-v7a.apk** → Dispositivos 32-bit\n\n## Agregar Torrentio (maximas peliculas en espanol)\nPega en Stremio → Addons:\n`https://torrentio.strem.fun/providers=yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents,torrentgalaxy,magnetdl,horriblesubs,nyaasi,rutor,rutracker,comando,bludv,micoleaodublado,ilcorsaronero,mejortorrent,wolfmax4k,cinecalidad,besttorrents/sort=qualitysize/language=spanish,multi/manifest.json`\n\nSHA-256: `%s`\n' "$VERSION" "${APP_SLUG}-${VERSION}" "${APP_SLUG}-${VERSION}" "$SHA64" > /tmp/notes.md

UPLOAD_ARGS=("$SIGNED_ARM64")
[ -n "$SIGNED_ARM32" ] && [ -f "$SIGNED_ARM32" ] && UPLOAD_ARGS+=("$SIGNED_ARM32")

gh release create "$TAG"   --repo "$GITHUB_REPOSITORY"   --title "H.A.P.E.R Y.C $VERSION"   --notes-file /tmp/notes.md   "${UPLOAD_ARGS[@]}" && ok "Release $TAG publicado." || die "Fallo al crear release"
rm -f /tmp/notes.md
