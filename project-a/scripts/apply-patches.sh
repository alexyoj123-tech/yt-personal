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

# Regex del asset GmsCore. Default hoy: naming 'app.revanced.android.gms-<ver>-signed.apk'
# (excluye -hw-signed.apk, que es la variante Huawei AppGallery). Si el upstream
# cambia el naming de nuevo, override con env REVANCED_GMSCORE_REGEX sin tocar código.
GMSCORE_REGEX="${REVANCED_GMSCORE_REGEX:-app\\.revanced\\.android\\.gms-[0-9]+-signed\\.apk$}"

step "Descargando revanced-cli ($CLI_REPO), patches ($PATCHES_REPO), GmsCore ($GMSCORE_REPO)"

CLI_JAR="$(ensure_tool "revanced-cli.jar"  "$CLI_REPO"     "revanced-cli-.*-all\\.jar$")"
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
apply_patch() {
  local input_apk="$1" out_apk="$2" label="$3"
  [ -f "$input_apk" ] || die "No encuentro input: $input_apk"

  step "Parcheando $label → $(basename "$out_apk")"
  # revanced-cli v5+ usa `patch`. Flags:
  #   --patches <file.rvp>  : set de patches
  #   --out <apk>           : archivo de salida (sin firmar)
  #   --purge               : limpia temporales
  # NO pasamos --keystore aquí; la firma va en sign-apks.sh.
  java -jar "$CLI_JAR" patch \
    --patches "$PATCHES_RVP" \
    --out "$out_apk" \
    --purge \
    "$input_apk"
  [ -f "$out_apk" ] || die "Patch OK pero no existe $out_apk"
  ok "$label parcheado: $(du -h "$out_apk" | cut -f1)"
}

apply_patch "$APKS_DIR/youtube.apk"       "$PATCHED_DIR/youtube-patched.apk"       "YouTube"
apply_patch "$APKS_DIR/youtube-music.apk" "$PATCHED_DIR/youtube-music-patched.apk" "YouTube Music"

# GmsCore se copia tal cual al output; es un requisito externo pero se
# redistribuye junto con los otros APKs en el Release.
cp -f "$GMSCORE_APK" "$PATCHED_DIR/gmscore.apk"
ok "GmsCore copiado: $PATCHED_DIR/gmscore.apk"

# ── Meta del patch run ───────────────────────────────────────────────
CLI_VER="$(gh_latest_tag "$CLI_REPO")"
PATCHES_VER="$(gh_latest_tag "$PATCHES_REPO")"
GMS_VER="$(gh_latest_tag "$GMSCORE_REPO")"

cat > "$META_DIR/patch.json" <<EOF
{
  "patched_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "revanced_cli_version": "$CLI_VER",
  "revanced_patches_version": "$PATCHES_VER",
  "gmscore_version": "$GMS_VER",
  "outputs": [
    "youtube-patched.apk",
    "youtube-music-patched.apk",
    "gmscore.apk"
  ]
}
EOF

ok "Patch completo. Meta: $META_DIR/patch.json"
