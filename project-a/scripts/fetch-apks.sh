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
# FIX 2026-06-21: el .mpp ya NO trae metadata JSON embebida (confirmado en
# v1.31.0 — solo assets de UI). La metadata estructurada de compatibilidad
# vive en un archivo separado en el repo de patches: patches-list.json
# (rama main, no es asset de release). Lo bajamos directo por HTTP en vez
# de intentar extraerlo del .mpp.
resolve_patches_meta_if_needed() {
  [ -s "$META_DIR/patches-meta.json" ] && return 0
  info "patches-meta.json no existe — descargando patches-list.json del repo de patches."
  require_cmd curl
  require_cmd jq

  # Parametrizable via REVANCED_PATCHES_REPO por si cambiamos de fork.
  local patches_repo="${REVANCED_PATCHES_REPO:-MorpheApp/morphe-patches}"
  local branch="${REVANCED_PATCHES_META_BRANCH:-main}"
  local list_url="https://raw.githubusercontent.com/${patches_repo}/${branch}/patches-list.json"

  local tmp
  tmp="$(mktemp)"
  if curl -sfL "$list_url" -o "$tmp" && [ -s "$tmp" ] && jq empty "$tmp" 2>/dev/null; then
    mv "$tmp" "$META_DIR/patches-meta.json"
    ok "patches-meta.json descargado de $list_url ($(wc -c < "$META_DIR/patches-meta.json") bytes)"
    return 0
  fi
  rm -f "$tmp"

  warn "No se pudo obtener '$list_url' — versiones se resolverán como vacío."
  warn "(Revisá que el repo siga publicando patches-list.json en esa rama, o pasá YT_VERSION/YTM_VERSION a mano.)"
  echo '{"patches":[]}' > "$META_DIR/patches-meta.json"
}

resolve_patches_meta_if_needed

# ── 3. Resolución de versiones ───────────────────────────────────────
# Preferencia: env override > patches-meta > (falla con mensaje útil)
#
# Esquema real de patches-list.json (v1.31.0+):
#   { "patches": [ { "name": ..., "compatiblePackages": [
#        { "packageName": "com.google.android.youtube",
#          "targets": [ { "version": "20.51.39", "isExperimental": false }, ... ] }
#   ] } ] }
#
# Tomamos, para el package pedido, la INTERSECCIÓN de versiones no-
# experimentales entre TODOS los patches que mencionan ese package (así
# garantizamos que la versión elegida sea compatible con cualquier patch
# que se termine usando, no solo con uno) y devolvemos la más alta.
resolve_version_from_meta() {
  local pkg="$1"
  jq -r --arg pkg "$pkg" '
    [.patches[]?
     | .compatiblePackages[]?
     | select(.packageName == $pkg)
     | (.targets // []) | map(select(.isExperimental == false) | .version)
     | select(length > 0)
    ] as $lists
    | if ($lists | length) == 0 then empty
      else
        ($lists[0] as $first
         | reduce $lists[1:][] as $l ($first; . - (. - $l))
        )
        | sort_by(split(".") | map(tonumber? // 0))
        | last // empty
      end
  ' "$META_DIR/patches-meta.json" 2>/dev/null || true
}

YT_VERSION="${YT_VERSION:-}"
YTM_VERSION="${YTM_VERSION:-}"
[ -z "$YT_VERSION"  ] && YT_VERSION="$(resolve_version_from_meta  "$YT_PKG"  || true)"
[ -z "$YTM_VERSION" ] && YTM_VERSION="$(resolve_version_from_meta "$YTM_PKG" || true)"

# ── Piso de versión mínima ────────────────────────────────────────────
# Evita que patches-list.json inestable haga retroceder a versiones viejas.
# Actualizar manualmente cada vez que se publique una versión más nueva.
YT_VERSION_FLOOR="20.51.39"  # morphe-patches v1.33.0 soporta hasta 20.51.39
YTM_VERSION_FLOOR="9.15.51"

version_gte() {
  # Retorna 0 (true) si $1 >= $2 comparando semver
  python3 -c "
import sys
a=[int(x) for x in sys.argv[1].split('.')]
b=[int(x) for x in sys.argv[2].split('.')]
sys.exit(0 if a>=b else 1)
" "$1" "$2"
}

if [ -n "$YT_VERSION" ] && ! version_gte "$YT_VERSION" "$YT_VERSION_FLOOR"; then
  warn "Version YT detectada ($YT_VERSION) < piso ($YT_VERSION_FLOOR) — usando piso."
  YT_VERSION="$YT_VERSION_FLOOR"
fi
if [ -n "$YTM_VERSION" ] && ! version_gte "$YTM_VERSION" "$YTM_VERSION_FLOOR"; then
  warn "Version YTM detectada ($YTM_VERSION) < piso ($YTM_VERSION_FLOOR) — usando piso."
  YTM_VERSION="$YTM_VERSION_FLOOR"
fi

# El scraper APKMirror requiere versión explícita (no existe "latest" en
# su URL schema). Si no hay env ni meta, abortamos con mensaje útil que
# lista las versiones conocidas-compatibles con inotia00 v5.14.1.
if [ -z "$YT_VERSION" ] || [ -z "$YTM_VERSION" ]; then
  die "Faltan versiones explícitas (YT_VERSION/YTM_VERSION) y no hay meta disponible
    (falló la descarga/parseo de patches-list.json — revisá el warning de arriba).
    Pasa versiones via workflow_dispatch. Últimas conocidas-compat
    (no-experimentales) con MorpheApp/morphe-patches v1.31.0:
      YouTube:       20.21.37, 20.31.42, 20.51.39
      YouTube Music: 7.29.52, 8.47.56, 8.51.51
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
