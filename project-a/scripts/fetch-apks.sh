#!/usr/bin/env bash
# Descarga los APKs oficiales de YouTube y YouTube Music compatibles con
# los patches actuales de ReVanced.
#
# Flujo:
#   1) Descarga revanced-cli.jar y revanced-patches.rvp si no existen
#      (para poder extraer `patches-meta.json` y saber qué versión pedir).
#   2) Extrae patches-meta.json si no existe.
#   3) Resuelve la versión MÁS ALTA listada como compatible por cada
#      paquete. Si no hay meta, usa "latest".
#   4) Descarga los APKs con `apkeep --download-source apk-pure`.
#      APKPure a veces sirve XAPK/APKM (zips con splits) → se extrae la
#      base (el .apk más grande).
#
# Requiere: apkeep, java (para list-patches), gh, jq, unzip.
# Env opcionales: ARCH (default arm64-v8a, informativo — apk-pure no
# filtra por arch), YT_VERSION / YTM_VERSION (override),
# APKEEP_SOURCE (default "apk-pure").

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_cmd apkeep
require_cmd jq
require_cmd unzip

ARCH="${ARCH:-arm64-v8a}"
APKEEP_SOURCE="${APKEEP_SOURCE:-apk-pure}"

YT_PKG="com.google.android.youtube"
YTM_PKG="com.google.android.apps.youtube.music"

# ── 1+2. Bootstrap patches-meta.json ─────────────────────────────────
resolve_patches_meta_if_needed() {
  [ -s "$META_DIR/patches-meta.json" ] && return 0
  info "patches-meta.json no existe — extrayendo metadata del .rvp directamente."
  require_cmd gh
  require_cmd unzip
  require_cmd jq

  # Nota: ReVanced/revanced-patches está HTTP 451-bloqueado por GitHub
  # desde 2025. Default switcheado a inotia00/revanced-patches (fork
  # mantenido). Parametrizable con env por si inotia00 también cae.
  # CLI pineada a v5.0.1 (consistente con apply-patches.sh).
  local patches_repo="${REVANCED_PATCHES_REPO:-inotia00/revanced-patches}"

  local patches_rvp
  patches_rvp="$(ensure_tool "revanced-patches.rvp" "$patches_repo" "patches-.*\\.rvp$")"

  # revanced-cli v6 removió `list-patches --with-packages --with-versions
  # --json` — ahora solo soporta --descriptions/--index/--options y no
  # emite JSON con compatibilidad. Alternativa: .rvp es un ZIP, leemos
  # la metadata directo. Probamos rutas candidatas conocidas.
  info "Archivos JSON dentro del .rvp:"
  unzip -l "$patches_rvp" 2>/dev/null | awk 'NR>3 && $4 ~ /\.json$/ {print "  • "$4}' >&2 || true

  local candidates=(
    "patches.json"
    "META-INF/patches.json"
    "META-INF/revanced/patches.json"
    "revanced/patches.json"
    "compatibility.json"
    "compatiblePackages.json"
  )
  local tmp
  tmp="$(mktemp)"
  for path in "${candidates[@]}"; do
    if unzip -p "$patches_rvp" "$path" > "$tmp" 2>/dev/null && \
       [ -s "$tmp" ] && jq empty "$tmp" 2>/dev/null; then
      mv "$tmp" "$META_DIR/patches-meta.json"
      ok "patches-meta.json extraído de '$path' ($(wc -c < "$META_DIR/patches-meta.json") bytes)"
      return 0
    fi
  done
  rm -f "$tmp"

  warn "Ninguna ruta candidata contiene JSON válido en el .rvp — versiones se resolverán como 'latest'."
  warn "(Si querés pinpoint de versión, revisa el listado de arriba y añade la ruta correcta a 'candidates' en fetch-apks.sh.)"
  echo "[]" > "$META_DIR/patches-meta.json"
}

resolve_patches_meta_if_needed

