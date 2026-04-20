# Migration Guide — ¿qué hacer si algo upstream cae?

Este proyecto depende de 4 upstreams externos que podrían cambiar,
bloquearse o desaparecer: **inotia00/revanced-patches**, **APKMirror**,
**ReVanced/GmsCore**, **yuliskov/SmartTube**. Este documento es la
"póliza de seguro" — qué hacer en cada escenario, sin reinventar.

---

## Índice

- [Patches: si inotia00 cae o es bloqueado](#patches)
- [APKs: si APKMirror cambia o es bloqueado](#apkmirror)
- [GmsCore: si ReVanced/GmsCore deja de mantenerse](#gmscore)
- [TV: si SmartTube desaparece](#smarttube)
- [Escalación mayor: si YouTube rompe todo el ecosistema](#escalacion)

---

## <a id="patches"></a>Patches: si `inotia00/revanced-patches` cae

### Síntomas
- HTTP 404 o 451 al intentar acceder al repo.
- `gh release view` falla con "Repository access blocked" o "not found".
- El último commit de inotia00 es > 6 meses atrás (proyecto abandonado).

### Forks alternativos activos (verificados 2026-04)

| Fork | Formato | Compat CLI | Notas |
|------|---------|-----------|-------|
| [`anddea/revanced-patches`](https://github.com/anddea/revanced-patches) | `.mpp` (fork propio del patcher) | Requiere CLI propio (fork `app.morphe.patcher`) — no publicado en GitHub | Cambio de formato + nueva herramienta. Más trabajo pero tiene patches que inotia00 retiró (ej. "Spoof streaming data" para YT). |
| [`crimera/piko-patches`](https://github.com/crimera/piko-patches) | `.rvp` | CLI v5.x compatible | Inicialmente fork de Twitter/X patches, no siempre tiene YT. Verificar antes de migrar. |

### Procedimiento para migrar

1. Cambiar default de `REVANCED_PATCHES_REPO` en `.github/actions/revanced-build/action.yml`:
   ```yaml
   revanced_patches_repo:
     default: 'crimera/piko-patches'  # o anddea/revanced-patches
   ```
2. Si el nuevo fork usa otro formato (`.mpp` vs `.rvp`), actualizar regex en `fetch-apks.sh` y `apply-patches.sh`:
   ```bash
   PATCHES_RVP="$(ensure_tool "revanced-patches.mpp" "$PATCHES_REPO" "patches-.*\\.mpp$")"
   ```
3. Si requiere CLI distinto (ej. anddea requiere `app.morphe.patcher`), actualizar `REVANCED_CLI_REPO` e `REVANCED_CLI_TAG` al CLI compatible.
4. Actualizar `docs/APKMIRROR-SCRAPER.md` §versiones-soportadas con el set de versiones compat del nuevo fork.
5. Correr un build manual. Si falla, seguir protocolo estándar de `docs/TROUBLESHOOTING.md` §nuevos-bugs.

### Si TODOS los forks caen simultáneamente

Escalación: pasarse al Proyecto B (`project-b/yt-native-client`) — cliente nativo Kotlin con motor NewPipeExtractor. No depende de patches; lee YouTube como scraper. Ya queda documentado en `project-b/README.md`.

---

## <a id="apkmirror"></a>APKs: si APKMirror cambia HTML o bloquea

### Síntomas
- `apkmirror_download.py` falla con exit code 3, 4, o 5 (ver `docs/APKMIRROR-SCRAPER.md`).
- Error "Cloudflare JS challenge" en lugar de HTML normal.
- HTTP 429 Too Many Requests (rate limit).

### Qué revisar primero

Ver `docs/APKMIRROR-SCRAPER.md` §riesgos-y-qué-hacer-si-se-rompen. Los exit codes 2/3/4/5/6 apuntan al regex/función específica a ajustar.

### Fuentes alternativas (fallback)

Si APKMirror se vuelve inusable por completo:

| Fuente | Pros | Contras | Herramienta |
|--------|------|---------|-------------|
| **APKPure** | Soportado nativo por `apkeep` | Solo sirve versiones recientes (no histórico). No sirve para inotia00 que requiere YT 19.x. | `apkeep --download-source apk-pure` |
| **AppTeka** | Catálogo amplio | Scrape equivalente a APKMirror en complejidad. | Custom scraper. |
| **archive.org / Wayback** | APKs históricos, poca moderación | URLs impredecibles, inconsistente. | curl directo con URLs específicas. |
| **Aptoide** | Catálogo alternativo | Menos confiable, APKs a veces modificados por terceros. | API disponible pero credenciales requeridas. |
| **Extracción manual del device** | 100% auténtico | Requiere ADB + teléfono con YT pre-instalado. No escalable a CI. | `adb shell pm path com.google.android.youtube` + `adb pull`. |

### Patrones de cambios de HTML a vigilar en APKMirror

Si el scraper rompe por cambio de HTML, buscar estos patrones típicos:

- **URL structure de la version page:** `/apk/<org>/<slug>/<slug>-<ver-dashes>-release/` → estable desde 2015.
- **URL de variant page:** `/<slug>-<ver>-[N]-android-apk-download/` → estable.
- **Link /download/?key=:** el key es un hash de 40 hex; si cambia a otro formato (ej. UUID) ajustar regex `/download/\?key=[^"]+` en `find_download_key_url()`.
- **Referer validation:** APKMirror redirige a `?redirected=thank_you_invalid_referer` sin Referer correcto. Nunca confiar en redirects automáticos de urllib si falla el referer check.
- **download.php endpoint:** `/wp-content/themes/APKMirror/download.php?id=...&key=...` — pattern estable; si cambia el nombre del theme (muy improbable), ajustar `find_download_php_url()`.
- **R2 CDN URL:** Cloudflare R2 signed URL con query string `X-Amz-*`. Si APKMirror migra de R2 a otro CDN, urllib sigue el 302 igual siempre que envíes UA+Referer.

### Si aparece Cloudflare JS challenge

**Mitigación de menor a mayor impacto:**

1. Añadir delay `time.sleep(3)` entre requests en el scraper — simular navegación humana.
2. Usar `curl_cffi` (pip install, TLS fingerprinting de browser real) en lugar de urllib stdlib. Rompe nuestro criterio "stdlib only" pero es el workaround estándar.
3. Usar browser headless: Playwright/Selenium. Pesado para CI runners, ~200 MB extra.
4. Último recurso: cache local en el repo los APKs oficiales como Git LFS. Resuelve para siempre pero pesa GB.

---

## <a id="gmscore"></a>GmsCore: si `ReVanced/GmsCore` deja de mantenerse

### Síntomas
- Último release > 6 meses.
- APK no instala por incompat con Android version nueva.
- PoToken / Play Integrity fallan en dispositivos que antes funcionaban.

### Alternativas

| Alternativa | Pros | Contras |
|-------------|------|---------|
| [**microG/GmsCore**](https://github.com/microg/GmsCore) (proyecto padre original) | Más estable, 10+ años de mantenimiento | Puede no tener los hooks específicos que los patches de inotia00 esperan |
| **Mantener versión actual pineada** | Funciona hoy, no hay que tocar | Eventualmente Android nuevo rompe compat |
| **Cambiar a cliente nativo (Proyecto B)** | No depende de GmsCore | Requiere trabajo de implementación de Proyecto B |

### Procedimiento

1. Cambiar `REVANCED_GMSCORE_REPO` default en action.yml a `microg/GmsCore`.
2. Ajustar `REVANCED_GMSCORE_REGEX` al naming de microG (probablemente `GmsCore-.*\\.apk$`).
3. **Verificar que `inotia00/revanced-patches` acepte `gmsCoreVendorGroupId=com.google`** (opción posible según `list-patches -o`) o mantener `app.revanced` si microG se configura para servir bajo ese package name (requiere rebuild de microG con ese vendor).

---

## <a id="smarttube"></a>TV: si SmartTube desaparece

### Síntomas
- `yuliskov/SmartTube` repo bloqueado o archivado.
- Fallback: último release funcional que tengamos cached en nuestros propios Releases.

### Alternativas para Android TV

| Opción | Estado 2026 | Notas |
|--------|-------------|-------|
| [**NewPipe**](https://github.com/TeamNewPipe/NewPipe) versión TV | Existe variant "NewPipe (TV)" experimental | Interfaz TV D-pad friendly. Menos features que SmartTube. |
| **FreeTube** | Linux primary, Android port comunitario | Menos maduro que NewPipe en Android. |
| **LibreTube** | Android, usa Piped backend | Requiere servidor Piped activo (también frágil). |
| **Cliente nativo Proyecto B** adaptado a TV | Mayor esfuerzo | Requiere extender Proyecto B con androidx.tv:tv-material. |

### Procedimiento de cambio

1. Editar `apply-patches.sh`: cambiar `ensure_tool` de SmartTube a la fuente alternativa:
   ```bash
   SMARTTUBE_APK="$(ensure_tool "smarttube.apk" "TeamNewPipe/NewPipe" "NewPipe_.*\\.apk$" "")"
   ```
2. Actualizar regex si el naming del asset es distinto.
3. Actualizar `docs/INSTALL-TV.md` con instrucciones específicas de la app alternativa.

---

## <a id="escalacion"></a>Escalación mayor: si YouTube rompe TODO el ecosistema

### Escenarios

- Google añade una validación server-side que ReVanced no puede bypass (ej. hardware attestation estricta para todos los devices).
- YouTube app nueva incompat con todos los patches, durante meses.
- DMCA contra todos los forks de revanced-patches en masa.

### Qué hacer

1. **Corto plazo (~1-2 semanas):** pinear a la versión más vieja de YT/YTM compatible. Si el usuario ya tiene esa versión parcheada funcionando, documentar "no actualizar hasta nueva solución upstream".

2. **Mediano plazo (~1-3 meses):** saltarse a **Proyecto B** — cliente nativo Kotlin con NewPipeExtractor. No depende de parches sobre YouTube oficial. Alexy (el dueño) ya aprobó la existencia de este fallback desde el MEGA_PROMPT original. Ver `project-b/README.md`.

3. **Largo plazo (~meses):** evaluar **Plan D (iOS) con YouTube Premium pagado** si el ecosistema Android queda cerrado. Ver `docs/IOS-FUTURO.md` — incluso en Android, si ReVanced muere definitivamente, el workaround final para el dueño es **$12/mes YouTube Premium** que funciona en todos sus dispositivos sin fricción.

4. **Nunca recomendar:** rootear el teléfono o flashear custom ROM. El dueño explícitamente rechazó estas opciones por riesgo de seguridad (banking apps, garantía, etc.).

---

## Cómo usar este documento en el futuro

Si Claude (u otro agente) retoma el proyecto meses/años después:

1. Lee `docs/CONTINUIDAD.md` primero para contexto general.
2. Si algo falla en el pipeline, identifica el componente afectado (patches, APKs, GmsCore, SmartTube) y consulta la sección correspondiente arriba.
3. Si el escenario no está cubierto aquí, añadirlo como nueva sección tras resolverlo — este doc es vivo.

**Commit de este archivo ante cambios:** mensaje tipo `docs(migration): añadir sección <X>` + SHA de los fixes aplicados en consecuencia.
