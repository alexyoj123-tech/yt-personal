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
# MIGRACIÓN 2026-04-23: inotia00/revanced-patches archivado (ver Bug #10
# en TROUBLESHOOTING.md). Nueva fuente: MorpheApp, mantenido por los mismos
# desarrolladores ex-ReVanced/inotia00 con código limpio desde cero.
# - morphe-patches v1.24.0+ (formato .mpp)
# - morphe-cli v1.7.0+ (drop-in syntax compat con revanced-cli v5)
# - MicroG-RE v6.1.3+ (reemplazo ligero 12.8 MB vs 100 MB de ReVanced/GmsCore)
CLI_REPO="${REVANCED_CLI_REPO:-MorpheApp/morphe-cli}"
PATCHES_REPO="${REVANCED_PATCHES_REPO:-MorpheApp/morphe-patches}"
GMSCORE_REPO="${REVANCED_GMSCORE_REPO:-MorpheApp/MicroG-RE}"

# CLI pineada a v1.7.0 por estabilidad. Actualizar cuando Morphe publique
# nueva stable + verificar compat con .mpp correspondiente.
CLI_TAG="${REVANCED_CLI_TAG:-v1.7.0}"

# Regex del asset MicroG-RE: 'microg-<ver>.apk'. Si el naming cambia,
# override con env REVANCED_GMSCORE_REGEX.
GMSCORE_REGEX="${REVANCED_GMSCORE_REGEX:-microg-[0-9.]+\\.apk$}"

step "Descargando morphe-cli ($CLI_REPO @ $CLI_TAG), patches ($PATCHES_REPO), MicroG-RE ($GMSCORE_REPO)"

# El regex de CLI acepta ambos formatos histórico y actual:
# - revanced-cli-<ver>-all.jar (histórico, inotia00/ReVanced)
# - morphe-cli-<ver>-all.jar   (actual, Morphe)
CLI_JAR="$(ensure_tool "revanced-cli.jar"  "$CLI_REPO"     "(revanced-cli|morphe-cli)-.*-all\\.jar$" "$CLI_TAG")"
PATCHES_RVP="$(ensure_tool "revanced-patches.mpp" "$PATCHES_REPO" "patches-.*\\.mpp$")"
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
  # morphe-cli v1.7.0 es drop-in replacement de revanced-cli v5 syntax:
  #   -p=<file.mpp>         : set de patches
  #   -e "<PatchName>"      : habilita un patch específico (enable, -e)
  #   -O "key=value"        : override una opción del patch anterior
  #   -o=<apk>              : archivo de salida
  #   --unsigned            : NO firma (Morphe-specific flag, ideal — firmamos
  #                           con apksigner en sign-apks.sh)
  #   --continue-on-error   : continúa si un patch menor falla
  #   --purge               : limpia temporales
  # NO pasamos --keystore; el APK sale sin firmar gracias a --unsigned.
  java -jar "$CLI_JAR" patch \
    -p "$PATCHES_RVP" \
    "${extra_opts[@]}" \
    -o "$out_apk" \
    --unsigned \
    --continue-on-error \
    --purge \
    "$input_apk"
  [ -f "$out_apk" ] || die "Patch OK pero no existe $out_apk"
  ok "$label parcheado: $(du -h "$out_apk" | cut -f1)"
}

# ── Opciones de branding (Morphe v1.24.0+) ──────────────────────────
# Patches clave que habilitamos explícitamente:
#   "Custom branding" (unificado icon+name)   — opciones: customName, customIcon
#   "Change package name"                     — NO enabled by default en Morphe.
#     Debe habilitarse para que el APK coexista con el pre-instalado de
#     Samsung/Google. Opciones safe: updatePermissions/Providers=true.
#   "GmsCore support"                         — enabled, auto, integra con MicroG-RE.
#   "Spoof video streams"                     — enabled, auto, resuelve HTTP 400
#                                                server-side (el patch clave que
#                                                inotia00 NO exponía).
#
# El customIcon espera una carpeta con:
#   mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
#     morphe_adaptive_background_custom.png   (108/162/216/324/432 px)
#     morphe_adaptive_foreground_custom.png
# Íconos extraídos del APK oficial 20.47.62 / 8.47.56 Q4 2024 via apktool,
# commiteados en project-a/assets/morphe-icons/.
MORPHE_ICONS="$REPO_ROOT/project-a/assets/morphe-icons"

YT_OPTS=(
  -e "Custom branding"
  -O "customName=YouTube"
  -O "customIcon=$MORPHE_ICONS/youtube"
  -e "Change package name"
  -O "packageName=Default"
  -O "updatePermissions=true"
  -O "updateProviders=true"
  -O "updateProvidersStrings=true"
)
YTM_OPTS=(
  -e "Custom branding"
  -O "customName=YouTube Music"
  -O "customIcon=$MORPHE_ICONS/ytmusic"
  -e "Change package name"
  -O "packageName=Default"
  -O "updatePermissions=true"
  -O "updateProviders=true"
  -O "updateProvidersStrings=true"
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

# Package names esperados tras "Change package name" patch de Morphe.
# El único valor posible en el patch es "Default" → Morphe decide el naming.
# Esperado: app.morphe.android.youtube / app.morphe.android.apps.youtube.music
# (patrón consistente con morphe-* de sus forks). Si el primer build da otro
# nombre, aapt2 del log lo confirma — override via env EXPECTED_*_PACKAGE.
EXPECTED_YT_PACKAGE="${EXPECTED_YT_PACKAGE:-app.morphe.android.youtube}"
EXPECTED_YTM_PACKAGE="${EXPECTED_YTM_PACKAGE:-app.morphe.android.apps.youtube.music}"

verify_package "$PATCHED_DIR/youtube-patched.apk"       "$EXPECTED_YT_PACKAGE"  "YouTube"
verify_package "$PATCHED_DIR/youtube-music-patched.apk" "$EXPECTED_YTM_PACKAGE" "YouTube Music"

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
