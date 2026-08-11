#!/usr/bin/env bash
# project-g: compila VallEthTube desde el codigo fuente de LibreTube.
#
# POR QUE DESDE FUENTE Y NO RE-FIRMANDO EL APK OFICIAL:
#   1. El bug de pantalla negra (upstream #8254, causado por SABR de YouTube)
#      se corrigio el 2026-06-09, pero el ultimo release oficial es del
#      2026-05-28. Re-firmar el APK publicado arrastra el bug; compilar
#      desde master lo incluye.
#   2. Permite cambiar el nombre real de la app (launcher + interior).
#   3. Permite poner icono propio.
#
# LICENCIA: LibreTube es GPL-3.0. Redistribuir una version modificada esta
# permitido siempre que el codigo siga disponible y se declaren los cambios.
# Este script ES la declaracion de cambios y el repo es publico.
set -euo pipefail

info() { echo "[INFO]  $*"; }
ok()   { echo "[OK]    $*"; }
die()  { echo "[ERR]   $*" >&2; exit 1; }
step() { echo ""; echo "──── $* ────"; }

APP_NAME="VallEthTube"
APP_SLUG="VallEthTube"
UPSTREAM="${LIBRETUBE_REPO:-libre-tube/LibreTube}"
BRANCH="${LIBRETUBE_BRANCH:-master}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PG_DIR="$REPO_ROOT/project-g"
BUILD_DIR="$PG_DIR/build"
OUT_DIR="$BUILD_DIR/out"
META_DIR="$BUILD_DIR/meta"
TOOLS_DIR="$BUILD_DIR/tools"
SRC_DIR="$BUILD_DIR/src"
ASSETS_DIR="$PG_DIR/assets"
mkdir -p "$OUT_DIR" "$META_DIR" "$TOOLS_DIR"

# ── Clonar fuente ─────────────────────────────────────────────────────
step "Clonando $UPSTREAM ($BRANCH)"
rm -rf "$SRC_DIR"
git clone --depth 1 --branch "$BRANCH" \
  "https://github.com/${UPSTREAM}.git" "$SRC_DIR" 2>&1 | tail -3
COMMIT="$(git -C "$SRC_DIR" rev-parse --short HEAD)"
COMMIT_DATE="$(git -C "$SRC_DIR" log -1 --format=%cd --date=short)"
info "commit $COMMIT ($COMMIT_DATE)"

GRADLE_FILE="$SRC_DIR/app/build.gradle.kts"
[ -f "$GRADLE_FILE" ] || die "No existe app/build.gradle.kts — cambio de estructura upstream"

BASE_VERSION="$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE_FILE" | head -1)"
[ -n "$BASE_VERSION" ] || die "No pude leer versionName"
VERSION="${BASE_VERSION}+${COMMIT_DATE//-/}"
TAG="ytp-g-${APP_SLUG}-${VERSION}"
info "version base $BASE_VERSION → tag $TAG"

# ── Idempotencia ──────────────────────────────────────────────────────
if gh release view "$TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  ok "Release $TAG ya existe — nada nuevo."
  exit 0
fi

# ── Renombrar la app ──────────────────────────────────────────────────
# app_name se define con resValue() en build.gradle.kts, que SOBREESCRIBE
# el valor de strings.xml. Hay que cambiarlo ahi, no en strings.xml.
step "Renombrando a $APP_NAME"
python3 - "$GRADLE_FILE" "$APP_NAME" << 'PYEOF'
import re, sys
path, name = sys.argv[1], sys.argv[2]
src = open(path, encoding="utf-8").read()
new, n = re.subn(
    r'resValue\("string",\s*"app_name",\s*"LibreTube"\)',
    f'resValue("string", "app_name", "{name}")',
    src)
if n == 0:
    sys.exit("FALLO: no encontre el resValue de app_name — revisar upstream")
open(path, "w", encoding="utf-8").write(new)
print(f"[OK]    resValue app_name → {name} ({n} ocurrencia)")
PYEOF

# Tambien en strings.xml, por si alguna pantalla lo lee directo.
STRINGS="$SRC_DIR/app/src/main/res/values/strings.xml"
if [ -f "$STRINGS" ]; then
  sed -i "s|<string name=\"app_name\" translatable=\"false\">LibreTube</string>|<string name=\"app_name\" translatable=\"false\">${APP_NAME}</string>|" "$STRINGS"
  grep -q "${APP_NAME}" "$STRINGS" && info "strings.xml actualizado"
fi

# ── Icono propio ──────────────────────────────────────────────────────
step "Instalando icono propio"
if [ -d "$ASSETS_DIR/drawable" ]; then
  cp -v "$ASSETS_DIR/drawable/"*.xml          "$SRC_DIR/app/src/main/res/drawable/"
  cp -v "$ASSETS_DIR/mipmap-anydpi-v26/"*.xml "$SRC_DIR/app/src/main/res/mipmap-anydpi-v26/"
  ok "icono reemplazado (minSdk=26, solo hacen falta los adaptive)"
else
  info "sin assets/ — se mantiene el icono original"
fi

