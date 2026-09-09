#!/usr/bin/env bash
# Publica los 3 APKs firmados como un GitHub Release.
#
# Tag: ytp-a-<YYYY.MM.DD>[-N] (N se incrementa si ya existe)
# Title: "Daily ReVanced Build YYYY-MM-DD"
# Body: changelog con versiones YT / YTM / GmsCore / revanced-cli / patches
#
# Si el Release anterior ya tenía las MISMAS versiones, skip (no publica).
#
# Requiere: gh autenticado (GITHUB_TOKEN en Actions).
# Env opcional: FORCE_RELEASE=1 para publicar aunque no haya cambios.

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_cmd gh
require_cmd jq

REPO="${GITHUB_REPOSITORY:-alexyoj123-tech/yt-personal}"
DEVICE_NAME="${DEVICE_NAME:-Samsung Galaxy A04e}"

# ── Leer metadatos ──────────────────────────────────────────────────
[ -f "$META_DIR/fetch.json" ] || die "Falta $META_DIR/fetch.json (ejecuta fetch-apks.sh primero)"
[ -f "$META_DIR/patch.json" ] || die "Falta $META_DIR/patch.json (ejecuta apply-patches.sh primero)"
[ -f "$META_DIR/sign.json"  ] || die "Falta $META_DIR/sign.json (ejecuta sign-apks.sh primero)"

YT_VERSION="$(jq -r '.versions.youtube'       "$META_DIR/sign.json")"
YTM_VERSION="$(jq -r '.versions.youtube_music' "$META_DIR/sign.json")"
GMS_VERSION="$(jq -r '.versions.gmscore'       "$META_DIR/sign.json")"
SMARTTUBE_VERSION="$(jq -r '.versions.smarttube // "n/a"' "$META_DIR/sign.json")"
CLI_VERSION="$(jq -r '.revanced_cli_version'      "$META_DIR/patch.json")"
PATCHES_VERSION="$(jq -r '.revanced_patches_version' "$META_DIR/patch.json")"

# Huella de versiones: string plano y estable, embebido como comentario HTML en
# el body del release. Comparar esto es lo unico fiable — el texto markdown
# cambia de formato y los greps sobre prosa se rompen en silencio.
FINGERPRINT="yt=${YT_VERSION};ytm=${YTM_VERSION};gms=${GMS_VERSION};st=${SMARTTUBE_VERSION};cli=${CLI_VERSION};patches=${PATCHES_VERSION}"
info "Huella de este build: $FINGERPRINT"

# ── Skip si no hay cambios ──────────────────────────────────────────
# BUG HISTORICO (arreglado 2026-09-08): este bloque nunca llegaba a saltar.
#   1. `--limit 1` devolvia el release mas nuevo de CUALQUIER proyecto
#      (ytp-f-family, ytp-g-VallEthTube...), no el de project-a, asi que el
#      `[[ == ytp-a-* ]]` fallaba y la comparacion se saltaba entera.
#   2. Los greps buscaban "YouTube: X" pero el body escribe "**YouTube:** X",
#      y "ReVanced patches:" ya no existe en el body (ahora es "patches:").
# Resultado: publicaba release TODOS los dias aunque nada hubiera cambiado.
if [ "${FORCE_RELEASE:-0}" != "1" ]; then
  last_tag="$(gh release list --repo "$REPO" --limit 60 --json tagName \
      --jq '[.[] | select(.tagName | startswith("ytp-a-"))][0].tagName // empty' \
      2>/dev/null || true)"

  if [ -n "$last_tag" ]; then
    info "Ultimo release de project-a: $last_tag — comparando..."
    last_body="$(gh release view "$last_tag" --repo "$REPO" --json body --jq '.body' 2>/dev/null || echo '')"
    last_fp="$(printf '%s' "$last_body" \
      | sed -n 's/.*ytp-fingerprint:[[:space:]]*\([^[:space:]]*\).*/\1/p' | head -1)"

    if [ -n "$last_fp" ]; then
      if [ "$last_fp" = "$FINGERPRINT" ]; then
        ok "Sin cambios desde $last_tag — skip release (FORCE_RELEASE=1 para forzar)."
        exit 0
      fi
      info "Hay cambios:"
      info "  anterior: $last_fp"
      info "  actual:   $FINGERPRINT"
    else
      # Release anterior sin huella (formato viejo). Comparar sobre el body con
      # los ** de markdown quitados, para no depender del formato del texto.
      plain="$(printf '%s' "$last_body" | sed 's/\*\*//g')"
      if echo "$plain" | grep -qF "YouTube: $YT_VERSION"    && \
         echo "$plain" | grep -qF "YT Music: $YTM_VERSION"  && \
         echo "$plain" | grep -qF "MicroG-RE: $GMS_VERSION" && \
         echo "$plain" | grep -qF "patches: $PATCHES_VERSION"; then
        ok "Sin cambios desde $last_tag (formato viejo) — skip release."
        exit 0
      fi
      info "Hay cambios vs $last_tag (comparacion sobre body sin huella)."
    fi
  else
    info "No hay releases previos de project-a — publicando el primero."
  fi
