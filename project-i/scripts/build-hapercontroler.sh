#!/usr/bin/env bash
# Compila, firma, verifica y publica HAPER CONTROLER (project-i).
#
# Se ejecuta desde la raiz del repo. Todo lo que puede salir mal aborta el
# build ANTES de publicar: un release con el package o el label equivocado es
# peor que no tener release.
set -euo pipefail

APP_DIR="project-i"
PKG_ESPERADO="io.github.alexyoj123.hapercontroler"
# Una sola L a proposito: es la marca del dueno, no un typo.
LABEL_ESPERADO="HAPER CONTROLER"
PREFIJO_TAG="ytp-i-HaperControler"

log() { echo "==> $*"; }
fail() { echo "!! $*" >&2; exit 1; }

# ------------------------------------------------------------------ entorno

[ -d "$APP_DIR" ] || fail "no encuentro $APP_DIR (ejecutar desde la raiz del repo)"

for var in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
  [ -n "${!var:-}" ] || fail "falta el secret $var"
done

VERSION_NAME="$(grep -oE 'versionName = "[^"]+"' "$APP_DIR/app/build.gradle.kts" | head -1 | cut -d'"' -f2)"
[ -n "$VERSION_NAME" ] || fail "no pude leer versionName de app/build.gradle.kts"
log "versionName = $VERSION_NAME"

# ---------------------------------------------------------------- keystore

KEYSTORE_DIR="$(mktemp -d)"
KEYSTORE_PATH="$KEYSTORE_DIR/release.jks"
echo "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KEYSTORE_PATH"
[ -s "$KEYSTORE_PATH" ] || fail "el keystore decodificado quedo vacio"
# Se borra pase lo que pase, incluso si el build revienta.
trap 'rm -rf "$KEYSTORE_DIR"' EXIT

export HAPER_KEYSTORE_PATH="$KEYSTORE_PATH"
export HAPER_KEYSTORE_PASSWORD="$ANDROID_KEYSTORE_PASSWORD"
export HAPER_KEY_ALIAS="$ANDROID_KEY_ALIAS"
export HAPER_KEY_PASSWORD="$ANDROID_KEY_PASSWORD"

# ----------------------------------------------------------------- compilar

log "compilando release"
pushd "$APP_DIR" > /dev/null
chmod +x ./gradlew
./gradlew --no-daemon --stacktrace clean :app:assembleRelease
popd > /dev/null

APK="$APP_DIR/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || fail "no se genero el APK de release"
log "APK: $APK ($(du -h "$APK" | cut -f1))"

# --------------------------------------------------- verificaciones duras

log "verificando identidad del APK con aapt2"
BADGING="$(aapt2 dump badging "$APK")"

PKG_REAL="$(echo "$BADGING" | grep -oE "^package: name='[^']+'" | cut -d"'" -f2)"
LABEL_REAL="$(echo "$BADGING" | grep -oE "^application-label:'[^']+'" | cut -d"'" -f2)"

[ "$PKG_REAL" = "$PKG_ESPERADO" ] || fail "package incorrecto: '$PKG_REAL' (esperaba '$PKG_ESPERADO')"
[ "$LABEL_REAL" = "$LABEL_ESPERADO" ] || fail "label incorrecto: '$LABEL_REAL' (esperaba '$LABEL_ESPERADO')"
log "package = $PKG_REAL · label = $LABEL_REAL"

# resources.arsc sin comprimir (Android 11+ lo exige) + alineacion a 4 bytes.
log "verificando alineacion y compresion de resources.arsc"
# Columnas de `unzip -v`: Length Method Size Cmpr Date Time CRC-32 Name
ARSC_METODO="$(unzip -v "$APK" | awk '$8=="resources.arsc" {print $2}')"
[ "$ARSC_METODO" = "Stored" ] || fail "resources.arsc esta comprimido ($ARSC_METODO); Android 11+ lo rechaza"
zipalign -c -v 4 "$APK" > /dev/null || fail "el APK no esta alineado a 4 bytes"

log "verificando la firma"
SHA_ESPERADA="484063e1f35e8b8e20d75b608b32cdd8e007ce57a133d2dccce9f48db9102987"
SHA_FIRMA=""

# apksigner es lo preferido, pero ni su disponibilidad ni el texto exacto de
# su salida son estables entre versiones de build-tools. Todo lo de abajo
# lleva `|| true` a proposito: con `set -euo pipefail`, un `grep` sin
# coincidencias dentro de una sustitucion de comandos mata el script en
# silencio — que es exactamente como fallaron los primeros runs.
if command -v apksigner > /dev/null 2>&1; then
  apksigner verify --print-certs "$APK" > /tmp/project-i-certs.txt 2>&1 || true
  echo "--- salida de apksigner ---"
  cat /tmp/project-i-certs.txt 2>/dev/null || true
  echo "---------------------------"
  SHA_FIRMA="$(grep -iE 'SHA-256 digest' /tmp/project-i-certs.txt 2>/dev/null |
    head -1 | awk '{print $NF}' | tr -d ':' | tr 'A-Z' 'a-z' || true)"
  [ -n "$SHA_FIRMA" ] && log "firma leida con apksigner"