# ── 3. Resolución de versiones ───────────────────────────────────────
# Preferencia: env override > patches-meta > latest
resolve_version_from_meta() {
  local pkg="$1"
  jq -r --arg pkg "$pkg" '
    [.[]
     | select(.compatiblePackages?[]?.name == $pkg)
     | .compatiblePackages[]
     | select(.name == $pkg)
     | .versions[]?]
    | unique
    | sort_by(split(".") | map(tonumber? // 0))
    | last // empty
  ' "$META_DIR/patches-meta.json" 2>/dev/null || true
}

YT_VERSION="${YT_VERSION:-}"
YTM_VERSION="${YTM_VERSION:-}"
[ -z "$YT_VERSION"  ] && YT_VERSION="$(resolve_version_from_meta  "$YT_PKG"  || true)"
[ -z "$YTM_VERSION" ] && YTM_VERSION="$(resolve_version_from_meta "$YTM_PKG" || true)"

info "YouTube        → ${YT_VERSION:-latest}  ($YT_PKG)"
info "YouTube Music  → ${YTM_VERSION:-latest} ($YTM_PKG)"

# ── 4. Descarga + manejo XAPK/APKM ───────────────────────────────────
extract_base_apk() {
  local bundle="$1" dest="$2"
  local extracted
  extracted="$(mktemp -d)"
  info "Bundle detectado ($(basename "$bundle")) — extrayendo base APK"
  unzip -q "$bundle" -d "$extracted"
  # El APK base suele ser el más grande del bundle.
  local base
  base="$(find "$extracted" -maxdepth 3 -type f -name '*.apk' -printf '%s\t%p\n' | sort -rn | head -1 | cut -f2-)"
  [ -n "$base" ] && [ -f "$base" ] || { rm -rf "$extracted"; die "No encontré ningún .apk dentro de $bundle"; }
  mv "$base" "$dest"
  rm -rf "$extracted"
  ok "Base APK extraído: $dest"
}

download_apk() {
  local pkg="$1" version="$2" out_name="$3"
  step "Descargando $pkg desde $APKEEP_SOURCE (versión=${version:-latest})"

  local tmp_dir spec
  tmp_dir="$(mktemp -d)"
  if [ -n "$version" ]; then spec="$pkg@$version"; else spec="$pkg"; fi

  # Primer intento: versión solicitada (si alguna).
  local ok_dl=0
  if apkeep --download-source "$APKEEP_SOURCE" -a "$spec" "$tmp_dir" 2>&1; then
    ok_dl=1
  elif [ -n "$version" ]; then
    warn "apk-pure no tiene $pkg@$version — retry con latest."
    rm -rf "$tmp_dir"
    tmp_dir="$(mktemp -d)"
    apkeep --download-source "$APKEEP_SOURCE" -a "$pkg" "$tmp_dir" 2>&1 && ok_dl=1
  fi
  [ "$ok_dl" = "1" ] || { rm -rf "$tmp_dir"; die "apkeep falló para $pkg desde $APKEEP_SOURCE"; }

  # Encontrar lo descargado (puede ser .apk, .xapk, .apkm).
  local downloaded
  downloaded="$(find "$tmp_dir" -maxdepth 3 -type f \( -name '*.apk' -o -name '*.xapk' -o -name '*.apkm' -o -name '*.apks' \) | head -1)"
  [ -n "$downloaded" ] && [ -f "$downloaded" ] || { rm -rf "$tmp_dir"; die "apkeep OK pero no vi archivo en $tmp_dir"; }

  local dest="$APKS_DIR/$out_name"
  case "$downloaded" in
    *.xapk|*.apkm|*.apks)
      extract_base_apk "$downloaded" "$dest"
      ;;
    *.apk)
      mv "$downloaded" "$dest"
      ;;
  esac
  rm -rf "$tmp_dir"
  ok "Descargado: $dest ($(du -h "$dest" | cut -f1))"
}

download_apk "$YT_PKG"  "$YT_VERSION"  "youtube.apk"
download_apk "$YTM_PKG" "$YTM_VERSION" "youtube-music.apk"

# ── Meta ─────────────────────────────────────────────────────────────
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
