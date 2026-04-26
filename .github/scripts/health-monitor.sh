#!/usr/bin/env bash
# Audita los upstreams de los que depende este proyecto y abre issues
# automáticos si alguno está en riesgo (archivado o > N días sin push).
#
# Llamado por:
#   .github/workflows/health-monitor.yml      (cron lunes 06:00 UTC)
#   .github/workflows/health-check-manual.yml (dispatch on-demand con inputs)
#
# Env requeridas:
#   GH_TOKEN     - token con scope `repo` (issues + read api)
#   REPO         - github repo destino para issues (ej. owner/yt-personal)
#
# Env opcionales:
#   DRY_RUN              - "1" → solo logea, no crea issues
#   THRESHOLD_OVERRIDE   - número → si está set, sustituye TODOS los thresholds
#                          (útil para testing; vacío = usar defaults asimétricos)
#
# Definición de upstreams: UPSTREAMS=(name|type|ref|threshold_days|anchor)
#   name             - identificador corto, usado en title de issue + dedup
#   type             - "github" o "gitlab"
#   ref              - "owner/repo" (github) o "project_id" numérico (gitlab)
#   threshold_days   - umbral en días sin push (override por upstream según
#                      cadencia real del proyecto)
#   anchor           - id de sección dentro de docs/ANTI-FRAGIL.md (sin "#")
#
# Salida:
#   - stdout: tabla resumen con estado de cada upstream
#   - stderr: errores transitorios (warnings)
#   - efecto: 0..N issues nuevos creados (skip si ya existe uno abierto)

set -euo pipefail

: "${REPO:?REPO requerido (ej. alexyoj123-tech/yt-personal)}"
: "${GH_TOKEN:?GH_TOKEN requerido}"
DRY_RUN="${DRY_RUN:-0}"
THRESHOLD_OVERRIDE="${THRESHOLD_OVERRIDE:-}"

# 5 upstreams del proyecto. Thresholds asimétricos según cadencia real:
# - Morphe-patches/cli/MicroG-RE: actualizan diariamente → 90d alerta
# - SmartTube: actualizan ~mensual → 180d alerta
# - Origin (energylove GitLab): actualizan bimensual → 180d alerta
UPSTREAMS=(
  "morphe-patches|github|MorpheApp/morphe-patches|90|morphe-patches"
  "morphe-cli|github|MorpheApp/morphe-cli|90|morphe-cli"
  "microg-re|github|MorpheApp/MicroG-RE|90|microg-re"
  "smarttube|github|yuliskov/SmartTube|180|smarttube"
  "origin|gitlab|62144433|180|origin"
)

NOW_TS=$(date -u +%s)
ANTI_FRAGIL_BASE="https://github.com/${REPO}/blob/main/docs/ANTI-FRAGIL.md"

# Asegurar label 'health' (idempotente — gh devuelve error si ya existe; no fatal)
if [ "$DRY_RUN" != "1" ]; then
  gh label create health \
    --repo "$REPO" \
    --color "d93f0b" \
    --description "Health-monitor: upstream stale/archived" \
    >/dev/null 2>&1 || true
fi

# Por cada upstream
total=0
healthy=0
alerts=0
created=0
skipped_dup=0

printf "%-18s %-8s %-12s %-7s  %s\n" "upstream" "type" "last_push" "days" "status"
printf "%-18s %-8s %-12s %-7s  %s\n" "---" "---" "---" "----" "------"

