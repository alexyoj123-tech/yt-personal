#!/usr/bin/env bash
# Firma los APKs parcheados con el keystore release del repo.
#
# Lee el keystore de $ANDROID_KEYSTORE_BASE64 (Secret de GitHub Actions),
# lo decodifica a $TOOLS_DIR/release.jks, y usa apksigner para firmar.
#
# Requiere:
#   - apksigner (Android SDK build-tools, instalado por el workflow A3)
#   - zipalign (mismo paquete, se usa antes de firmar)
#   - Variables de entorno:
#       ANDROID_KEYSTORE_BASE64
#       ANDROID_KEYSTORE_PASSWORD
#       ANDROID_KEY_ALIAS
#       ANDROID_KEY_PASSWORD
#
# Salida: APKs firmados en $SIGNED_DIR/, nombres finales:
#   youtube-personal-<version>.apk
#   youtube-music-personal-<version>.apk
#   gmscore-<version>.apk

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_cmd apksigner
require_cmd zipalign
require_cmd jq

[ -n "${ANDROID_KEYSTORE_BASE64:-}"   ] || die "Falta env ANDROID_KEYSTORE_BASE64"
[ -n "${ANDROID_KEYSTORE_PASSWORD:-}" ] || die "Falta env ANDROID_KEYSTORE_PASSWORD"
[ -n "${ANDROID_KEY_ALIAS:-}"         ] || die "Falta env ANDROID_KEY_ALIAS"
[ -n "${ANDROID_KEY_PASSWORD:-}"      ] || die "Falta env ANDROID_KEY_PASSWORD"

# ── Materializar keystore ────────────────────────────────────────────
KS_PATH="$TOOLS_DIR/release.jks"
printf "%s" "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KS_PATH"
[ -s "$KS_PATH" ] || die "Keystore vacío tras base64 -d"
info "Keystore materializado: $KS_PATH ($(du -h "$KS_PATH" | cut -f1))"

# ── Leer versiones desde meta/fetch.json ─────────────────────────────
YT_VERSION="$(jq -r '.youtube.version' "$META_DIR/fetch.json")"
YTM_VERSION="$(jq -r '.youtube_music.version' "$META_DIR/fetch.json")"
GMS_VERSION="$(jq -r '.gmscore_version' "$META_DIR/patch.json" 2>/dev/null || echo 'unknown')"

# Fecha para el tag si version=="latest"
DATE_TAG="$(date -u +%Y.%m.%d)"
[ "$YT_VERSION"  = "latest" ] && YT_VERSION="$DATE_TAG"
[ "$YTM_VERSION" = "latest" ] && YTM_VERSION="$DATE_TAG"

# ── Función: zipalign + sign ─────────────────────────────────────────
sign_apk() {
  local in_apk="$1" out_apk="$2" label="$3"
  [ -f "$in_apk" ] || die "No encuentro $in_apk"
  step "Firmando $label → $(basename "$out_apk")"

  local aligned="${in_apk%.apk}-aligned.apk"
  zipalign -p -f 4 "$in_apk" "$aligned"
  info "zipalign OK: $aligned"

  apksigner sign \
    --ks "$KS_PATH" \
    --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
    --ks-key-alias "$ANDROID_KEY_ALIAS" \
    --key-pass "pass:$ANDROID_KEY_PASSWORD" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$out_apk" \
    "$aligned"

  apksigner verify --print-certs "$out_apk" >/dev/null
  ok "$label firmado y verificado: $(du -h "$out_apk" | cut -f1)"
  rm -f "$aligned"
}

sign_apk "$PATCHED_DIR/youtube-patched.apk" \
         "$SIGNED_DIR/youtube-personal-${YT_VERSION}.apk" "YouTube"

sign_apk "$PATCHED_DIR/youtube-music-patched.apk" \
         "$SIGNED_DIR/youtube-music-personal-${YTM_VERSION}.apk" "YT Music"

# GmsCore ya viene firmado por upstream, pero re-firmamos con la misma
# clave para mantener consistencia de firma en el dispositivo del dueño.
sign_apk "$PATCHED_DIR/gmscore.apk" \
         "$SIGNED_DIR/gmscore-${GMS_VERSION}.apk" "GmsCore"

# SmartTube: si apply-patches.sh lo descargó, re-firmamos con nuestro
# keystore (consistencia). El tag de SmartTube lo recogemos de meta/patch.json.
if [ -f "$PATCHED_DIR/smarttube.apk" ]; then
  SMARTTUBE_VERSION="$(jq -r '.smarttube_version // "unknown"' "$META_DIR/patch.json")"
  sign_apk "$PATCHED_DIR/smarttube.apk" \
           "$SIGNED_DIR/smarttube-${SMARTTUBE_VERSION}.apk" "SmartTube"
fi

# ── Limpieza: borrar keystore materializado ──────────────────────────
shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"
info "Keystore borrado de disco."

# ── Meta del signing ─────────────────────────────────────────────────
SMARTTUBE_VERSION="${SMARTTUBE_VERSION:-unknown}"

cat > "$META_DIR/sign.json" <<EOF
{
  "signed_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "keystore_alias": "$ANDROID_KEY_ALIAS",
  "signed_apks": [
    "youtube-personal-${YT_VERSION}.apk",
    "youtube-music-personal-${YTM_VERSION}.apk",
    "gmscore-${GMS_VERSION}.apk",
    "smarttube-${SMARTTUBE_VERSION}.apk"
  ],
  "versions": {
    "youtube": "${YT_VERSION}",
    "youtube_music": "${YTM_VERSION}",
    "gmscore": "${GMS_VERSION}",
    "smarttube": "${SMARTTUBE_VERSION}"
  }
}
EOF

ok "Sign completo. Meta: $META_DIR/sign.json"
