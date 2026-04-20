#!/usr/bin/env bash
# Aplica los patches de ReVanced a YouTube y YouTube Music.
#
# Baja las últimas releases de:
#   - revanced/revanced-cli (JAR)
#   - revanced/revanced-patches (.rvp, formato moderno)
#   - ReVanced/GmsCore (APK MicroG fork)
#
# Genera APKs parcheados sin firmar en $PATCHED_DIR/.
# NO firma — eso lo hace sign-apks.sh.

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_cmd java
require_cmd gh
require_cmd jq

# ── Descargar herramientas ───────────────────────────────────────────
# Defaults parametrizables por env (ver CLAUDE.md / TROUBLESHOOTING.md).
# ReVanced/revanced-patches está HTTP 451 desde 2025 → usamos fork.
CLI_REPO="${REVANCED_CLI_REPO:-ReVanced/revanced-cli}"
PATCHES_REPO="${REVANCED_PATCHES_REPO:-inotia00/revanced-patches}"
GMSCORE_REPO="${REVANCED_GMSCORE_REPO:-ReVanced/GmsCore}"

# CLI pineada a v5.0.1 por default — v6 tiene incompat binaria con los
# patches de inotia00 v5.14.1 (patcher v2 rompió clases como MutableMethod
# que usan los patches v5.x). Ver docs/TROUBLESHOOTING.md #8.
CLI_TAG="${REVANCED_CLI_TAG:-v5.0.1}"

# Regex del asset GmsCore. Default hoy: naming 'app.revanced.android.gms-<ver>-signed.apk'
# (excluye -hw-signed.apk, que es la variante Huawei AppGallery). Si el upstream
# cambia el naming de nuevo, override con env REVANCED_GMSCORE_REGEX sin tocar código.
GMSCORE_REGEX="${REVANCED_GMSCORE_REGEX:-app\\.revanced\\.android\\.gms-[0-9]+-signed\\.apk$}"

step "Descargando revanced-cli ($CLI_REPO @ $CLI_TAG), patches ($PATCHES_REPO), GmsCore ($GMSCORE_REPO)"

CLI_JAR="$(ensure_tool "revanced-cli.jar"  "$CLI_REPO"     "revanced-cli-.*-all\\.jar$" "$CLI_TAG")"
PATCHES_RVP="$(ensure_tool "revanced-patches.rvp" "$PATCHES_REPO" "patches-.*\\.rvp$")"
GMSCORE_APK="$(ensure_tool "gmscore.apk"  "$GMSCORE_REPO"          "$GMSCORE_REGEX")"

info "CLI:       $CLI_JAR"
info "Patches:   $PATCHES_RVP"
info "GmsCore:   $GMSCORE_APK"

# ── Meta de patches (usado también por fetch-apks.sh en próximos runs) ──
info "Extrayendo metadata de patches (compatible_packages + versions)"
java -jar "$CLI_JAR" list-patches --with-packages --with-versions --json "$PATCHES_RVP" \
  > "$META_DIR/patches-meta.json" 2>/dev/null || {
  warn "list-patches --json no soportado por este CLI — uso fallback text."
  java -jar "$CLI_JAR" list-patches "$PATCHES_RVP" \
    > "$META_DIR/patches-meta.txt" || true
}

# ── Función: aplicar patches a un APK ────────────────────────────────
# Uso: apply_patch <input_apk> <out_apk> <label> [-- <extra_opts>...]
# Los extra_opts se pasan literalmente a revanced-cli (típicamente -O
# para configurar options de patches, ej. appIcon / appName).
apply_patch() {
  local input_apk="$1" out_apk="$2" label="$3"
  shift 3
  # Resto de args = opciones extra (típicamente -O flags).
  local extra_opts=("$@")

  [ -f "$input_apk" ] || die "No encuentro input: $input_apk"

  step "Parcheando $label → $(basename "$out_apk")"
  # cli v5 NO exige verificación PGP — por eso pineamos a v5.0.1 (v6
  # añadió --bypass-verification obligatorio pero es incompat binaria
  # con los patches de inotia00 v5.14.1; v6.x necesita patcher v2+,
  # v5.x patches usan MutableMethod de patcher v1.x).
  # Flags:
  #   -p <file.rvp>         : set de patches (short form, compat v5+v6)
  #   -O "PatchName:key=val": override opción de un patch específico
  #   -o <apk>              : archivo de salida (sin firmar por nosotros)
  #   --purge               : limpia temporales
  # NO pasamos --keystore aquí; cli v5 firma con su "ReVanced Key" default,
  # luego sign-apks.sh re-firma con nuestro keystore vía apksigner
  # (apksigner replace la firma existente limpiamente).
  java -jar "$CLI_JAR" patch \
    -p "$PATCHES_RVP" \
    "${extra_opts[@]}" \
    -o "$out_apk" \
    --purge \
    "$input_apk"
  [ -f "$out_apk" ] || die "Patch OK pero no existe $out_apk"
  ok "$label parcheado: $(du -h "$out_apk" | cut -f1)"
}