else
  log "apksigner no esta en el PATH"
fi

# keytool lee el certificado directo del APK y viene con cualquier JDK.
if [ -z "$SHA_FIRMA" ]; then
  SHA_FIRMA="$(keytool -printcert -jarfile "$APK" 2>/dev/null |
    grep -iE 'SHA256:' | head -1 | awk '{print $2}' | tr -d ':' | tr 'A-Z' 'a-z' || true)"
  [ -n "$SHA_FIRMA" ] && log "firma leida con keytool"
fi

[ -n "$SHA_FIRMA" ] || fail "no pude leer la firma del APK con ninguna herramienta"
[ "$SHA_FIRMA" = "$SHA_ESPERADA" ] || fail "el APK NO esta firmado con el keystore del repo (SHA-256 leida: $SHA_FIRMA)"
log "firma correcta (SHA-256 del repo)"

# --------------------------------------------------------------- publicar

if [ "${PUBLICAR:-true}" != "true" ]; then
  log "PUBLICAR=false: se compilo y verifico, no se publica release"
  exit 0
fi

FECHA="$(date -u +%Y%m%d)"
TAG_BASE="${PREFIJO_TAG}-${VERSION_NAME}+${FECHA}"
TAG="$TAG_BASE"

# REGLA DURA DEL REPO: no se borra ningun tag ni release nunca. Si el tag ya
# existe se republica con sufijo -2, -3, ... Borrar tags ya corrompio la cache
# del CDN de GitHub antes.
SUFIJO=2
while gh release view "$TAG" --repo "$GITHUB_REPOSITORY" > /dev/null 2>&1; do
  TAG="${TAG_BASE}-${SUFIJO}"
  SUFIJO=$((SUFIJO + 1))
  [ "$SUFIJO" -gt 20 ] && fail "demasiados releases con el mismo tag base hoy"
done
log "tag a publicar: $TAG"

ENTREGA="$APP_DIR/build/out"
mkdir -p "$ENTREGA"
cp "$APK" "$ENTREGA/HaperControler-${VERSION_NAME}.apk"

NOTAS="$(mktemp)"
cat > "$NOTAS" <<EOF
Control remoto universal de TV. Codigo propio, sin anuncios, sin suscripcion,
sin telemetria.

**Version:** $VERSION_NAME
**Paquete:** \`$PKG_ESPERADO\`
**minSdk:** 28 · **targetSdk:** 36

> ### Si tenias VallEthRemote instalado, desinstalalo primero
> Esta version cambio de nombre y de \`applicationId\`
> (\`io.github.alexyoj123.vallethremote\` -> \`$PKG_ESPERADO\`).
> Android las trata como dos apps distintas: **no se actualiza encima**, hay
> que desinstalar la vieja a mano. Los releases viejos siguen publicados.

### Que hace
- Descubre TVs en la red por mDNS, SSDP y sondeo de puertos.
- Samsung Tizen: teclas, abrir apps, Wake-on-LAN, emparejamiento con token.
- Android TV por ADB: teclas con shell persistente (baja latencia), texto,
  busqueda por intents e instalacion de APKs con progreso real.
- Android TV sin ADB: protocolo del control oficial de Google TV
  (protobuf 6466/6467) con emparejamiento por codigo de 6 digitos.
- LG webOS por SSAP, con cursor nativo.
- Roku por ECP.
- Trackpad con cursor real via Bluetooth HID (mouse + teclado + volumen).
- Voz de una sola pulsacion con reglas locales, sin mandar audio a ningun lado.
- **Mis apps:** abre paquetes instalados aunque el launcher del aparato los
  esconda (las apps sin LEANBACK_LAUNCHER no salen en el inicio de un Android TV).
- **Despliegue:** revisa a diario los releases del repo e instala las
  actualizaciones en el aparato por ADB, que instala como usuario \`shell\` y
  por eso esquiva la restriccion de "apps desconocidas" del operador.

### Honestidad de la UI
Si una capacidad no existe en el dispositivo conectado, el boton no aparece o
se explica el motivo real. Nunca un boton que parece funcionar y no hace nada.

Detalle tecnico y limitaciones conocidas: \`docs/CONTINUIDAD-project-i.md\`.
EOF

log "publicando release"
gh release create "$TAG" \
  --repo "$GITHUB_REPOSITORY" \
  --title "HAPER CONTROLER $VERSION_NAME" \
  --notes-file "$NOTAS" \
  "$ENTREGA/HaperControler-${VERSION_NAME}.apk"

log "listo: $TAG"
