# HOW IT WORKS — arquitectura del proyecto yt-personal

## Diagrama del flujo completo

```
                ┌──────────────────────────────────────────────────────────┐
                │ CRONS de GitHub Actions                                  │
                │                                                          │
                │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │
                │  │ project-a    │  │ project-d    │  │ health-      │    │
                │  │ daily 06:00  │  │ weekly Sun   │  │ monitor      │    │
                │  │ UTC          │  │ 04:00 UTC    │  │ Mon 06:00    │    │
                │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘    │
                └─────────┼─────────────────┼─────────────────┼────────────┘
                          │                 │                 │
                          ▼                 ▼                 ▼
       ┌──────────────────────────┐  ┌──────────────┐  ┌──────────────────────┐
       │ Project A pipeline       │  │ Project D    │  │ Audita 5 upstreams   │
       │                          │  │ pipeline     │  │ vía GitHub/GitLab API│
       │ 1. apkmirror_download.py │  │              │  │                      │
       │    → YT, YTM oficiales   │  │ 1. fetch     │  │ Si stale/archived:   │
       │ 2. fetch Morphe stack    │  │    GitLab    │  │   abre issue [HEALTH]│
       │ 3. apply patches         │  │    .rar      │  │   con label `health` │
       │ 4. sign con yt-personal  │  │ 2. unrar     │  │   + comando fallback │
       │ 5. crear release         │  │ 3. release   │  │                      │
       │    `ytp-a-YYYY.MM.DD`    │  │    `ytp-d-   │  │ Sin spam:            │
       │                          │  │    origin-X` │  │ dedup por título     │
       └──────────┬───────────────┘  └──────┬───────┘  └──────────────────────┘
                  │                         │
                  ▼                         ▼
       ┌──────────────────────────────────────────────────────────────────────┐
       │ GitHub Releases del repo                                             │
       │                                                                      │
       │  ytp-a-2026.04.23/  ─────► youtube-personal-20.47.62.apk (172 MB)    │
       │                            youtube-music-personal-8.47.56.apk (68MB) │
       │                            gmscore-6.1.3.apk (13 MB)                 │
       │                            smarttube-31.57s.apk (24 MB)              │
       │                                                                      │
       │  ytp-d-origin-1.4.6/ ────► youtube-origin-1.4.6.apk (199 MB)         │
       └──────────────────────────────────┬───────────────────────────────────┘
                                          │
                                          │ (manifests apuntan acá vía
                                          │  apkFilterRegEx)
                                          ▼
       ┌──────────────────────────────────────────────────────────────────────┐
       │ GitHub Pages (auto-deploy en push a docs/)                           │
       │                                                                      │
       │  https://alexyoj123-tech.github.io/yt-personal/                      │
       │    ├── index.html             ← landing con 2 botones (📱 / 📺)      │
       │    ├── obtainium-phone.json   ← config 3 apps celular                │
       │    └── obtainium-tv.json      ← config 2 apps TV                     │
       └──────────────────────────────────┬───────────────────────────────────┘
                                          │
                                          │ (URL del JSON)
                                          ▼
       ┌──────────────────────────────────────────────────────────────────────┐
       │ Obtainium en el dispositivo del usuario (familia/amigo)              │
       │                                                                      │
       │  Importa el JSON → conoce los 3 (o 2) apps configuradas              │
       │  Cada app: nombre, package, URL del repo, regex APK, filtro título   │
       │  Background: chequea releases del repo cada 24h                      │
       │  Si hay versión nueva: notifica + ofrece instalar (1 tap usuario)    │
       └──────────────────────────────────────────────────────────────────────┘
```

## Componentes en detalle

### Project A — pipeline Morphe (celular)

**Archivos:** `project-a/scripts/{fetch-apks,apply-patches,sign-apks,create-release,common,apkmirror_download}.{sh,py}`, `.github/workflows/project-a-{build,manual}.yml`, `.github/actions/revanced-build/action.yml`.

**Trigger:** cron diario 06:00 UTC + workflow_dispatch manual.

