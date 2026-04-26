# ANTI-FRAGIL — supervivencia automática del proyecto

> "Antifragile systems gain from disorder." — Nassim Taleb
>
> Este proyecto depende de **5 upstreams externos** que pueden cambiar,
> archivarse o desaparecer en cualquier momento. La estrategia anti-frágil
> es: **no esperar a que algo se rompa para actuar — detectar el riesgo
> antes y tener el plan B ya escrito**.

## §1 Cómo funciona

El workflow `.github/workflows/health-monitor.yml` corre **cada lunes
06:00 UTC** y audita los upstreams:

1. Para cada uno: consulta API (GitHub o GitLab) → obtiene `pushed_at` y `archived`.
2. Calcula días desde el último push.
3. Si el upstream está **archivado** o **superó su threshold de días**:
   - Crea un issue automático con título `[HEALTH] <name> <ALERT> (<N> días sin actividad)` y label `health`.
   - Body incluye: link upstream, fecha último push, fallback recomendado, comando exacto de switch.
4. **Dedup:** si ya hay un issue abierto con el mismo upstream, no crea duplicado.
5. Si hay alertas, el workflow termina con exit 1 → aparece en rojo en Actions (visibilidad pasiva, no email).

Tu trabajo cuando aparezca un issue: revisarlo, decidir si actuar (a veces el upstream está temporalmente quieto pero no muerto), y aplicar el procedimiento de §3 cuando confirmes que toca migrar.

## §2 Matriz primary → fallbacks

