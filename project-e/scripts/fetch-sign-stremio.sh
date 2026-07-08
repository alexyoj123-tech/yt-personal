#!/usr/bin/env bash
# project-e: Descarga Stremio, firma con keystore, publica HAPERYC-X.X.X
set -euo pipefail
info() { echo "[INFO]  $*"; }
ok()   { echo "[OK]    $*"; }
die()  { echo "[ERR]   $*" >&2; exit 1; }
step() { echo ""; echo "──── $* ────"; }

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="$REPO_ROOT/project-e/build/out"
META_DIR="$REPO_ROOT/project-e/build/meta"
TOOLS_DIR="$REPO_ROOT/project-e/build/tools"
mkdir -p "$OUT_DIR" "$META_DIR" "$TOOLS_DIR"

APP_SLUG="HAPERYC"

sign_apk() {
  local aligned="/tmp/aln-$$"
  zipalign -p -f 4 "$1" "$aligned"
  apksigner sign \
    --ks "$KS_PATH" --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
    --ks-key-alias "$ANDROID_KEY_ALIAS" --key-pass "pass:$ANDROID_KEY_PASSWORD" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
    --out "$2" "$aligned"
  apksigner verify --print-certs "$2" >/dev/null
  rm -f "$aligned"
  ok "$3: $(du -h "$2" | cut -f1)"
}

step "Keystore"
[ -n "${ANDROID_KEYSTORE_BASE64:-}" ] || die "Falta ANDROID_KEYSTORE_BASE64"
KS_PATH="$TOOLS_DIR/release.jks"
printf "%s" "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KS_PATH"

step "Version"
REL=$(curl -fsSL -H "Authorization: token ${GH_TOKEN:-}" \
  "https://api.github.com/repos/perpetus/stremio-android/releases/latest")
VERSION=$(echo "$REL" | python3 -c "import sys,json; print(json.load(sys.stdin)['tag_name'].lstrip('v'))")
TAG="ytp-e-${APP_SLUG}-${VERSION}"
info "Version: $VERSION | Tag: $TAG"

if gh release view "$TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  ok "Ya existe."; shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"; exit 0
fi

get_url() {
  local q="$1"
  echo "$REL" | python3 -c "
import sys,json
for a in json.load(sys.stdin)['assets']:
    if '${q}' in a['name'] and '.apk' in a['name'] and 'mapping' not in a['name']:
        print(a['browser_download_url']); break
"
}

step "arm64-v8a"
URL64=$(get_url "arm64-v8a"); [ -n "$URL64" ] || die "No APK arm64"
curl -fsSL --location "$URL64" -o /tmp/raw64.apk
SIGNED64="$OUT_DIR/${APP_SLUG}-${VERSION}-arm64.apk"
sign_apk /tmp/raw64.apk "$SIGNED64" "arm64"

step "armeabi-v7a"
URL32=$(get_url "armeabi-v7a"); SIGNED32=""
if [ -n "$URL32" ]; then
  curl -fsSL --location "$URL32" -o /tmp/raw32.apk
  SIGNED32="$OUT_DIR/${APP_SLUG}-${VERSION}-armeabi-v7a.apk"
  sign_apk /tmp/raw32.apk "$SIGNED32" "armeabi"
fi

shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"

SHA64=$(sha256sum "$SIGNED64" | cut -d' ' -f1)
printf '{"version":"%s","tag":"%s","sha256":"%s"}\n' "$VERSION" "$TAG" "$SHA64" > "$META_DIR/stremio.json"

UPLOADS=("$SIGNED64")
[ -n "$SIGNED32" ] && [ -f "$SIGNED32" ] && UPLOADS+=("$SIGNED32")

gh release create "$TAG" --repo "$GITHUB_REPOSITORY" \
  --title "HAPERYC $VERSION - H.A.P.E.R Y.C" \
  --notes "H.A.P.E.R Y.C $VERSION — firmado con keystore propio. SHA-256: $SHA64" \
  "${UPLOADS[@]}"
ok "Publicado: $TAG"
