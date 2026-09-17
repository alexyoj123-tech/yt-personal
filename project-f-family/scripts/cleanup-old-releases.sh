#!/usr/bin/env bash
# Borra releases ytp-f-family-* con MÁS de 90 días desde su publicación.
#
# CRÍTICO: este script SOLO toca tags que matchean exactamente el
# prefijo "ytp-f-family-". Hace skip explícito de cualquier otro
# (ytp-a-*, ytp-d-*, ytp-c-*, etc.).
#
# Env:
#   GH_TOKEN  - token con scope `repo`
#   REPO      - owner/repo
#   MAX_DAYS  - opcional, default 90
#   DRY_RUN   - opcional, "1" lista qué borraría sin borrar

set -euo pipefail
: "${GH_TOKEN:?GH_TOKEN requerido}"
: "${REPO:?REPO requerido}"
MAX_DAYS="${MAX_DAYS:-90}"
DRY_RUN="${DRY_RUN:-0}"

NOW_TS=$(date -u +%s)

# El release ytp-f-family-* MAS RECIENTE nunca se borra, aunque pase MAX_DAYS.
# Desde que project-f solo publica cuando cambia algo, el mismo release puede
# quedarse vigente mas de 90 dias; borrarlo dejaria a la familia con
# docs/family-manifest.json apuntando a un release que ya no existe.
NEWEST_F=$(gh release list --repo "$REPO" --limit 200 --json tagName,publishedAt \
  --jq '[.[] | select(.tagName | startswith("ytp-f-family-"))]
        | sort_by(.publishedAt) | reverse | .[0].tagName // empty')
[ -n "$NEWEST_F" ] && echo "[cleanup] Protegido (mas reciente): $NEWEST_F"

# Listar TODOS los releases del repo
all_releases=$(gh release list --repo "$REPO" --limit 200 \
  --json tagName,publishedAt --jq '.[] | "\(.tagName)|\(.publishedAt)"')

deleted=0
skipped=0
preserved=0

while IFS='|' read -r tag published_at; do
  # ── GUARD CRÍTICO: solo procesar ytp-f-family-* ──
  case "$tag" in
    ytp-f-family-*) ;; # OK, candidato a evaluar
    *)
      preserved=$((preserved+1))
      continue
      ;;
  esac

  # ── GUARD: nunca borrar el mas reciente ──
  if [ -n "$NEWEST_F" ] && [ "$tag" = "$NEWEST_F" ]; then
    echo "[cleanup] $tag es el mas reciente — protegido"
    skipped=$((skipped+1))
    continue
  fi

  # Calcular antigüedad
  pub_ts=$(date -u -d "$published_at" +%s 2>/dev/null || echo 0)
  age_days=$(( (NOW_TS - pub_ts) / 86400 ))

  if [ "$age_days" -le "$MAX_DAYS" ]; then
    skipped=$((skipped+1))
    continue
  fi

  # Borrar
  if [ "$DRY_RUN" = "1" ]; then
    echo "[cleanup] [DRY_RUN] borraría: $tag ($age_days días)"
  else
    echo "[cleanup] borrando $tag ($age_days días)..."
    gh release delete "$tag" --repo "$REPO" --yes --cleanup-tag 2>&1 | tail -2 || true
  fi
  deleted=$((deleted+1))
done <<< "$all_releases"

echo ""
echo "[cleanup] Resumen:"
echo "  preservados (no ytp-f-family-*): $preserved"
echo "  ytp-f-family-* dentro de ${MAX_DAYS}d (mantenidos): $skipped"
echo "  ytp-f-family-* > ${MAX_DAYS}d (borrados): $deleted"
if [ "$DRY_RUN" = "1" ]; then
  echo "  Modo: DRY_RUN (no se borró nada)"
fi
exit 0