| # | Upstream primary | Tipo | Threshold | Fallback recomendado | Anchor |
|---|------------------|------|-----------|---------------------|--------|
| 1 | `MorpheApp/morphe-patches` | GitHub | 90 días | `anddea/revanced-patches` | [§3.1](#morphe-patches) |
| 2 | `MorpheApp/morphe-cli` | GitHub | 90 días | `ReVanced/revanced-cli v5.0.1` (legacy) | [§3.2](#morphe-cli) |
| 3 | `MorpheApp/MicroG-RE` | GitHub | 90 días | `microg/GmsCore` → `ReVanced/GmsCore` | [§3.3](#microg-re) |
| 4 | `yuliskov/SmartTube` | GitHub | 180 días | `TeamNewPipe/NewPipe` → TizenTube Cobalt | [§3.4](#smarttube) |
| 5 | `gitlab.com/energylove/originproject` | GitLab | 180 días | SmartTube interno → TizenTube Cobalt | [§3.5](#origin) |

**Por qué thresholds asimétricos:**
- Morphe-* y MicroG-RE actualizan diariamente en condiciones normales — 90d sin push = anormal.
- SmartTube actualiza ~mensual — 180d evita falsos positivos en ciclos de feature largos.
- Origin (energylove) actualiza bimensual confirmado — 180d cubre 2 ciclos sin alertar.

## §3 Procedimientos por upstream

### <a id="morphe-patches"></a>§3.1 Si `MorpheApp/morphe-patches` cae

**Síntoma típico del issue:** `[HEALTH] morphe-patches STALE (>90 días)` o `ARCHIVED`.

**Diff exacto a aplicar en `.github/actions/revanced-build/action.yml`:**
```diff
  revanced_patches_repo:
-   default: 'MorpheApp/morphe-patches'
+   default: 'anddea/revanced-patches'
```

**Notas:**
- `anddea/revanced-patches` usa el mismo `morphe-patcher` y `morphe-cli` interno, así que **no hay que cambiar el CLI**.
- Última anddea verificada: v4.0.0 (2026-03-27, ~1900 stars). YT compat 19.05–20.05 (más restrictivo que Morphe v1.24+).
- **Test obligatorio:** trigger manual de `project-a-manual.yml` con YT version dentro del rango compat de anddea (probar 20.05.46) y verificar que el patch `Spoof video streams` esté presente y que el `Custom branding` use el mismo schema (path con `morphe_adaptive_*_custom.png`).
- Cerrar el issue HEALTH con comment indicando el commit del switch + tag del primer release válido con anddea.

**Si anddea también cae:** no hay tercer fallback puro de patches — escalación a Project B (cliente nativo NewPipeExtractor). Ver `docs/MIGRATION-GUIDE.md` §escalacion.

---

### <a id="morphe-cli"></a>§3.2 Si `MorpheApp/morphe-cli` cae

**Diff:**
```diff
  revanced_cli_repo:
-   default: 'MorpheApp/morphe-cli'
+   default: 'ReVanced/revanced-cli'
  revanced_cli_tag:
-   default: 'v1.7.0'
+   default: 'v5.0.1'
```

**Caveats importantes:**
- `ReVanced/revanced-cli v5.0.1` es **incompatible con `.mpp`** (patches Morphe). Solo lee `.rvp` (patches inotia00/ReVanced legacy).
- Para usar este fallback necesitás cambiar TAMBIÉN `revanced_patches_repo`. Realísticamente, si Morphe-cli cae, también necesitás migrar patches → ver §3.1.
- Si llegamos aquí es porque tanto Morphe como anddea cayeron — escenario de "crisis total". En ese punto evaluar pasarse a Project B.

---

### <a id="microg-re"></a>§3.3 Si `MorpheApp/MicroG-RE` cae

**Diff (opción A — microg/GmsCore upstream):**
```diff
  revanced_gmscore_repo:
-   default: 'MorpheApp/MicroG-RE'
+   default: 'microg/GmsCore'
  revanced_gmscore_regex:
-   default: 'microg-[0-9.]+\.apk$'
+   default: 'GmsCore-.*\.apk$'   # verificar naming exacto en releases
```

**Diff (opción B — ReVanced/GmsCore legacy):**
```diff
  revanced_gmscore_repo:
-   default: 'MorpheApp/MicroG-RE'
+   default: 'ReVanced/GmsCore'
  revanced_gmscore_regex:
-   default: 'microg-[0-9.]+\.apk$'
+   default: 'app\.revanced\.android\.gms-[0-9]+-signed\.apk$'
```

**Cuál elegir:**
- `microg/GmsCore` (upstream original) tiene mantenimiento más activo pero **vendor group ID = `org.microg.gms`** ≠ el que el patch `GmsCore support` de Morphe espera (`app.revanced` ó `app.morphe`).
- `ReVanced/GmsCore` (legacy) tiene **mismo vendor group ID `app.revanced`** que MicroG-RE — drop-in replacement sin cambios en patches. **Esta es la opción recomendada si MicroG-RE cae.**

**Consecuencia para usuarios:** cualquiera de las 2 opciones se instala como UPDATE sobre la instalación previa de MicroG-RE/ReVanced-GmsCore (mismo package `app.revanced.android.gms`). No requiere desinstalar.

---

### <a id="smarttube"></a>§3.4 Si `yuliskov/SmartTube` cae

SmartTube se incluye en cada release del proyecto A para Android TV / TV Box. Si yuliskov abandona o el repo es bloqueado:

**Opción primaria — `TeamNewPipe/NewPipe`:**
- NewPipe es el cliente OSS de YouTube más maduro (>27k stars). No es nativo TV pero es D-pad usable.
- Cambio en `apply-patches.sh`:
  ```diff
  - SMARTTUBE_REPO="${SMARTTUBE_REPO:-yuliskov/SmartTube}"
  - SMARTTUBE_REGEX="${SMARTTUBE_REGEX:-SmartTube_stable_.*_arm64-v8a\\.apk$}"
  + SMARTTUBE_REPO="${SMARTTUBE_REPO:-TeamNewPipe/NewPipe}"
  + SMARTTUBE_REGEX="${SMARTTUBE_REGEX:-NewPipe_v.*\\.apk$}"
  ```
- Renombrar paso/asset a `newpipe-<version>.apk` en sign-apks.sh + create-release.sh para claridad.

**Opción secundaria — TizenTube Cobalt:**
- Solo aplica si tu TV es Samsung con Tizen OS (el TV Box Claro 4K no es Tizen → no aplica).
- Documentación: <https://github.com/reisxd/TizenTube>

**Opción terciaria — mantener versión cached:**
- Tu repo ya tiene SmartTube firmado en releases anteriores (`ytp-a-2026.04.23` con `smarttube-31.57s.apk`).
- Mientras yuliskov decide qué hacer, SmartTube seguirá funcionando hasta que YouTube rompa la compat server-side. Comprar tiempo: 6-18 meses típicos.

---

### <a id="origin"></a>§3.5 Si `gitlab.com/energylove/originproject` cae

Este es el caso con MENOS fallbacks porque YouTube Origin es único en su nicho (wrapper de YT TV oficial con ad-block + DRM Widevine preservado).

**Opción primaria — SmartTube (interno):**
- Tu repo ya genera `smarttube-*.apk` cada vez que el proyecto A corre. Es el reemplazo natural si Origin cae — pierdes la UI 100% oficial de Origin pero ganás un cliente OSS auditable.
- No hay diff a hacer en código — simplemente dejás de instalar Origin y seguís con SmartTube.

**Opción secundaria — TizenTube Cobalt o YouTube Advanced Retro:**
- Documentadas en `docs/MIGRATION-GUIDE-TV.md` §fallbacks.
- Solo aplican según OS del TV Box.

**Opción terciaria — pagar YouTube Premium $12/mes:**
- Si todos los caminos OSS para TV se cierran, comprar Premium funciona en el TV Box oficial (app YouTube TV de Google ya viene preinstalada en Claro 4K).
- Documentado como "última escalación" en `docs/IOS-FUTURO.md` (donde aplicaba para iOS) — mismo razonamiento aplica a TV.

**Plan de acción:**
1. Cuando aparezca el issue HEALTH para Origin: chequear manualmente <https://gitlab.com/energylove/originproject/-/tree/main/Releases>.
2. Si confirmás abandono: deshabilitar workflow `project-d-weekly.yml` (`gh workflow disable project-d-weekly.yml --repo ...`) para no spamear releases vacías.
3. Documentar en CONTINUIDAD.md el switch a SmartTube como cliente TV principal.

## §4 Caso histórico: migración inotia00 → Morphe (2026-04-23)

Este caso es el **prototipo de cómo se ejecuta una migración mayor** en este proyecto. Lo documentamos para que futuros mantenedores tengan un ejemplo real.

### Síntoma original
- En abril 2026 confirmamos que `inotia00/revanced-patches` quedó archivado en marzo 2026 (último commit 2026-03-10).
- Builds del Project A con `inotia00 v5.14.1` + YT 19.44.39 empezaron a recibir HTTP 400 server-side de YouTube (anti-bot reforzado).
- Test en device del A04e: YT parcheado mostraba "Se produjo un error" inmediatamente al abrir, antes de cualquier interacción.

### Investigación (~2h)
1. Verificamos que `inotia00` estaba archivado vía `gh api repos/inotia00/revanced-patches`.
2. Buscamos forks activos: `anddea/revanced-patches` activo pero usa `morphe-patcher` propio.
3. Descubrimos que el mismo equipo de inotia00 montó **MorpheApp** como sucesor oficial (organización GitHub nueva).
4. Confirmamos los 4 componentes Morphe activos:
   - `MorpheApp/morphe-patches v1.24.0` (`.mpp`, 6.9 MB)
   - `MorpheApp/morphe-cli v1.7.0` (drop-in syntax compat con CLI v5)
   - `MorpheApp/MicroG-RE v6.1.3` (12.8 MB, 8× más liviano que ReVanced/GmsCore)
   - `MorpheApp/morphe-patcher` (interno, no se usa directamente)
5. Verificamos que YT 20.47.62 está soportada por morphe-patches v1.24.0 + disponible en APKMirror.

### Cambios aplicados
1. `.github/actions/revanced-build/action.yml` — 4 defaults actualizados (Bug #11: composite action defaults vs bash fallback).
2. `project-a/scripts/apply-patches.sh` — nuevos `-e "Custom branding" -O "customName=..." -O "customIcon=..."` + `-e "Change package name"` con sintaxis Morphe.
3. `project-a/scripts/apkmirror_download.py` — fix priorización arm64-v8a > universal > fallback (Bug #12: scraper elegía arm-v7a por accidente).
4. Íconos oficiales Q4 2024 re-extraídos del APK YT 20.47.62 + YTM 8.47.56 con apktool, organizados en `project-a/assets/morphe-icons/<app>/mipmap-<dpi>/morphe_adaptive_{background,foreground}_custom.png`.
5. Docs `MIGRATION-GUIDE.md`, `CONTINUIDAD.md`, `TROUBLESHOOTING.md` actualizados.

### Resultado verificado
- Release `ytp-a-2026.04.23` publicado con 4 APKs firmados con `yt-personal`.
- YT package post-rename: `app.morphe.android.youtube` (verificado con `aapt2 dump badging`).
- Patch `Spoof video streams` aplicado (resuelve HTTP 400 server-side).
- Test en device A04e: ✓ funciona.

### Lecciones para próximas migraciones
1. **Mantener defaults en UN solo lugar** — composite action YAML, no scripts bash. Si están en ambos, pueden des-sincronizarse (Bug #11).
2. **Los íconos se pueden reusar entre versiones** — Google rara vez cambia el ícono Q4 (lo hizo en oct 2024). Si la próxima migración mantiene el mismo branding, los PNGs commiteados siguen sirviendo.
3. **Verificar `package_name` esperado** post-patch en `apply-patches.sh` con `aapt2` — atrapa silent-fails de patches que no se aplicaron.
4. **Re-extraer íconos solo si el target version cambia significativamente** (ej. del Q4 2024 al Q1 2026 si Google rebrand).

## §5 Cómo extender el sistema

Para añadir un upstream nuevo:

1. **Editar `.github/scripts/health-monitor.sh`:** añadir una entrada al array `UPSTREAMS`:
   ```bash
   UPSTREAMS=(
     ...
     "nuevo-upstream|github|owner/repo|90|nuevo-upstream"
   )
   ```
2. **Crear sección §3.X** en este documento (`docs/ANTI-FRAGIL.md`) con el procedimiento de fallback.
3. **Verificar threshold**: chequear cadencia real de updates del upstream (ver `pushed_at` histórico de los últimos 12 meses) y elegir threshold = 2× cadencia normal.
4. **Documentar en CONTINUIDAD.md §7** que el upstream se añadió.

## §6 Decisiones de diseño

- **Issues automáticos > emails:** los emails de GitHub pueden filtrarse a spam. Issues abiertos quedan visibles en Actions y en `/issues` del repo, imposible de no notar.
- **Dedup por título:** evita spam de issues idénticos. Un solo issue abierto por upstream a la vez.
- **Exit 1 si hay alertas (en modo non-dry):** el workflow aparece en rojo en GitHub Actions UI, dando una segunda capa de visibilidad sin necesidad de notificaciones.
- **Threshold asimétrico:** 90d para upstreams diarios, 180d para mensuales/bimensuales. Evita ruido innecesario.
- **GitLab API anonymous:** suficiente (60 req/h, usamos 1 req/semana). No requiere token GitLab.