for entry in "${UPSTREAMS[@]}"; do
  total=$((total+1))
  IFS='|' read -r name type ref threshold anchor <<< "$entry"

  # Override threshold global si se indicó
  if [ -n "$THRESHOLD_OVERRIDE" ]; then
    threshold="$THRESHOLD_OVERRIDE"
  fi

  # Fetch metadata según tipo
  archived="?"; pushed=""; upstream_url=""; api_err=0
  if [ "$type" = "github" ]; then
    raw=$(gh api "repos/$ref" 2>/dev/null) || api_err=$?
    if [ "$api_err" -eq 0 ]; then
      archived=$(jq -r '.archived' <<<"$raw")
      pushed=$(jq -r '.pushed_at' <<<"$raw")
      upstream_url="https://github.com/$ref"
    fi
  elif [ "$type" = "gitlab" ]; then
    raw=$(curl -sf "https://gitlab.com/api/v4/projects/$ref" 2>/dev/null) || api_err=$?
    if [ "$api_err" -eq 0 ]; then
      archived=$(jq -r '.archived // false' <<<"$raw")
      pushed=$(jq -r '.last_activity_at' <<<"$raw")
      path=$(jq -r '.path_with_namespace' <<<"$raw")
      upstream_url="https://gitlab.com/$path"
    fi
  fi

  if [ "$api_err" -ne 0 ] || [ -z "$pushed" ]; then
    printf "%-18s %-8s %-12s %-7s  %s\n" "$name" "$type" "n/a" "n/a" "⚠ API_ERROR"
    alerts=$((alerts+1))
    title="[HEALTH] $name API_ERROR"
    body=$(cat <<EOF
**Upstream:** \`$ref\` ($type)
**Status:** No pude consultar la API (rc=$api_err).

Posibles causas:
- Rate limit (poco probable con 5 reqs/semana).
- El repo / proyecto fue eliminado o renombrado.
- Cambio de URL del API endpoint.

**Acción:**
1. Verificar manualmente: $upstream_url (si aplica) o $ref.
2. Si el repo desapareció: ver fallback en [docs/ANTI-FRAGIL.md#$anchor]($ANTI_FRAGIL_BASE#$anchor).
3. Cerrar este issue tras resolverlo, o reabrir si reaparece.

_Generado automáticamente por \`.github/workflows/health-monitor.yml\`._
EOF
    )
  else
    pushed_ts=$(date -d "$pushed" -u +%s 2>/dev/null || date -u -j -f "%Y-%m-%dT%H:%M:%SZ" "$pushed" "+%s" 2>/dev/null || echo 0)
    days=$(( (NOW_TS - pushed_ts) / 86400 ))

    alert_kind=""
    if [ "$archived" = "true" ]; then
      alert_kind="ARCHIVED"
    elif [ "$days" -gt "$threshold" ]; then
      alert_kind="STALE"
    fi

    if [ -z "$alert_kind" ]; then
      printf "%-18s %-8s %-12s %-7s  %s\n" "$name" "$type" "${pushed:0:10}" "$days" "✓ OK (threshold ${threshold}d)"
      healthy=$((healthy+1))
      continue
    fi

    alerts=$((alerts+1))
    printf "%-18s %-8s %-12s %-7s  %s\n" "$name" "$type" "${pushed:0:10}" "$days" "✗ $alert_kind"

    title="[HEALTH] $name $alert_kind ($days días sin actividad)"
    body=$(cat <<EOF
**Upstream:** \`$ref\` ($upstream_url)
**Estado:** \`$alert_kind\`
**Último push:** \`$pushed\` ($days días atrás)
**Threshold configurado:** $threshold días

**Qué hacer:**

1. Verificar el upstream manualmente: $upstream_url
   - Si está temporalmente inactivo pero el mantenedor está vivo, podés cerrar este issue y reabrirlo en otra alerta.
   - Si confirmás que está abandonado o archivado, procedé con el fallback.

2. **Fallback documentado:** ver [docs/ANTI-FRAGIL.md#$anchor]($ANTI_FRAGIL_BASE#$anchor) para el comando exacto de switch.

3. Tras aplicar el fallback, **cerrar este issue** con un comment indicando:
   - Commit del switch
   - Nuevo upstream activo
   - Tag del primer release con el upstream nuevo

_Generado automáticamente por \`.github/workflows/health-monitor.yml\` el $(date -u +%Y-%m-%d)._
EOF
    )
  fi

  # Dedup: ¿ya hay un issue abierto con título similar?
  existing=$(gh issue list \
    --repo "$REPO" \
    --state open \
    --label health \
    --search "[HEALTH] $name in:title" \
    --json number,title \
    --jq ".[] | select(.title | startswith(\"[HEALTH] $name\")) | .number" 2>/dev/null | head -1)

  if [ -n "$existing" ]; then
    printf "    ↳ ya existe issue #%s, skip\n" "$existing"
    skipped_dup=$((skipped_dup+1))
    continue
  fi

  if [ "$DRY_RUN" = "1" ]; then
    printf "    ↳ [DRY_RUN] crearia issue: %s\n" "$title"
  else
    issue_url=$(gh issue create \
      --repo "$REPO" \
      --title "$title" \
      --label health \
      --body "$body" 2>&1 | tail -1)
    printf "    ↳ issue creado: %s\n" "$issue_url"
    created=$((created+1))
  fi
done

echo ""
echo "═══ Resumen ═══"
echo "  Total upstreams:   $total"
echo "  Healthy:           $healthy"
echo "  Alertas:           $alerts"
echo "  Issues creados:    $created"
echo "  Skipped (dedup):   $skipped_dup"
[ "$DRY_RUN" = "1" ] && echo "  Modo:              DRY_RUN (no se crearon issues)"

# Exit con código != 0 si hubo alertas SOLO en modo non-dry
# (esto hace que el workflow aparezca en rojo en Actions, dando visibilidad).
# En dry_run no fallamos para que el log pueda verse sin "fail".
if [ "$DRY_RUN" != "1" ] && [ "$alerts" -gt 0 ]; then
  echo ""
  echo "Alertas activas detectadas — workflow exit 1 para visibilidad."
  exit 1
fi
exit 0
