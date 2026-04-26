# CONTINUIDAD — archivo maestro para retomar el proyecto

> Si sos un Claude (u otro agente) al que Alexy le acaba de pegar este
> documento diciendo **"Retomo proyecto yt-personal, lee este
> CONTINUIDAD.md y aplica lo pendiente"** — este es tu onboarding en
> 5 minutos.

## 1. Introducción

Este proyecto fue construido por **Alexy Yoj (GitHub: `alexyoj123-tech`,
email `alexyoj123@gmail.com`)** con la ayuda de **Claude Opus 4.7** y
**Claude Code 5x Max** el **20 de abril 2026**.

**Objetivo:** tener YouTube + YouTube Music sin anuncios, con descargas,
PiP, reproducción en background y SponsorBlock, en el **Samsung Galaxy
A04e** (Android 13, arm64-v8a) y eventualmente en un **TV Box Claro 4K**
(Android TV). Auto-actualizado de forma casi invisible. Uso
estrictamente **personal**, repo **privado**, sin distribución pública.

## 2. Estado actual (snapshot 2026-04-20)

### Qué funciona hoy (última migración: 2026-04-23)

- ✅ **Proyecto A — pipeline MORPHE:** tras la migración del 2026-04-23
  (inotia00/revanced-patches fue archivado, ver Bug #10 en
  TROUBLESHOOTING), el pipeline usa el ecosistema **MorpheApp**
  (mismos devs ex-ReVanced, código limpio desde cero):
    - `MorpheApp/morphe-cli` v1.7.0 (drop-in syntax v5-compat)
    - `MorpheApp/morphe-patches` v1.24.0 (formato `.mpp`)
    - `MorpheApp/MicroG-RE` v6.1.3 (reemplazo de GmsCore, 12.8 MB,
      package `app.revanced.android.gms` — mismo vendor que
      ReVanced/GmsCore, se instala como update sin desinstalar el viejo)
  Workflow `project-a-manual.yml` (dispatch manual) y
  `project-a-build.yml` (cron diario 06:00 UTC) producen 4 APKs firmados.
