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
CLI_VERSION="$(jq -r '.revanced_cli_version'      "$META_DIR/patch.json")"
PATCHES_VERSION="$(jq -r '.revanced_patches_version' "$META_DIR/patch.json")"

# ── Skip si no hay cambios ──────────────────────────────────────────
if [ "${FORCE_RELEASE:-0}" != "1" ]; then
  last_tag="$(gh release list --repo "$REPO" --limit 1 --json tagName --jq '.[0].tagName // empty' 2>/dev/null || true)"
  if [ -n "$last_tag" ] && [[ "$last_tag" == ytp-a-* ]]; then
    info "Último release: $last_tag — comparando versiones..."
    last_body="$(gh release view "$last_tag" --repo "$REPO" --json body --jq '.body' 2>/dev/null || echo '')"
    if echo "$last_body" | grep -q "YouTube: $YT_VERSION" && \
       echo "$last_body" | grep -q "YT Music: $YTM_VERSION" && \
       echo "$last_body" | grep -q "GmsCore: $GMS_VERSION" && \
       echo "$last_body" | grep -q "ReVanced patches: $PATCHES_VERSION"; then
      ok "Sin cambios desde $last_tag — skip release (FORCE_RELEASE=1 para forzar)."
      exit 0
    fi
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
cat > "$body_file" <<EOF
# YTP — Daily ReVanced Build

**Fecha:** $(date -u '+%Y-%m-%d %H:%M UTC')
**Dispositivo objetivo:** $DEVICE_NAME (arm64-v8a)

## Versiones incluidas

- **YouTube:** $YT_VERSION
- **YT Music:** $YTM_VERSION
- **GmsCore:** $GMS_VERSION
- **ReVanced CLI:** $CLI_VERSION
- **ReVanced patches:** $PATCHES_VERSION

## Assets (arm64-v8a, firmados con \`yt-personal\`)

| Archivo | Propósito |
|---------|-----------|
| \`youtube-personal-${YT_VERSION}.apk\` | YouTube parcheado (ad-free, background, PiP, SponsorBlock). |
| \`youtube-music-personal-${YTM_VERSION}.apk\` | YouTube Music parcheado (ad-free, background). |
| \`gmscore-${GMS_VERSION}.apk\` | ReVanced/GmsCore: MicroG fork requerido para login Google. |

## Instalación

Si ya tienes el YTP Setup, Obtainium actualizará todo solo en background
en los próximos minutos.

Si es tu primera vez: descarga el \`YTP-Setup.apk\` del Release de
\`ytp-setup\` más reciente y sigue [docs/INSTALL-PHONE.md](../blob/main/docs/INSTALL-PHONE.md).

---

*Automático. Uso personal.*
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