**Flujo:**
1. **fetch-apks.sh** invoca `apkmirror_download.py` (Python stdlib only, scraper a APKMirror) para bajar las versiones específicas de YouTube y YT Music que Morphe v1.24.0 soporta. El scraper tiene priority explícita arm64-v8a + nodpi (Bug #12 lección).
2. **apply-patches.sh** descarga `morphe-cli`, `morphe-patches.mpp` y `MicroG-RE.apk` desde sus repos GitHub. Aplica los patches a YT y YTM con flags `-e PatchName -O key=value` (sintaxis cli v5, Bug #9 lección). Los patches críticos: `Spoof video streams` (resuelve HTTP 400), `Custom branding` (icon + name), `Change package name` (rename a `app.morphe.android.*`).
3. **Verificación defensiva post-patch** (Bug #8 lección): `aapt2 dump badging` confirma que el package name realmente cambió. Si no cambió, `die`. Esto previene "falsa victoria" de runs donde el cli no carga patches pero exit 0.
4. **sign-apks.sh** firma los 3 APKs producidos (YT, YTM, MicroG-RE) con keystore `yt-personal`. SmartTube se descarga de yuliskov, también se firma con nuestro keystore para consistencia.
5. **create-release.sh** crea `ytp-a-YYYY.MM.DD` con los 4 APKs. Body incluye versiones específicas que Obtainium parsea via `versionExtractionRegEx`.

### Project D — pipeline YouTube Origin (TV)

**Archivos:** `project-d/scripts/fetch-origin.sh`, `.github/workflows/project-d-weekly.yml`.

**Trigger:** cron domingo 04:00 UTC + workflow_dispatch manual.

**Flujo:**
1. Lista `Releases/` del repo GitLab `energylove/originproject` via API.
2. Encuentra el último directorio con archivos `.rar` (skip placeholders vacíos).
3. Descarga el `.rar` arm64-v8a, valida magic bytes RAR5.
4. Extrae con `unrar` (instalado al vuelo en el runner Ubuntu via `apt`).
5. Renombra a `youtube-origin-<version>.apk` y publica como `ytp-d-origin-<version>` si no existe ya.

**Diferencia crítica vs Project A:** **NO se re-firma el APK.** Mantiene la firma original de energylove para preservar Widevine DRM HD/4K. Documentado en `docs/MIGRATION-GUIDE-TV.md §firma`.

### Health monitor — sistema anti-frágil

**Archivos:** `.github/scripts/health-monitor.sh`, `.github/workflows/health-{monitor,check-manual}.yml`.

**Trigger:** cron lunes 06:00 UTC + workflow_dispatch (con inputs `dry_run`, `threshold_override`).

**Flujo:**
1. Itera por una lista de 5 upstreams definidos en `UPSTREAMS=(name|type|ref|threshold|anchor)`.
2. Para cada uno: consulta GitHub API o GitLab API → `pushed_at`/`last_activity_at` + `archived`.
3. Si `archived=true` o `days_since_push > threshold`: prepara issue con título `[HEALTH] <name> <ALERT> (<N> días)`.
4. Antes de crear: dedup contra issues abiertos con label `health` + mismo upstream en title.
5. Crea issue con body que incluye link upstream, fecha último push, comando de switch hacia fallback (referencia `docs/ANTI-FRAGIL.md` anchor).
6. Sale con exit 1 si hay alertas en non-dry mode → workflow rojo en Actions UI = visibilidad pasiva sin email spam.

**Thresholds asimétricos:** 90 días para upstreams diarios (Morphe-{patches,cli}, MicroG-RE), 180 días para bimensuales (SmartTube, Origin energylove). Evita falsos positivos.

### GitHub Pages — distribución pública

**Archivos:** `docs/index.html`, `docs/obtainium-{phone,tv}.json`, `.github/workflows/pages-deploy.yml`.

**Trigger:** push a `main` con cambios en `docs/**` o el workflow mismo + workflow_dispatch.

**Flujo:**
1. `actions/configure-pages@v5` setup.
2. `actions/upload-pages-artifact@v3` empaqueta `docs/` como artifact.
3. `actions/deploy-pages@v4` despliega al ambiente `github-pages`.
4. URL final: `https://alexyoj123-tech.github.io/yt-personal/`.

El usuario familia/amigo abre la landing en su Android. Tap "Importar a Obtainium" copia la URL del JSON (`obtainium-phone.json` o `tv.json`) al portapapeles. Pega en Obtainium → Import → URL → ve las 3-2 apps preconfiguradas → tap Add + Install. Auto-update perpetuo desde ahí.

## Cómo se conectan entre sí

- **Project A produce releases `ytp-a-*`.** El `obtainium-phone.json` apunta al repo `alexyoj123-tech/yt-personal` con `filterReleaseTitlesByRegEx: "Daily ReVanced Build"` → Obtainium solo considera releases del Project A.
- **Project D produce releases `ytp-d-origin-*`.** El `obtainium-tv.json` filtra por `"YouTube Origin"` para Origin y por `"Daily ReVanced Build"` para SmartTube (que vive dentro de Project A releases).
- **Health monitor NO modifica los pipelines** — solo abre issues. La acción correctiva sigue siendo manual (decisión del mantenedor sobre cuándo migrar).
- **Pages NO depende de los releases** — solo sirve los JSON que apuntan a esos releases. Si un release falla, Pages sigue funcionando; Obtainium en el dispositivo del usuario simplemente no encuentra una versión nueva.

## Filosofía de diseño

1. **Idempotencia:** todos los workflows pueden re-correr sin efectos secundarios. Las releases tienen tags únicos por fecha/versión.
2. **Defaults en UN solo lugar** (Bug #11 lección): el composite action declara los defaults. Bash usa `${VAR:?required}` o `die` explícito.
3. **Verificación defensiva post-step** (Bug #8 lección): cada output crítico se valida con tooling externo (aapt2 para APKs, jq para JSONs, magic bytes para archivos binarios).
4. **Fail loud, not silent:** exit 1 + mensaje claro mejor que exit 0 silencioso con resultado inválido.
5. **Documentar el "por qué"** (no solo el "qué"): `docs/TROUBLESHOOTING.md` y `docs/CONTINUIDAD.md §10` capturan las decisiones para mantenedores futuros.