# ── Compilar (sin firmar: signingConfig queda null sin keystore.properties) ──
step "Compilando con Gradle"
cd "$SRC_DIR"
chmod +x ./gradlew
./gradlew --no-daemon --console=plain assembleRelease

UNSIGNED="$(find "$SRC_DIR/app/build/outputs/apk/release" -name '*.apk' | head -1)"
[ -n "$UNSIGNED" ] || die "Gradle no produjo APK"
info "APK sin firmar: $(basename "$UNSIGNED") ($(du -h "$UNSIGNED" | cut -f1))"

# ── Firmar con el keystore del repo ───────────────────────────────────
# v1+v2 unicamente: v3 rompia la lectura del APK en Obtainium/Android 12+.
step "Firmando"
[ -n "${ANDROID_KEYSTORE_BASE64:-}"   ] || die "Falta ANDROID_KEYSTORE_BASE64"
[ -n "${ANDROID_KEYSTORE_PASSWORD:-}" ] || die "Falta ANDROID_KEYSTORE_PASSWORD"
[ -n "${ANDROID_KEY_ALIAS:-}"         ] || die "Falta ANDROID_KEY_ALIAS"
[ -n "${ANDROID_KEY_PASSWORD:-}"      ] || die "Falta ANDROID_KEY_PASSWORD"
KS_PATH="$TOOLS_DIR/release.jks"
printf "%s" "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KS_PATH"
[ -s "$KS_PATH" ] || die "Keystore vacio"

ALIGNED="/tmp/vt-aligned.apk"
SIGNED="$OUT_DIR/${APP_SLUG}-${VERSION}.apk"
zipalign -p -f 4 "$UNSIGNED" "$ALIGNED"
apksigner sign \
  --ks "$KS_PATH" --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
  --ks-key-alias "$ANDROID_KEY_ALIAS" --key-pass "pass:$ANDROID_KEY_PASSWORD" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
  --out "$SIGNED" "$ALIGNED"
apksigner verify --print-certs "$SIGNED" >/dev/null
rm -f "$ALIGNED"
shred -u "$KS_PATH" 2>/dev/null || rm -f "$KS_PATH"
ok "firmado: $(basename "$SIGNED") ($(du -h "$SIGNED" | cut -f1))"

# ── Verificar que el nombre quedo aplicado ────────────────────────────
step "Verificando"
if command -v aapt2 >/dev/null 2>&1; then AAPT=aapt2; else AAPT=aapt; fi
LABEL="$($AAPT dump badging "$SIGNED" 2>/dev/null | grep -oP "application-label:'\K[^']+" || echo "?")"
PKG="$($AAPT dump badging "$SIGNED" 2>/dev/null | grep -oP "package: name='\K[^']+" || echo "?")"
info "label   = $LABEL"
info "package = $PKG"
[ "$LABEL" = "$APP_NAME" ] || die "El label quedo en '$LABEL', esperaba '$APP_NAME'"
ok "nombre correcto en el APK"

# ── Metadata + release ────────────────────────────────────────────────
SHA="$(sha256sum "$SIGNED" | cut -d' ' -f1)"
cat > "$META_DIR/vallethtube.json" << METAEOF
{
  "app_name": "$APP_NAME",
  "version": "$VERSION",
  "base_version": "$BASE_VERSION",
  "upstream_commit": "$COMMIT",
  "upstream_commit_date": "$COMMIT_DATE",
  "package": "$PKG",
  "label": "$LABEL",
  "tag": "$TAG",
  "sha256": "$SHA",
  "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
METAEOF

step "Publicando $TAG"
cat > /tmp/vt-notes.md << NOTESEOF
# $APP_NAME $VERSION

App personal para los niños. **Compilada desde el codigo fuente**, no re-firmada.

- Nombre \`$APP_NAME\` en el launcher y dentro de la app
- Icono propio (diseño original)
- Base: LibreTube $BASE_VERSION, commit \`$COMMIT\` del $COMMIT_DATE
- Firmada con el keystore del repo — se instala encima de la version anterior

## Por que compilada desde fuente

El bug de pantalla negra ([upstream #8254](https://github.com/libre-tube/LibreTube/issues/8254),
causado por el protocolo SABR de YouTube) se corrigio el 2026-06-09, pero el
ultimo release oficial es del 2026-05-28. Compilando desde \`master\` se
incluye la correccion.

## Configuracion para uso infantil (una sola vez)

1. Ajustes → **Apariencia** → dejar solo la pestaña *Suscripciones*
2. Ajustes → **Contenido** → activar *Ocultar Shorts* y *Ocultar transmisiones en vivo*
3. Importar la lista de canales aprobados:
   \`project-g/kids-english-subscriptions.json\`

## Integridad
SHA-256: \`$SHA\`

## Licencia
Basado en [LibreTube](https://github.com/${UPSTREAM}) (GPL-3.0). Cambios
aplicados: nombre de la app e icono. Ver \`project-g/scripts/build-vallethtube.sh\`.
NOTESEOF

gh release create "$TAG" --repo "$GITHUB_REPOSITORY" \
  --title "$APP_NAME $VERSION" \
  --notes-file /tmp/vt-notes.md \
  "$SIGNED"
rm -f /tmp/vt-notes.md
ok "Publicado: $TAG"