# ── Opciones de branding: usar presets oficiales built-in de inotia00 ──
# Los patches "Custom branding icon for <app>" aceptan presets en sus
# options — entre ellos `youtube` y `youtube_music` que son los íconos
# oficiales de Google embebidos en el .rvp por inotia00. Sin presets
# tendríamos que extraer los PNGs del APK original con apktool y
# commitearlos (gray-area + mantenimiento). Con los presets todo viene
# del .rvp y se actualiza automáticamente con cada nueva versión.
#
# SINTAXIS CLI v5.0.1 (orden-sensible picocli):
#   -e "PatchName" -O "optionKey=value" [-O "otherKey=otherValue"]...
# Los -O que siguen a un -e se asocian a ESE patch hasta que aparece
# otro -e. No usar el formato v6-style "PatchName:key=value" dentro
# del -O — v5 no lo parsea (ver TROUBLESHOOTING Bug #9).
# Los patches ya son Enabled=true por default, -e explícito es redundante
# pero requerido por picocli cuando hay -O (y sin --exclusive no limita
# el set de patches que corren).
YT_OPTS=(
  -e "Custom branding icon for YouTube"  -O "appIcon=youtube"
  -e "Custom branding name for YouTube"  -O "appName=YouTube"
)
YTM_OPTS=(
  -e "Custom branding icon for YouTube Music"  -O "appIcon=youtube_music"
  -e "Custom branding name for YouTube Music"  -O "appNameLauncher=YouTube Music"
                                               -O "appNameNotification=YouTube Music"
)

apply_patch "$APKS_DIR/youtube.apk"       "$PATCHED_DIR/youtube-patched.apk"       "YouTube"       "${YT_OPTS[@]}"
apply_patch "$APKS_DIR/youtube-music.apk" "$PATCHED_DIR/youtube-music-patched.apk" "YouTube Music" "${YTM_OPTS[@]}"

# GmsCore se copia tal cual al output; es un requisito externo pero se
# redistribuye junto con los otros APKs en el Release.
cp -f "$GMSCORE_APK" "$PATCHED_DIR/gmscore.apk"
ok "GmsCore copiado: $PATCHED_DIR/gmscore.apk"

# ── SmartTube para Android TV ────────────────────────────────────────
# yuliskov/SmartTube es Apache 2.0 — permite redistribución. Lo bajamos
# arm64-v8a (compat con la mayoría de TV Boxes 2020+), lo copiamos al
# directorio de patched/, y sign-apks.sh lo re-firma con nuestro
# keystore para consistencia con los otros 3 APKs.
step "Descargando SmartTube (Android TV) desde yuliskov/SmartTube"
SMARTTUBE_REPO="${SMARTTUBE_REPO:-yuliskov/SmartTube}"
SMARTTUBE_REGEX="${SMARTTUBE_REGEX:-SmartTube_stable_.*_arm64-v8a\\.apk$}"
SMARTTUBE_APK_SRC="$(ensure_tool "smarttube.apk" "$SMARTTUBE_REPO" "$SMARTTUBE_REGEX")"
cp -f "$SMARTTUBE_APK_SRC" "$PATCHED_DIR/smarttube.apk"
ok "SmartTube copiado: $PATCHED_DIR/smarttube.apk"

# ── Verificación defensiva: patches efectivamente aplicados ───────────
# Previene regresión al escenario de "falsa victoria" (run #7): si los
# patches no cargaron por incompat binaria, el package name quedaría
# como el original y el flag -b / exit-0 no alertarían. Chequear el
# rename del GmsCore support patch es la señal más confiable.
step "Verificando que los patches se aplicaron (package name debe cambiar)"
require_cmd aapt2

verify_package() {
  local apk="$1" expected="$2" label="$3"
  local actual
  actual="$(aapt2 dump badging "$apk" 2>/dev/null | awk -F\' '/^package/{print $2; exit}')"
  if [ "$actual" = "$expected" ]; then
    ok "$label: package=$actual ✓ (rename aplicado)"
  else
    die "$label: package esperado='$expected' actual='$actual'. Los patches NO se aplicaron (posible incompat CLI/patches). Revisa el SEVERE del log."
  fi
}

verify_package "$PATCHED_DIR/youtube-patched.apk"       "app.rvx.android.youtube"             "YouTube"
verify_package "$PATCHED_DIR/youtube-music-patched.apk" "app.rvx.android.apps.youtube.music"  "YouTube Music"

# ── Meta del patch run ───────────────────────────────────────────────
# Para CLI usamos el tag pineado (no "latest" del repo).
CLI_VER="${CLI_TAG}"
PATCHES_VER="$(gh_latest_tag "$PATCHES_REPO")"
GMS_VER="$(gh_latest_tag "$GMSCORE_REPO")"
SMARTTUBE_VER="$(gh_latest_tag "$SMARTTUBE_REPO")"

cat > "$META_DIR/patch.json" <<EOF
{
  "patched_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "revanced_cli_version": "$CLI_VER",
  "revanced_patches_version": "$PATCHES_VER",
  "gmscore_version": "$GMS_VER",
  "smarttube_version": "$SMARTTUBE_VER",
  "outputs": [
    "youtube-patched.apk",
    "youtube-music-patched.apk",
    "gmscore.apk",
    "smarttube.apk"
  ]
}
EOF

ok "Patch completo. Meta: $META_DIR/patch.json"