fi

# ── Elegir tag único ─────────────────────────────────────────────────
base_tag="ytp-a-$(date -u +%Y.%m.%d)"
tag="$base_tag"
n=1
while gh release view "$tag" --repo "$REPO" >/dev/null 2>&1; do
  n=$((n+1))
  tag="${base_tag}-${n}"
done
info "Tag elegido: $tag"

# ── Changelog / body ─────────────────────────────────────────────────
body_file="$(mktemp)"
# Nota prefijo opcional (ej. para releases de corrección).
{
  if [ -n "${RELEASE_NOTE_PREFIX:-}" ]; then
    echo "> **Nota:** $RELEASE_NOTE_PREFIX"
    echo ""
  fi
} > "$body_file"
cat >> "$body_file" <<EOF
# YTP — Daily ReVanced Build

**Fecha:** $(date -u '+%Y-%m-%d %H:%M UTC')
**Dispositivo objetivo:** $DEVICE_NAME (arm64-v8a)

## Versiones incluidas

- **YouTube:** $YT_VERSION · package \`app.morphe.android.youtube\`
- **YT Music:** $YTM_VERSION · package \`app.morphe.android.apps.youtube.music\`
- **MicroG-RE:** $GMS_VERSION · package \`app.revanced.android.gms\` (fork microG por Morphe, mantiene vendor ReVanced — instala como update sobre ReVanced/GmsCore previo sin desinstalar)
- **SmartTube:** $SMARTTUBE_VERSION (Android TV) · package \`org.smarttube.stable\`
- **Morphe CLI:** $CLI_VERSION · **patches:** $PATCHES_VERSION

## Assets (arm64-v8a, firmados con \`yt-personal\`)

| Archivo | Propósito |
|---------|-----------|
| \`youtube-personal-${YT_VERSION}.apk\` | YouTube parcheado (ad-free, background, PiP, SponsorBlock, ícono + nombre oficiales). |
| \`youtube-music-personal-${YTM_VERSION}.apk\` | YouTube Music parcheado (ad-free, background, ícono + nombre oficiales). |
| \`gmscore-${GMS_VERSION}.apk\` | MicroG-RE: microG fork (vendor Morphe) requerido para login Google. |
| \`smarttube-${SMARTTUBE_VERSION}.apk\` | SmartTube (Android TV, cliente YouTube con SponsorBlock). |

## Calidad de streaming

- **Video (YouTube):** highest (4K/2160p) disponible según conexión/video. Configuración post-install: Settings → Video quality → "Highest available" (remember = ON).
- **Audio (YT Music):** máximo disponible en cuenta no-Premium (~256 kbps AAC/OPUS). Enhanced Bitrate (lossless 256+ kbps) requiere cuenta Premium verificada server-side — no bypasseable por patches.
- **Codec preferido:** detectado automáticamente por el player (AV1 si el device lo soporta, luego VP9, luego H.264).

## Instalación

**Teléfono:** [docs/INSTALL-PHONE.md](../blob/main/docs/INSTALL-PHONE.md) · **TV (Claro 4K, etc.):** [docs/INSTALL-TV.md](../blob/main/docs/INSTALL-TV.md)

Si ya tienes el YTP Setup, Obtainium actualizará todo solo en background
en los próximos minutos.

---

*Automático. Uso personal. Ver [docs/CONTINUIDAD.md](../blob/main/docs/CONTINUIDAD.md) para retomo futuro.*

<!-- ytp-fingerprint: $FINGERPRINT -->
EOF

# ── Crear release ────────────────────────────────────────────────────
step "Creando release $tag con 3 APKs"
mapfile -t assets < <(ls "$SIGNED_DIR"/*.apk 2>/dev/null)
[ "${#assets[@]}" -ge 3 ] || die "Se esperaban 3 APKs firmados en $SIGNED_DIR, encontré ${#assets[@]}"

gh release create "$tag" \
  --repo "$REPO" \
  --title "Daily ReVanced Build — $(date -u +%Y-%m-%d)" \
  --notes-file "$body_file" \
  "${assets[@]}"

ok "Release $tag publicado:"
ok "  https://github.com/$REPO/releases/tag/$tag"

rm -f "$body_file"
