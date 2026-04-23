#!/usr/bin/env bash
# Descarga los APKs oficiales de YouTube y YouTube Music compatibles con
# los patches actuales de ReVanced desde APKMirror.
#
# Flujo:
#   1) Intenta extraer metadata de compat del .rvp para resolver versiones.
#      Si no se encuentra, depende de YT_VERSION / YTM_VERSION del env.
#   2) Invoca scripts/apkmirror_download.py para bajar cada APK.
#      El scraper sigue la cadena version-page → variant-page →
#      key-page (con Referer) → download.php → CDN R2 de APKMirror.
#
# ¿Por qué APKMirror y no apkeep? apkeep no soporta APKMirror como fuente
# y las fuentes soportadas (apk-pure, google-play, f-droid, huawei-app-
# gallery) no tienen las versiones pinneadas que inotia00 v5.14.x
# requiere. APKMirror sí tiene archivo histórico. Ver
# docs/APKMIRROR-SCRAPER.md.
#
# Requiere: python3, gh, jq, unzip.
# Env:
#   YT_VERSION / YTM_VERSION  — versiones específicas (OBLIGATORIAS; ya no
#                               hay "latest" — APKMirror scraper requiere
#                               versión explícita).
#   ARCH                       — informativo (APKMirror sirve el APK
#                               universal con nodpi por default, válido
#                               para arm64-v8a).
#   APKEEP_SOURCE              — vestigial (ignorado; soportado para
#                               compat con disparos viejos del workflow).

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_cmd python3 || require_cmd python
require_cmd jq

ARCH="${ARCH:-arm64-v8a}"

YT_PKG="com.google.android.youtube"
YTM_PKG="com.google.android.apps.youtube.music"

# Mapping package → (APKMirror org, slug). Si se añaden apps nuevas al
# pipeline en el futuro, extender aquí.
YT_APKM_ORG="google-inc";    YT_APKM_SLUG="youtube"
YTM_APKM_ORG="google-inc";   YTM_APKM_SLUG="youtube-music"

PY_SCRAPER="$SCRIPT_DIR/apkmirror_download.py"
[ -f "$PY_SCRAPER" ] || die "Falta $PY_SCRAPER — scraper APKMirror."

# ── 1+2. Bootstrap patches-meta.json ─────────────────────────────────
resolve_patches_meta_if_needed() {
  [ -s "$META_DIR/patches-meta.json" ] && return 0
  info "patches-meta.json no existe — extrayendo metadata del .mpp directamente."
  require_cmd gh
  require_cmd unzip
  require_cmd jq

  # MIGRACIÓN 2026-04-23: inotia00/revanced-patches archivado. Nuevo default
  # MorpheApp/morphe-patches con formato .mpp (ZIP igual, estructura similar).
  # Parametrizable via REVANCED_PATCHES_REPO por si cambiamos de fork.
  local patches_repo="${REVANCED_PATCHES_REPO:-MorpheApp/morphe-patches}"

  local patches_rvp
  patches_rvp="$(ensure_tool "revanced-patches.mpp" "$patches_repo" "patches-.*\\.mpp$")"

  # Las versiones YT/YTM las pasamos siempre explícitamente vía workflow
  # input — este bootstrap es best-effort para documentar compat internamente.
  # El .mpp es un ZIP. Probamos rutas candidatas conocidas para metadata JSON.
  info "Archivos JSON dentro del .mpp:"
  unzip -l "$patches_rvp" 2>/dev/null | awk 'NR>3 && $4 ~ /\.json$/ {print "  • "$4}' >&2 || true

  local candidates=(
    "patches.json"
    "META-INF/patches.json"
    "META-INF/morphe/patches.json"
    "morphe/patches.json"
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

  warn "Ninguna ruta candidata contiene JSON válido en el .mpp — versiones se resolverán como 'latest'."
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

# El scraper APKMirror requiere versión explícita (no existe "latest" en
# su URL schema). Si no hay env ni meta, abortamos con mensaje útil que
# lista las versiones conocidas-compatibles con inotia00 v5.14.1.
if [ -z "$YT_VERSION" ] || [ -z "$YTM_VERSION" ]; then
  die "Faltan versiones explícitas (YT_VERSION/YTM_VERSION) y no hay meta disponible.
    Pasa versiones via workflow_dispatch. Últimas conocidas-compat con
    MorpheApp/morphe-patches v1.24.0:
      YouTube:       20.21.37, 20.31.42, 20.45.36, 20.47.62
      YouTube Music: 7.29.52, 8.44.54, 8.47.56
    Si APKMirror no tiene una, prueba la anterior de la lista.
    Ver docs/APKMIRROR-SCRAPER.md §versiones-soportadas."
fi

info "YouTube        → $YT_VERSION  ($YT_PKG)"
info "YouTube Music  → $YTM_VERSION ($YTM_PKG)"

# ── 4. Descarga via scraper APKMirror ────────────────────────────────
download_apk() {
  local pkg="$1" version="$2" out_name="$3" org="$4" slug="$5"
  local dest="$APKS_DIR/$out_name"
  step "Descargando $pkg@$version desde APKMirror ($org/$slug)"

  if ! python3 "$PY_SCRAPER" "$org" "$slug" "$version" "$dest"; then
    die "APKMirror scraper falló para $pkg@$version.
    Pasos de diagnóstico:
      1. Revisá el exit code arriba (2=version 404, 3=sin variantes,
         4=key missing, 5=download.php missing, 6=download inválido).
      2. Si el HTML de APKMirror cambió, revisar docs/APKMIRROR-SCRAPER.md
         §mantenimiento y actualizar los regex en apkmirror_download.py."
  fi
  ok "Descargado: $dest ($(du -h "$dest" | cut -f1))"
}

download_apk "$YT_PKG"  "$YT_VERSION"  "youtube.apk"       "$YT_APKM_ORG"  "$YT_APKM_SLUG"
download_apk "$YTM_PKG" "$YTM_VERSION" "youtube-music.apk" "$YTM_APKM_ORG" "$YTM_APKM_SLUG"

# ── Meta ─────────────────────────────────────────────────────────────
cat > "$META_DIR/fetch.json" <<EOF
{
  "fetched_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "source_primary": "apkmirror",
  "arch": "$ARCH",
  "youtube": {
    "package": "$YT_PKG",
    "version": "$YT_VERSION",
    "apk": "youtube.apk"
  },
  "youtube_music": {
    "package": "$YTM_PKG",
    "version": "$YTM_VERSION",
    "apk": "youtube-music.apk"
  }
}
EOF

ok "Fetch completo. Meta: $META_DIR/fetch.json"
