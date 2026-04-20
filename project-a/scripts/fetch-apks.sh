#!/usr/bin/env bash
# Descarga los APKs oficiales de YouTube y YouTube Music para arm64-v8a.
#
# Usa `apkeep` como downloader. Fuente primaria: APKMirror (más al día);
# fallback: APKPure.
#
# Requiere:
#  - apkeep (instalado por el workflow, ver A3)
#  - gh (para consultar releases de revanced-patches y ajustar versiones)
#
# Salida: $APKS_DIR/youtube.apk y $APKS_DIR/youtube-music.apk
# Meta: $META_DIR/fetch.json con versiones resueltas y fuentes.

set -euo pipefail
source "$(dirname "$0")/common.sh"

ARCH="${ARCH:-arm64-v8a}"

YT_PKG="com.google.android.youtube"
YTM_PKG="com.google.android.apps.youtube.music"

# ── Resolución de versiones ──────────────────────────────────────────
# Si el workflow pasó YT_VERSION / YTM_VERSION, respetarlas. Si no,
# intentar leer `compatible_packages` del último revanced-patches.rvp.
YT_VERSION="${YT_VERSION:-}"
YTM_VERSION="${YTM_VERSION:-}"

resolve_version_from_patches() {
  local pkg="$1"
  local patches_json="$META_DIR/patches-meta.json"
  # Si el workflow generó patches-meta.json (con revanced-cli list-versions),
  # extraer la versión más alta reportada como compatible.
  if [ -f "$patches_json" ]; then
    jq -r --arg pkg "$pkg" \
      '[.[] | select(.compatiblePackages[]?.name == $pkg) | .compatiblePackages[] | select(.name == $pkg) | .versions[]?] | unique | sort_by(.) | last // empty' \
      "$patches_json" 2>/dev/null || true
  fi
}

if [ -z "$YT_VERSION" ]; then
  YT_VERSION="$(resolve_version_from_patches "$YT_PKG" || true)"
fi
if [ -z "$YTM_VERSION" ]; then
  YTM_VERSION="$(resolve_version_from_patches "$YTM_PKG" || true)"
fi

info "YouTube        → versión=${YT_VERSION:-latest} pkg=$YT_PKG"
info "YouTube Music  → versión=${YTM_VERSION:-latest} pkg=$YTM_PKG"

# ── Downloader ───────────────────────────────────────────────────────
require_cmd apkeep

APKEEP_SOURCE="${APKEEP_SOURCE:-APKMirror}"
APKEEP_OPTS=()
# APKMirror requiere un user-agent no genérico; apkeep ya lo maneja.
# Para APKMirror, opcionalmente se usa token apkmirror; si no, usa scraping.

download_apk() {
  local pkg="$1" version="$2" arch_label="$3" out_name="$4"
  local spec="$pkg"
  if [ -n "$version" ]; then
    spec="$pkg@$version"
  fi

  step "Descargando $pkg desde $APKEEP_SOURCE (arch=$arch_label)"
  local tmp_dir
  tmp_dir="$(mktemp -d)"
  # apkeep acepta --download-source y --options.
  # Para APKMirror: --options arch=<arm64-v8a|armeabi-v7a|x86_64>,min_api=24
  local opts="arch=$arch_label"
  if [ "$APKEEP_SOURCE" = "APKMirror" ]; then
    opts="$opts,min_api=24"
  fi
  if apkeep --download-source "$APKEEP_SOURCE" --options "$opts" \
            -a "$spec" "$tmp_dir"; then
    # apkeep guarda como <pkg>.apk; moverlo al nombre canónico.
    local found
    found="$(find "$tmp_dir" -maxdepth 2 -type f -name '*.apk' | head -1)"
    [ -n "$found" ] || die "apkeep OK pero no encontré APK en $tmp_dir"
    mv "$found" "$APKS_DIR/$out_name"
    ok "Descargado: $APKS_DIR/$out_name ($(du -h "$APKS_DIR/$out_name" | cut -f1))"
    rm -rf "$tmp_dir"
    return 0
  fi
  warn "apkeep con fuente $APKEEP_SOURCE falló para $pkg — intento fallback APKPure."
  rm -rf "$tmp_dir"
  tmp_dir="$(mktemp -d)"
  if apkeep --download-source APKPure -a "$spec" "$tmp_dir"; then
    local found
    found="$(find "$tmp_dir" -maxdepth 2 -type f -name '*.apk' | head -1)"
    [ -n "$found" ] || die "APKPure OK pero no encontré APK."
    mv "$found" "$APKS_DIR/$out_name"
    ok "Fallback APKPure OK: $APKS_DIR/$out_name"
    rm -rf "$tmp_dir"
    return 0
  fi
  die "Ambas fuentes fallaron para $pkg"
}

download_apk "$YT_PKG"  "$YT_VERSION"  "$ARCH" "youtube.apk"
download_apk "$YTM_PKG" "$YTM_VERSION" "$ARCH" "youtube-music.apk"

# ── Escribir meta ────────────────────────────────────────────────────
cat > "$META_DIR/fetch.json" <<EOF
{
  "fetched_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "source_primary": "$APKEEP_SOURCE",
  "arch": "$ARCH",
  "youtube": {
    "package": "$YT_PKG",
    "version": "${YT_VERSION:-latest}",
    "apk": "youtube.apk"
  },
  "youtube_music": {
    "package": "$YTM_PKG",
    "version": "${YTM_VERSION:-latest}",
    "apk": "youtube-music.apk"
  }
}
EOF

ok "Fetch completo. Meta: $META_DIR/fetch.json"
