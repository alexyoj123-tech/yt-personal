#!/usr/bin/env bash
# Genera nombres random para los 2 APKs (yt.apk, ytm.apk) y un
# manifest.json que mapea "yt"/"ytm" → nombre random + URL final del
# release. La página docs/familia.html lee el manifest para mostrar
# los links correctos.
#
# Formato del nombre random:
#   <SEG1>-<SEG2>-<SEG3>-<SEG4>-<SEG5>-<SEG6>-<SEG7>-<SUFFIX>.apk
#   - 7 segmentos de 4 chars ASCII alfanumérico (excluyendo confusos
#     como 0/O y 1/l/I para legibilidad)
#   - 1 sufijo aleatorio de 5 chars
# Total: ~35 chars antes de la extensión .apk
#
# Banned words (case-insensitive, regenera si aparecen):
#   youtube, music, morphe, family, alexy, yt, ytm
#
# Env:
#   IN_DIR   - directorio con yt.apk + ytm.apk + source-tag.txt
#   OUT_DIR  - directorio de salida (mismos archivos pero renombrados)
#   REPO     - para construir browser_download_url en el manifest
#   TAG      - tag del release ytp-f-family-* (ej. ytp-f-family-2026-04-26)

set -euo pipefail
: "${IN_DIR:?IN_DIR requerido}"
: "${OUT_DIR:?OUT_DIR requerido}"
: "${REPO:?REPO requerido}"
: "${TAG:?TAG requerido}"

mkdir -p "$OUT_DIR"

# Generar nombre random (Python por consistencia y secrets module).
gen_name() {
  python3 - <<'PYEOF'
import secrets, re

# Charset: ASCII alfanumérico legible (sin 0OoIl1)
charset = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
banned = re.compile(r"(youtube|music|morphe|family|alexy|yt|ytm)", re.IGNORECASE)

while True:
    segments = ["".join(secrets.choice(charset) for _ in range(4)) for _ in range(7)]
    suffix = "".join(secrets.choice(charset) for _ in range(5))
    name = "-".join(segments) + "-" + suffix + ".apk"
    if not banned.search(name):
        print(name)
        break
PYEOF
}

YT_RANDOM=$(gen_name)
YTM_RANDOM=$(gen_name)

echo "[rename] yt.apk  → $YT_RANDOM"
echo "[rename] ytm.apk → $YTM_RANDOM"

cp -f "$IN_DIR/yt.apk"  "$OUT_DIR/$YT_RANDOM"
cp -f "$IN_DIR/ytm.apk" "$OUT_DIR/$YTM_RANDOM"

# SHA-256 para el manifest (verificación de integridad client-side opcional).
SHA_YT=$(sha256sum "$OUT_DIR/$YT_RANDOM"  | awk '{print $1}')
SHA_YTM=$(sha256sum "$OUT_DIR/$YTM_RANDOM" | awk '{print $1}')

SIZE_YT=$(stat -c%s "$OUT_DIR/$YT_RANDOM")
SIZE_YTM=$(stat -c%s "$OUT_DIR/$YTM_RANDOM")

SOURCE_TAG=$(cat "$IN_DIR/source-tag.txt")
BUILD_DATE=$(date -u +%Y-%m-%d)
EXPIRES_TS=$(date -u -d "+90 days" +%Y-%m-%d)

cat > "$OUT_DIR/manifest.json" <<EOF
{
  "schema_version": 1,
  "tag": "$TAG",
  "build_date": "$BUILD_DATE",
  "expires_date": "$EXPIRES_TS",
  "expires_days": 90,
  "source_release": "$SOURCE_TAG",
  "apps": {
    "yt": {
      "filename": "$YT_RANDOM",
      "size_bytes": $SIZE_YT,
      "sha256": "$SHA_YT",
      "url": "https://github.com/$REPO/releases/download/$TAG/$YT_RANDOM"
    },
    "ytm": {
      "filename": "$YTM_RANDOM",
      "size_bytes": $SIZE_YTM,
      "sha256": "$SHA_YTM",
      "url": "https://github.com/$REPO/releases/download/$TAG/$YTM_RANDOM"
    }
  }
}
EOF

echo "[rename] Manifest:"
cat "$OUT_DIR/manifest.json"
