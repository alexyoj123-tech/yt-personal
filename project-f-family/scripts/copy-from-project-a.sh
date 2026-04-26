#!/usr/bin/env bash
# Copia los APKs YouTube + YT Music ya parcheados desde el último
# release ytp-a-* del proyecto A. NO los modifica — solo los toma
# para que rename-random.sh + el workflow los republiquen con nombres
# random como ytp-f-family-*.
#
# Salida:
#   $OUT_DIR/yt.apk     — APK YouTube parcheado de project-a (intacto)
#   $OUT_DIR/ytm.apk    — APK YouTube Music parcheado de project-a (intacto)
#
# Env requeridas:
#   GH_TOKEN  - token con scope `repo`
#   REPO      - owner/repo (ej. alexyoj123-tech/yt-personal)
#   OUT_DIR   - directorio donde dejar los APKs

set -euo pipefail
: "${GH_TOKEN:?GH_TOKEN requerido}"
: "${REPO:?REPO requerido}"
: "${OUT_DIR:?OUT_DIR requerido}"

mkdir -p "$OUT_DIR"

echo "[copy] Buscando último release ytp-a-* en $REPO..."
LATEST_A=$(gh release list --repo "$REPO" --limit 30 --json tagName \
  --jq '[.[] | select(.tagName | startswith("ytp-a-"))][0].tagName')

if [ -z "$LATEST_A" ]; then
  echo "[copy] ERROR: no encontré ningún release ytp-a-* en $REPO" >&2
  exit 1
fi

echo "[copy] Usando release: $LATEST_A"

echo "[copy] Descargando youtube-personal-*.apk → $OUT_DIR/yt.apk"
gh release download "$LATEST_A" --repo "$REPO" \
  -p 'youtube-personal-*.apk' -O "$OUT_DIR/yt.apk" --clobber

echo "[copy] Descargando youtube-music-personal-*.apk → $OUT_DIR/ytm.apk"
gh release download "$LATEST_A" --repo "$REPO" \
  -p 'youtube-music-personal-*.apk' -O "$OUT_DIR/ytm.apk" --clobber

# Validación: ambos archivos existen y tienen tamaño razonable (>10 MB c/u)
for f in yt.apk ytm.apk; do
  size=$(stat -c%s "$OUT_DIR/$f" 2>/dev/null || echo 0)
  if [ "$size" -lt 10000000 ]; then
    echo "[copy] ERROR: $f mide $size bytes (<10MB) — descarga incompleta" >&2
    exit 1
  fi
  echo "[copy]   $f OK ($size bytes)"
done

# Persistir el tag fuente para que el workflow lo incluya en el release notes.
echo "$LATEST_A" > "$OUT_DIR/source-tag.txt"
echo "[copy] OK. Source tag guardado en $OUT_DIR/source-tag.txt"