- ✅ **Keystore release** generado (RSA 4096, 100 años, alias
  `yt-personal`). Ubicación local:
  `C:\Users\alexy\yt-personal-secrets\yt-personal-release.jks`.
  Credenciales como GitHub Secrets: `ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- ✅ **Scraper APKMirror** (`project-a/scripts/apkmirror_download.py`,
  Python stdlib-only) maneja la descarga de YT/YTM oficiales.
- ✅ **Íconos oficiales Q4 2024 de YouTube/YT Music** extraídos del APK
  original (apktool local, YT 20.47.62 + YTM 8.47.56) y commiteados en
  `project-a/assets/morphe-icons/`. Se inyectan via `Custom branding`
  patch de Morphe (opción `customIcon`).
- ✅ **Nombres oficiales** "YouTube" y "YouTube Music" vía
  `Custom branding` patch (opción `customName`).
- ✅ **Spoof video streams** patch (nuevo en Morphe, no existía público
  en inotia00) — resuelve el HTTP 400 server-side que bloqueaba YT
  parcheado en abril 2026.
- ✅ **SmartTube** incluido en el Release para TV.

### Versiones pineadas actuales

- **YouTube:** `20.47.62` (de las 4 compat Morphe: 20.21.37/20.31.42/20.45.36/**20.47.62**)
- **YouTube Music:** `8.47.56` (de las 3 compat Morphe: 7.29.52/8.44.54/**8.47.56**)
- **MicroG-RE:** auto-latest (hoy v6.1.3)
- **SmartTube:** auto-latest (hoy 31.57s)
- **Package names post-rename:**
  - YT: `app.morphe.android.youtube`
  - YTM: `app.morphe.android.apps.youtube.music`

### Tag del último release válido

Ver https://github.com/alexyoj123-tech/yt-personal/releases/latest —
después de la migración Morphe, primer release esperado es
**`ytp-a-2026.04.20-v7`** (o el siguiente en línea).

## 3. Plan original vs ajustes

### Plan original (MEGA_PROMPT_v3.2.txt)

Triada de proyectos:
- **A** — pipeline ReVanced (este proyecto A, funcional hoy).
- **B** — cliente nativo Kotlin+Compose con NewPipeExtractor (WIP,
  fallback si ReVanced rompe).
- **C** — ytp-setup (APK único dual phone/TV) — WIP.
- **D** — iOS sideload documentado, NO ejecutado.

### Ajustes tomados durante construcción

1. **`ReVanced/revanced-patches` está HTTP 451.** Cambiamos a
   `inotia00/revanced-patches v5.14.1`. Ver `docs/TROUBLESHOOTING.md`
   Bug #2.
2. **apkeep no soporta APKMirror.** Construimos scraper Python propio.
   Ver `docs/APKMIRROR-SCRAPER.md`.
3. **revanced-cli v6 incompat con inotia00 v5.14.1.** Pineado a
   v5.0.1. Ver Bug #8/TROUBLESHOOTING.
4. **Íconos oficiales:** el APK original usa resource shrinking
   (`res/<hash>.png`). Requiere apktool para extraer nombres reales.
   Hecho local una vez; commit de 40 PNGs en `project-a/assets/icons/`.
5. **Quality settings:** no existen opciones build-time en los
   patches — son settings que el usuario configura post-install en la
   app. Documentado como paso manual en `docs/INSTALL-PHONE.md`.

## 4. Bugs históricos ya resueltos

Ver `docs/TROUBLESHOOTING.md` para el catálogo completo con síntoma
literal + causa raíz + fix + SHA. Resumen de los 10 bugs principales:

| # | Título | Commit SHA |
|---|--------|-----------|
| 1 | apkeep rechaza `APKMirror`/`APKPure` (kebab-case requerido) | `5d62678` |
| 2 | `ReVanced/revanced-patches` HTTP 451 → fork inotia00 | `eb57b66` |
| 3 | `gh` sin `GH_TOKEN` → "asset no encontrado" engañoso | `eb57b66` |
| 4 | jq parse error con `\.` en string literal | `e89eeca` |
| 5 | Regex GmsCore desactualizada al naming 2026 | `d213b86` |
| 6 | Logs a stdout polucionan command substitution | `4617918` |
| 7 | revanced-cli v6 exige `--bypass-verification` en patch | `261b2fe` |
| 7b | revanced-cli v6 removió `list-patches --json` | `261b2fe` |
| 8 | CLI v6 incompat binaria con patches inotia00 v5.x (MutableMethod) → pineado v5.0.1 | `7aabc3e` |
| 9 | Sintaxis -O estilo v6 rechazada por CLI v5 | `d88c815` |
| 10 | **inotia00 archivado + YT 19.44.39 HTTP 400 → migración completa a Morphe** | (commit v7) |

## 5. Cómo retomo el proyecto

### Si todo está funcionando hoy

1. Verifica la última release con `gh release list --repo alexyoj123-tech/yt-personal --limit 3`.
2. Los APKs se auto-renuevan cada día si hay cambios upstream. No hay
   que hacer nada activamente.

### Si algo falla

1. **Primer paso siempre:** leé `docs/TROUBLESHOOTING.md` buscando el
   síntoma que vés. 8 bugs ya documentados con fix + SHA.
2. **Si el síntoma no está ahí:** identificá qué componente falló
   (patches? APKMirror? GmsCore? SmartTube?) y consultá
   `docs/MIGRATION-GUIDE.md` §correspondiente. Tiene planes B para
   cada upstream.
3. **Si nada te da luz:** pegale este archivo + el log del error al
   Claude más capaz que tengas (Opus 4.7+ recomendado) y decí:
   > "Retomo proyecto yt-personal, lee este CONTINUIDAD.md y
   > docs/MIGRATION-GUIDE.md, aquí está el error actual: [pegar log].
   > Propón diagnóstico + fix. Espera mi luz verde antes de commit."

### Datos prácticos del setup

- **Repo privado:** https://github.com/alexyoj123-tech/yt-personal
- **Workflow dispatch manual (para forzar un rebuild):**
  ```bash
  gh workflow run project-a-manual.yml --repo alexyoj123-tech/yt-personal --ref main \
    -f youtube_version=19.44.39 \
    -f ytmusic_version=7.25.53 \
    -f publish_release=true \
    -f force_release=true \
    -f revanced_cli_tag=v5.0.1
  ```
- **Monitor del run:**
  ```bash
  gh run watch --repo alexyoj123-tech/yt-personal
  ```
- **Keystore local + backup:** `C:\Users\alexy\yt-personal-secrets\`.
  **Backup en Bitwarden recomendado** — si perdés el keystore, todas
  las apps instaladas dejan de actualizarse (Android verifica firma).

### Ubicación de archivos críticos

- Pipeline scripts: `project-a/scripts/*.sh`, `project-a/scripts/apkmirror_download.py`
- Composite action: `.github/actions/revanced-build/action.yml`
- Workflows: `.github/workflows/project-a-*.yml`
- Íconos oficiales extraídos: `project-a/assets/icons/{youtube,ytmusic}/`
- Docs: `docs/` (TROUBLESHOOTING, APKMIRROR-SCRAPER, MIGRATION-GUIDE,
  KEYSTORE, IOS-FUTURO, INSTALL-TV, HOW-IT-WORKS).

## 6. Limitaciones conocidas y aceptadas

Alexy aceptó explícitamente estas limitaciones:

- **Premium Enhanced Bitrate** (audio lossless > 256 kbps) requiere
  cuenta Premium verificada server-side. No bypasseable por patches.
  Máximo audible post-parche: 256 kbps OPUS/AAC.
- **Retraso de 2-8 semanas ocasional** cuando YouTube hace un cambio
  mayor server-side. La comunidad ReVanced típicamente publica fix en
  ese lapso; mientras tanto el pipeline se atrasa automáticamente.
- **iOS NO implementado.** Documentado plan en `docs/IOS-FUTURO.md`.
  Cuando Alexy compre iPhone, el fallback recomendado es **$12/mes
  YouTube Premium** por simplicidad.
- **Gray-area legal:** íconos extraídos del APK oficial de Google.
  OK para uso personal en repo privado; no distribuir.

## 7. Sistema anti-frágil (completado 2026-04-27)

El proyecto tiene **detección automática de problemas en upstreams** vía
`.github/workflows/health-monitor.yml` (cron lunes 06:00 UTC). Audita
los 5 upstreams (`MorpheApp/morphe-patches`, `MorpheApp/morphe-cli`,
`MorpheApp/MicroG-RE`, `yuliskov/SmartTube`,
`gitlab.com/energylove/originproject`) y crea **issues automáticos con
label `health`** si alguno está archivado o supera su threshold (90 días
para upstreams diarios, 180 días para bimensuales).

### Si esto se rompe en el futuro — orden de checks

1. **Primer paso siempre:** revisar issues abiertos con label `health` en
   <https://github.com/alexyoj123-tech/yt-personal/issues?q=label%3Ahealth+is%3Aopen>.
   Cada issue dice exactamente qué upstream falló y enlaza al
   procedimiento de fallback.
2. Si no hay issues pero algo no anda: trigger manual del workflow para
   forzar un check fresco:
   ```bash
   gh workflow run health-check-manual.yml --repo alexyoj123-tech/yt-personal \
     -f dry_run=false
   ```
3. Si querés probar el monitor sin disparar issues:
   ```bash
   gh workflow run health-check-manual.yml --repo alexyoj123-tech/yt-personal \
     -f dry_run=true
   ```
4. Si querés override del threshold (ej. ver qué pasaría con 30d):
   ```bash
   gh workflow run health-check-manual.yml --repo alexyoj123-tech/yt-personal \
     -f dry_run=true -f threshold_override=30
   ```
5. Procedimiento de migración por upstream: ver
   [`docs/ANTI-FRAGIL.md` §3](ANTI-FRAGIL.md#3-procedimientos-por-upstream).

### Caso histórico documentado

`docs/ANTI-FRAGIL.md` §4 contiene el postmortem completo de la migración
inotia00 → Morphe (2026-04-23): síntomas, investigación, cambios
aplicados, lecciones aprendidas. Es el prototipo de cómo se ejecuta una
migración mayor en este proyecto.

## 8. Reconocimientos

Este proyecto es posible gracias a:

- **ReVanced Team** — infraestructura de patches + CLI + GmsCore.
- **inotia00** — fork activo de revanced-patches (v5.14.1) tras el
  takedown del oficial en 2025.
- **Yuri Liskov (yuliskov)** — SmartTube, cliente Android TV.
- **microG / marcan** — base de GmsCore, re-implementación libre de
  Google Play Services.
- **iBotPeaches (Apktool)** — herramienta de decodificación que hizo
  posible extraer los íconos oficiales.
- **Anthropic + Claude** — el motor de razonamiento que hizo factible
  debuggear 11+ runs e integrar 7+ componentes upstream.

Sin estas comunidades, este proyecto no existiría. Si alguna vez
reutilizas partes de este trabajo en otro proyecto, considera
contribuir a cualquiera de ellas.
