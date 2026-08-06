#!/usr/bin/env bash
# project-g: Descarga LibreTube (cliente YouTube sin anuncios, con bloqueo de
# canales y feed solo-suscripciones), lo re-firma con el keystore del repo y
# publica release ytp-g-kidstube-X.X
#
# Propósito: app de video para niños con enfoque LISTA BLANCA — solo aparecen
# los canales suscritos manualmente. Sin recomendaciones algorítmicas,
# sin Shorts, sin anuncios, sin cuenta de Google.
set -euo pipefail

info() { echo "[INFO]  $*"; }
ok()   { echo "[OK]    $*"; }
die()  { echo "[ERR]   $*" >&2; exit 1; }
step() { echo ""; echo "──── $* ────"; }

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="$REPO_ROOT/project-g/build/out"
META_DIR="$REPO_ROOT/project-g/build/meta"
TOOLS_DIR="$REPO_ROOT/project-g/build/tools"
mkdir -p "$OUT_DIR" "$META_DIR" "$TOOLS_DIR"

APP_SLUG="KidsTube"
UPSTREAM="libre-tube/LibreTube"

# ── Keystore ──────────────────────────────────────────────────────────
step "Preparando keystore"
[ -n "${ANDROID_KEYSTORE_BASE64:-}"   ] || die "Falta ANDROID_KEYSTORE_BASE64"
[ -n "${ANDROID_KEYSTORE_PASSWORD:-}" ] || die "Falta ANDROID_KEYSTORE_PASSWORD"
[ -n "${ANDROID_KEY_ALIAS:-}"         ] || die "Falta ANDROID_KEY_ALIAS"
[ -n "${ANDROID_KEY_PASSWORD:-}"      ] || die "Falta ANDROID_KEY_PASSWORD"
KS_PATH="$TOOLS_DIR/release.jks"
printf "%s" "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KS_PATH"
[ -s "$KS_PATH" ] || die "Keystore vacío"

# ── Versión upstream ──────────────────────────────────────────────────
step "Consultando última versión de LibreTube"
REL=$(curl -fsSL -H "Authorization: token ${GH_TOKEN:-}" \
  "https://api.github.com/repos/${UPSTREAM}/releases/latest")
VERSION=$(echo "$REL" | python3 -c "import sys,json; print(json.load(sys.stdin)['tag_name'].lstrip('v'))")
TAG="ytp-g-${APP_SLUG}-${VERSION}"
info "Versión: $VERSION | Tag: $TAG"

if gh release view "$TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  ok "Release $TAG ya existe — nada nuevo."
  shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"
  exit 0
fi

# ── Descarga ──────────────────────────────────────────────────────────
step "Descargando APK"
URL=$(echo "$REL" | python3 -c "
import sys,json
for a in json.load(sys.stdin)['assets']:
    if a['name'].endswith('.apk'):
        print(a['browser_download_url']); break
")
[ -n "$URL" ] || die "No encontré APK en el release de $UPSTREAM"
RAW="/tmp/libretube-raw.apk"
curl -fsSL --location "$URL" -o "$RAW"
[ -s "$RAW" ] || die "APK descargado vacío"
info "Descargado: $(du -h "$RAW" | cut -f1)"

# ── Firma ─────────────────────────────────────────────────────────────
step "Firmando con keystore del repo"
SIGNED="$OUT_DIR/${APP_SLUG}-${VERSION}.apk"
ALIGNED="/tmp/libretube-aligned.apk"
zipalign -p -f 4 "$RAW" "$ALIGNED"
apksigner sign \
  --ks "$KS_PATH" --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
  --ks-key-alias "$ANDROID_KEY_ALIAS" --key-pass "pass:$ANDROID_KEY_PASSWORD" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
  --out "$SIGNED" "$ALIGNED"
apksigner verify --print-certs "$SIGNED" >/dev/null
rm -f "$ALIGNED"
shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"
ok "Firmado: $SIGNED ($(du -h "$SIGNED" | cut -f1))"

# ── Metadata ──────────────────────────────────────────────────────────
SHA=$(sha256sum "$SIGNED" | cut -d' ' -f1)
printf '{"version":"%s","tag":"%s","sha256":"%s","signed_at":"%s"}\n' \
  "$VERSION" "$TAG" "$SHA" "$(date -u +%FT%TZ)" > "$META_DIR/libretube.json"

# ── Release ───────────────────────────────────────────────────────────
step "Publicando release $TAG"
cat > /tmp/g-notes.md << NOTESEOF
# KidsTube $VERSION

Cliente de video para niños, basado en LibreTube. Firmado con el keystore del repo.

**Enfoque lista blanca:** solo aparecen los canales que suscribas manualmente.
Sin recomendaciones algorítmicas, sin anuncios, sin cuenta de Google.

## Configuración obligatoria (una sola vez)

Después de instalar, entrá a **Ajustes** y aplicá esto:

1. **General → Pestaña de inicio → Suscripciones**
   (así abre directo en los canales aprobados, no en Tendencias)
2. **General → Ocultar pestañas → activá Tendencias y Explorar**
3. **Contenido → Ocultar Shorts → activado**
4. **Contenido → Ocultar transmisiones en vivo → activado**
5. **Reproductor → Autoplay del siguiente video → DESACTIVADO**
   (evita que salte a contenido no aprobado al terminar)

## Canales recomendados

Ver \`project-g/CANALES-RECOMENDADOS.md\` en el repo.

## Integridad
SHA-256: \`$SHA\`

## Fuente
LibreTube: https://github.com/libre-tube/LibreTube
NOTESEOF

gh release create "$TAG" \
  --repo "$GITHUB_REPOSITORY" \
  --title "KidsTube $VERSION" \
  --notes-file /tmp/g-notes.md \
  "$SIGNED"
rm -f /tmp/g-notes.md
ok "Publicado: $TAG"
