#!/usr/bin/env bash
# project-e: Descarga Stremio, renombra a "H.A.P.E.R Y.C" via Python binary patch,
# firma con keystore y publica ytp-e-HAPERYC-X.X.X
set -euo pipefail

info() { echo "[INFO]  $*"; }
ok()   { echo "[OK]    $*"; }
die()  { echo "[ERR]   $*" >&2; exit 1; }
step() { echo ""; echo "──── $* ────"; }

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD_DIR="$REPO_ROOT/project-e/build"
OUT_DIR="$BUILD_DIR/out"; META_DIR="$BUILD_DIR/meta"; TOOLS_DIR="$BUILD_DIR/tools"
mkdir -p "$OUT_DIR" "$META_DIR" "$TOOLS_DIR"

APP_NAME="H.A.P.E.R Y.C"; APP_SLUG="HAPERYC"

sign_apk() {
  local aligned="/tmp/aln-$$"; zipalign -p -f 4 "$1" "$aligned"
  apksigner sign \
    --ks "$KS_PATH" --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
    --ks-key-alias "$ANDROID_KEY_ALIAS" --key-pass "pass:$ANDROID_KEY_PASSWORD" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
    --out "$2" "$aligned"
  apksigner verify --print-certs "$2" >/dev/null
  rm -f "$aligned"; ok "$3: $(du -h "$2" | cut -f1)"
}

patch_apk() {
  local raw="$1" out="$2"
  python3 << PYEOF
import sys, zipfile, struct, shutil, os

apk_in  = "$raw"
apk_out = "$out"
old_b = "Stremio".encode("utf-8")
new_b = "$APP_NAME".encode("utf-8")

def patch_data(data):
    result = bytearray(data)
    old_len, new_len = len(old_b), len(new_b)

    # UTF-8 string pool pattern: [len_utf16][len_utf8][bytes][0x00]
    old_p = bytes([old_len, old_len]) + old_b + b"\x00"
    new_p = bytes([new_len, new_len]) + new_b + b"\x00"
    if old_p in bytes(result):
        result = bytearray(bytes(result).replace(old_p, new_p))
        print("[OK]  UTF-8 pool patched")

    # UTF-16LE string pool: [len as LE uint16][utf16 chars][0x00 0x00]
    old16 = struct.pack("<H", old_len) + old_b.decode().encode("utf-16-le") + b"\x00\x00"
    new16 = struct.pack("<H", new_len) + new_b.decode().encode("utf-16-le") + b"\x00\x00"
    if old16 in bytes(result):
        result = bytearray(bytes(result).replace(old16, new16))
        print("[OK]  UTF-16 pool patched")

    return bytes(result)

tmp = apk_out + ".tmp"
with zipfile.ZipFile(apk_in, "r") as zin, zipfile.ZipFile(tmp, "w") as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == "resources.arsc":
            data = patch_data(data)
        out_info = zipfile.ZipInfo(item.filename, date_time=item.date_time)
        out_info.compress_type = item.compress_type
        out_info.external_attr = item.external_attr
        out_info.create_system = item.create_system
        zout.writestr(out_info, data)

os.replace(tmp, apk_out)
print("[OK]  APK parchado")
PYEOF
}

process_apk() {
  local raw="$1" out="$2" label="$3"
  local patched="/tmp/patched-$label.apk"
  patch_apk "$raw" "$patched"
  sign_apk "$patched" "$out" "$label"
  rm -f "$patched"
}

# ── Keystore ──────────────────────────────────────────────────────────
step "Keystore"
[ -n "${ANDROID_KEYSTORE_BASE64:-}" ] || die "Falta ANDROID_KEYSTORE_BASE64"
KS_PATH="$TOOLS_DIR/release.jks"
printf "%s" "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KS_PATH"

# ── Version ───────────────────────────────────────────────────────────
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
assets=json.load(sys.stdin)['assets']
for a in assets:
    if '${q}' in a['name'] and '.apk' in a['name'] and 'mapping' not in a['name']:
        print(a['browser_download_url']); break
"
}

# ── arm64 ─────────────────────────────────────────────────────────────
step "arm64-v8a"
URL64=$(get_url "arm64-v8a"); [ -n "$URL64" ] || die "No hay APK arm64"
curl -fsSL --location "$URL64" -o /tmp/raw64.apk
SIGNED64="$OUT_DIR/${APP_SLUG}-${VERSION}-arm64.apk"
process_apk /tmp/raw64.apk "$SIGNED64" "arm64"

# ── armeabi-v7a ───────────────────────────────────────────────────────
step "armeabi-v7a"
URL32=$(get_url "armeabi-v7a"); SIGNED32=""
if [ -n "$URL32" ]; then
  curl -fsSL --location "$URL32" -o /tmp/raw32.apk
  SIGNED32="$OUT_DIR/${APP_SLUG}-${VERSION}-armeabi-v7a.apk"
  process_apk /tmp/raw32.apk "$SIGNED32" "armeabi"
fi

shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"

# ── Release ───────────────────────────────────────────────────────────
SHA64=$(sha256sum "$SIGNED64" | cut -d' ' -f1)
printf '{"version":"%s","tag":"%s","sha256":"%s"}\n' "$VERSION" "$TAG" "$SHA64" \
  > "$META_DIR/stremio.json"

gh release create "$TAG" --repo "$GITHUB_REPOSITORY" \
  --title "H.A.P.E.R Y.C $VERSION" \
  --notes "H.A.P.E.R Y.C $VERSION — Firmado con keystore propio. SHA-256: $SHA64" \
  "$SIGNED64" ${SIGNED32:+"$SIGNED32"}
ok "Publicado: $TAG"
