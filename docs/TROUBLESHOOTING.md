# Troubleshooting

Runbook vivo del proyecto. Cada bug resuelto queda documentado con:
síntoma exacto (literal del log) → causa raíz → fix → commit SHA →
lección general para que no se repita (o sepamos diagnosticarlo rápido).

Si encuentras un bug nuevo, añádelo al final en la sección
[Nuevos bugs](#nuevos-bugs).

---

## Índice

- [Proyecto A — Pipeline ReVanced (A1 – A4)](#proyecto-a)
  - [Bug #1 — apkeep rechaza source `APKMirror` / `APKPure`](#bug-1)
  - [Bug #2 — `ReVanced/revanced-patches` bloqueado HTTP 451](#bug-2)
  - [Bug #3 — `gh` sin `GH_TOKEN` → "asset no encontrado" engañoso](#bug-3)
  - [Bug #4 — jq parse error con regex `\.` en string literal](#bug-4)
  - [Bug #5 — Regex GmsCore desactualizada al naming 2026](#bug-5)
  - [Bug #6 — Logs a stdout polucionan command substitution](#bug-6)
  - [Bug #7 — revanced-cli v6 exige `--bypass-verification` en `patch`](#bug-7)
  - [Bug #7b — revanced-cli v6 removió `list-patches --json`](#bug-7b)
  - [Bug #10 — inotia00 archivado + YT 19.44.39 recibe HTTP 400 → migración a Morphe](#bug-10)
  - [Bug #11 — Composite action YAML defaults ganan sobre bash `${VAR:-...}`](#bug-11)
- [Nuevos bugs](#nuevos-bugs)

---

## Proyecto A

### <a id="bug-1"></a>Bug #1 — apkeep rechaza source `APKMirror` / `APKPure`

**Run afectado:** #1 · **Resuelto en:** [`5d62678`](https://github.com/alexyoj123-tech/yt-personal/commit/5d62678) · **Archivo tocado:** `project-a/scripts/fetch-apks.sh`, `.github/actions/revanced-build/action.yml`

**Síntoma (literal del log):**
```
error: invalid value 'APKMirror' for '--download-source <download_source>'
  [possible values: apk-pure, google-play, f-droid, huawei-app-gallery]
[WARN]  apkeep con fuente APKMirror falló para com.google.android.youtube — intento fallback APKPure.
error: invalid value 'APKPure' for '--download-source <download_source>'
  [possible values: apk-pure, google-play, f-droid, huawei-app-gallery]
[ERR]   Ambas fuentes fallaron para com.google.android.youtube
```

**Causa raíz:** `apkeep` (EFForg) acepta solo sources en kebab-case (`apk-pure`, `google-play`, `f-droid`, `huawei-app-gallery`). No existe un source oficial `APKMirror` — la comunidad usa scrapers custom para APKMirror. Mis defaults (`APKMirror` primary, `APKPure` fallback) eran inventados.

**Fix:** default a `apk-pure` (único viable para YT/YTM sin credenciales). APKPure a veces sirve `.xapk` / `.apkm` (splits empaquetados en ZIP) → añadí extracción del base APK (el `.apk` más grande dentro del bundle). Retry automático sin versión si el spec con versión específica no está en apk-pure.

**Lección:** antes de hardcodear nombres de sources/opciones de una CLI externa, verificar con `<cli> --help` los valores exactos. En documentación general aparecen nombres "marketing"; en los flags CLI suelen ser kebab-case o más estrictos.

---

### <a id="bug-2"></a>Bug #2 — `ReVanced/revanced-patches` bloqueado HTTP 451

**Run afectado:** #2 · **Resuelto en:** [`eb57b66`](https://github.com/alexyoj123-tech/yt-personal/commit/eb57b66) · **Archivo tocado:** `project-a/scripts/fetch-apks.sh`, `project-a/scripts/apply-patches.sh`, `.github/actions/revanced-build/action.yml`

**Síntoma (literal):**
```
HTTP 451 Unavailable For Legal Reasons
https://api.github.com/repos/ReVanced/revanced-patches
```

y en el CI:
```
[ERR]   No encontré asset 'revanced-cli-.*-all\.jar$' en ReVanced/revanced-cli
```
(engañoso — ver también Bug #3 que se presenta junto con éste.)

**Causa raíz:** GitHub bloqueó el repo `ReVanced/revanced-patches` con HTTP 451 desde 2025 (acción legal, presumiblemente Google DMCA). `ReVanced/revanced-cli` y `ReVanced/GmsCore` siguen accesibles. La comunidad se movió a forks; los activos verificados en 2026-04:

| Fork | Tag | Formato | Estado |
|------|-----|---------|--------|
| `inotia00/revanced-patches` | `v5.14.1` | `.rvp` (36 MB) | ✅ mantenido, compat con cli v5+ |
| `anddea/revanced-patches` | `v4.0.0` | `.mpp` (formato viejo) | ⚠ solo si inotia00 cae |

**Fix:** parametrizar los 3 repos vía env:
- `REVANCED_CLI_REPO` (default `ReVanced/revanced-cli`)
- `REVANCED_PATCHES_REPO` (default `inotia00/revanced-patches` ← aquí el switch)
- `REVANCED_GMSCORE_REPO` (default `ReVanced/GmsCore`)

y exponer estos 3 como inputs del composite action `revanced-build`. Override en el input del workflow-dispatch si alguno cae.

**Lección:** ante HTTP 451 de un repo upstream, asumir que **no vuelve** y que la comunidad forkeó. Parametrizar el path desde el día 1 si el proyecto depende de un repo controversial.

---

### <a id="bug-3"></a>Bug #3 — `gh` sin `GH_TOKEN` → "asset no encontrado" engañoso

**Run afectado:** #2 (manifestado junto con Bug #2) · **Resuelto en:** [`eb57b66`](https://github.com/alexyoj123-tech/yt-personal/commit/eb57b66) · **Archivo tocado:** `.github/actions/revanced-build/action.yml`, `project-a/scripts/common.sh`

**Síntoma (literal):**
```
gh: To use GitHub CLI in a GitHub Actions workflow, set the GH_TOKEN environment variable. Example:
  env:
    GH_TOKEN: ${{ github.token }}
[ERR]   No encontré asset 'revanced-cli-.*-all\.jar$' en ReVanced/revanced-cli
```

**Causa raíz:** `fetch-apks.sh` empezó a llamar a `gh` internamente (para bootstrap de patches-meta.json). El step `fetch-apks` en `action.yml` no le pasaba `GH_TOKEN`. `gh` imprimía el aviso a **stderr** y devolvía stdout vacío; mi `ensure_tool()` interpretaba "stdout vacío → no encontré asset", mensaje engañoso.

**Fix:**
1. Añadir `GH_TOKEN: ${{ inputs.gh_token }}` al step `fetch-apks` en action.yml.
2. En `common.sh`, mejorar `_gh_assets_by_regex` y `gh_latest_tag` para **capturar stderr** y discriminar causa: `GH_TOKEN` ausente → mensaje específico; `HTTP 451` → "repo bloqueado, usa fork"; not-found → "no existe". Ya no se pierde la causa raíz tras "asset no encontrado".

**Lección:** cualquier wrapper de `gh` en CI debe:
- pasar explícitamente `GH_TOKEN` al step,
- capturar stderr y distinguir error transitorio vs asset-no-match.

Sin esto, el mensaje que ves es el *síntoma del síntoma*.

---

### <a id="bug-4"></a>Bug #4 — jq parse error con regex `\.` en string literal

**Run afectado:** #3 · **Resuelto en:** [`e89eeca`](https://github.com/alexyoj123-tech/yt-personal/commit/e89eeca) · **Archivo tocado:** `project-a/scripts/common.sh`

**Síntoma (literal):**
```
[ERR]   gh release view falló (rc=0) para ReVanced/revanced-cli: failed to parse jq expression (line 1, column 53)
[ERR]   No encontré asset 'revanced-cli-.*-all\.jar$' en ReVanced/revanced-cli (gh devolvió vacío sin error).
```

**Causa raíz:** mi regex literal era `revanced-cli-.*-all\\.jar$`. Trace:
- bash expande double-quote: `revanced-cli-.*-all\.jar$`
- jq recibe dentro de un string: `"revanced-cli-.*-all\.jar$"`
- jq string-literal solo acepta escapes `\\ \" \n \t \/` — **`\.` es inválido** → parse error

Aparte: el cli devolvió rc=0 pero stderr con el error, y mi código tomó stdout vacío como "asset no match".

**Fix:** separar la consulta `gh` del filtrado `jq`:
```bash
# antes (interpolación dentro del jq expression)
gh release view --repo "$repo" --json assets --jq \
  ".assets[] | select(.name | test(\"$regex\")) | .url"

# después (jq --arg pasa el regex como valor, no como source)
gh release view --repo "$repo" --json assets > "$tmp_out"
jq -r --arg re "$regex" --arg fld "$field" \
   '.assets[] | select(.name | test($re)) | .[$fld]' < "$tmp_out"
```

Con `--arg`, jq recibe el regex como valor JSON string ya escapado, y `test()` lo usa crudo como regex sin re-parsear.

**Lección:** nunca interpolar strings de usuario dentro del source de un jq expression. Siempre `jq --arg name value '... $name ...'`. Esto aplica análogo a SQL (prepared statements vs string concat), shell (array de args vs `$*`), y cualquier lenguaje con parsing.

---

### <a id="bug-5"></a>Bug #5 — Regex GmsCore desactualizada al naming 2026

**Run afectado:** #4 · **Resuelto en:** [`d213b86`](https://github.com/alexyoj123-tech/yt-personal/commit/d213b86) · **Archivo tocado:** `project-a/scripts/apply-patches.sh`, `.github/actions/revanced-build/action.yml`

**Síntoma (literal):**
```
[ERR]   No encontré asset 'app-release-.*\.apk$|GmsCore-.*\.apk$' en ReVanced/GmsCore (gh devolvió vacío sin error).
```

**Causa raíz:** mi regex `app-release-.*\.apk$|GmsCore-.*\.apk$` asumía nombres viejos. Naming real en `ReVanced/GmsCore` v0.3.13.2.250932 (abril 2026):
```
app.revanced.android.gms-250932004-hw-signed.apk   (variante Huawei)
app.revanced.android.gms-250932004-signed.apk     (variante Google)
```
Ninguno matcheaba.

**Fix:** regex nueva `app\.revanced\.android\.gms-[0-9]+-signed\.apk$` — matchea el asset Google normal y **excluye** `-hw-signed.apk` (no queremos Huawei en Samsung A04e con Google Services). Además, parametrizado vía env `REVANCED_GMSCORE_REGEX` para poder overridear sin tocar código si upstream renombra de nuevo.

**Lección:** para cualquier asset externo matcheado por regex, parametrizar la regex vía env desde el día 1. El naming upstream cambia con versionado mayor sin aviso.

---

### <a id="bug-6"></a>Bug #6 — Logs a stdout polucionan command substitution

**Run afectado:** #5 · **Resuelto en:** [`4617918`](https://github.com/alexyoj123-tech/yt-personal/commit/4617918) · **Archivo tocado:** `project-a/scripts/common.sh`

**Síntoma (literal):**
```
Error: Unable to access jarfile [INFO]  Ya existe revanced-cli.jar en /home/runner/work/***/***/project-a/build/tools/revanced-cli.jar — reuso.
```

**Causa raíz:** `log()`, `info()`, `ok()`, `step()` en `common.sh` usaban `printf` a **stdout**. Funciones llamadas vía `CLI_JAR="$(ensure_tool ...)"` capturaban TODO stdout — incluyendo los mensajes de log. El `$CLI_JAR` quedaba con `[INFO]  Ya existe ... — reuso.\n/path/real.jar`. Luego `java -jar "$CLI_JAR"` recibía la cadena completa como nombre del JAR → "Unable to access jarfile".

**Fix:** redirigir las 4 funciones a **stderr** (`>&2`). `warn()`, `err()`, `die()` ya iban a stderr. Con esto, stdout queda reservado exclusivamente para valores "de retorno" de funciones pensadas para `$(...)`.

```bash
# antes
info()  { printf "%s[INFO]%s  %s\n"  "$C_BLUE" "$C_RESET" "$*"; }
# después
info()  { printf "%s[INFO]%s  %s\n"  "$C_BLUE" "$C_RESET" "$*" >&2; }
```

**Lección:** en bash, cualquier función que pueda ser invocada vía `$(...)` debe emitir **solo** el valor de retorno a stdout. Todo lo demás (logs, progreso, diagnóstico) a stderr. Esta es una **convención fundacional**; romperla causa bugs mudos y misleading downstream.

---

### <a id="bug-7"></a>Bug #7 — revanced-cli v6 exige `--bypass-verification` en `patch`

**Run afectado:** #6 · **Resuelto en:** [`261b2fe`](https://github.com/alexyoj123-tech/yt-personal/commit/261b2fe) · **Archivo tocado:** `project-a/scripts/apply-patches.sh`

**Síntoma (literal):**
```
═══ Parcheando YouTube → youtube-patched.apk ═══
Error: Missing required argument(s): (-b | [-s=<signatureFile> -k=<publicKeyRingFile> -a=<attestationFile> ([-r=<repository>])])
Usage: revanced-cli patch [-f] [--exclusive] [--purge]
                          (-p=<patchesFile> (-b | [-s=<signatureFile>
                          -k=<publicKeyRingFile> -a=<attestationFile>
                          ([-r=<repository>])]))...
##[error]Process completed with exit code 2.
```

**Causa raíz:** revanced-cli v6.0.0 introdujo **verificación PGP obligatoria** en el subcomando `patch`. El grupo requerido: o `-b` / `--bypass-verification`, o el trío `-s -k -a [-r]` con el signature bundle PGP oficial. Como usamos el fork `inotia00/revanced-patches` (no firmado con las llaves PGP de ReVanced oficial), necesitamos bypass. Exit code 2 es típico de picocli cuando el argparsing falla (vs exit 1 de errores de runtime).

**Fix (1 flag):**
```bash
java -jar "$CLI_JAR" patch \
  --patches "$PATCHES_RVP" \
  --bypass-verification \     # ← añadido
  --out "$out_apk" \
  --purge \
  "$input_apk"
```

**Lección:** ante un exit code 2 de un CLI escrito con picocli/commons-cli/clap, leer el `Usage:` del output como el patrón formal de args requeridos. Las parentesis y barras verticales son la gramática real; los `[]` son opcionales y `()` son grupos obligatorios con disyunción `|`.

---

### <a id="bug-7b"></a>Bug #7b — revanced-cli v6 removió `list-patches --json`

**Run afectado:** #6 (resuelto junto con Bug #7) · **Resuelto en:** [`261b2fe`](https://github.com/alexyoj123-tech/yt-personal/commit/261b2fe) · **Archivo tocado:** `project-a/scripts/fetch-apks.sh`

**Síntoma (literal):**
```
[INFO]  Extrayendo metadata de patches (compatible_packages + versions)
Unmatched argument at index 1: '/home/runner/work/***/***/project-a/build/tools/revanced-patches.rvp'
Usage: revanced-cli list-patches [--descriptions] [--index] [--options]
```

**Causa raíz:** v6 removió los flags `--with-packages --with-versions --json` de `list-patches` y dejó de aceptar la ruta del RVP como arg posicional. Ahora sólo acepta `--descriptions --index --options`. No hay vía directa desde el cli para extraer la metadata de compatibilidad (qué versiones de YT/YTM tolera cada patch).

**Fix:** reemplazar la llamada al cli por **extracción directa del `.rvp`**, que es un ZIP. El script prueba 6 rutas candidatas conocidas (`patches.json`, `META-INF/patches.json`, etc.), valida con `jq empty` que sea JSON real, y si ninguna matchea loggea el listado de JSONs internos del .rvp para que el siguiente mantenedor sepa qué path añadir. Fallback: `[]` vacío → fetch-apks usa `latest` (apk-pure sirve algo compat en la práctica).

**Lección:** tools de upstream remueven features entre major versions. Si el dato está *dentro* de un artifact (como un `.rvp` que es ZIP), extraerlo directo es más estable que depender de un subcomando CLI que puede cambiar.

---

### <a id="bug-8"></a>Bug #8 — revanced-cli v6 incompat binaria con patches inotia00 v5.x (`MutableMethod`)

**Run afectado:** #6 · **Resuelto en:** [`7aabc3e`](https://github.com/alexyoj123-tech/yt-personal/commit/7aabc3e) · **Archivos tocados:** `.github/actions/revanced-build/action.yml`, `project-a/scripts/apply-patches.sh`

**Síntoma (literal del log):**
```
SEVERE: Failed to load patches from .../revanced-patches.rvp:
java.lang.NoClassDefFoundError: app/revanced/patcher/util/proxy/mutableTypes/MutableMethod
  at app.revanced.patcher.patch.PatchKt.getPatches$getPatchMethods(Patch.kt:293)
  ...
INFO: Decoding manifest         ← cli sigue como si nada
INFO: Compiling patched dex
INFO: Saved to youtube-patched.apk    ← exit 0, pero APK SIN parches aplicados
[OK] YouTube parcheado: 178M
```

**Causa raíz:** revanced-cli v6.0.0 actualizó internamente `revanced-patcher` a v2+ que **removió la clase `MutableMethod`** que TODOS los patches v5.x (incluido inotia00 v5.14.1) referencian. El cli loguea `SEVERE: Failed to load patches`, descarta el bundle entero (0 patches cargados), pero **continúa el flujo como si todo estuviera bien**: decompila, recompila el APK original, lo firma, y sale con exit 0. APK final = original sin modificar (verificable con `aapt2 dump badging`: package sigue siendo `com.google.android.youtube`, no `app.rvx.android.youtube`).

Run #7 fue una "falsa victoria" — released APKs que parecían exitosos pero NO tenían ningún patch aplicado.

**Fix:** pinear cli a v5.0.1 (la última que carga `.rvp` legacy con la API v1 que usa MutableMethod).
```diff
  revanced_cli_tag:
-   default: 'latest'
+   default: 'v5.0.1'
```

Y añadir verificación defensiva post-patch en `apply-patches.sh` para que NUNCA volvamos a publicar un APK no-parcheado:
```bash
# Verificar que el package name efectivamente cambió tras el patch
EXPECTED_YT_PACKAGE="app.rvx.android.youtube"
ACTUAL=$(aapt2 dump badging "$out_apk" | awk -F"'" '/^package/ {print $2}')
if [ "$ACTUAL" != "$EXPECTED_YT_PACKAGE" ]; then
  die "Patches NO se aplicaron — package=$ACTUAL esperado=$EXPECTED_YT_PACKAGE"
fi
```

**Lección:** **NUNCA confiar solo en exit code 0 de un CLI** que hace pipeline complejo. El cli puede no fallar pero entregar resultado inválido (silent fail). Verificar el output esperado con tooling externo (`aapt2 dump badging` para APKs). Esta lección originó el patrón de verificación defensiva post-cada-step que ahora todos los scripts del repo aplican.

---

### <a id="bug-9"></a>Bug #9 — cli v5.0.1 rechaza sintaxis `-O "PatchName:key=value"` de v6

**Run afectado:** rebuild post-#8 · **Resuelto en:** [`d88c815`](https://github.com/alexyoj123-tech/yt-personal/commit/d88c815) · **Archivos tocados:** `project-a/scripts/apply-patches.sh`

**Síntoma (literal):**
```
Unknown option: -O "Custom branding icon for YouTube:appIcon=youtube"
Usage: revanced-cli patch [options] <apk>
##[error]Process completed with exit code 2.
```

**Causa raíz:** cli v6 introdujo la sintaxis combinada `-O "PatchName:key=value"` (especificar patch + opción en un solo flag). cli v5.0.1 NO la soporta — usa el modelo viejo de `-e PatchName` para enable + `-O key=value` aplicado al último patch enabled. Tras pinear a v5 (Bug #8), las opciones tipo v6 que escribí en `apply-patches.sh` quedaron rechazadas como flags inválidos.

**Fix:** reescribir invocación de patch al modelo v5:
```diff
- java -jar cli.jar patch \
-   -O "Custom branding icon for YouTube:appIcon=youtube" \
-   -O "Custom branding name for YouTube:appName=YouTube" \
-   ...
+ java -jar cli.jar patch \
+   -e "Custom branding icon for YouTube" -O "appIcon=youtube" \
+   -e "Custom branding name for YouTube" -O "appName=YouTube" \
+   ...
```

Cada `-O key=value` se asocia al `-e PatchName` que lo precede inmediatamente. El orden importa.

**Lección:** APIs de CLI cambian entre majors igual que APIs de librería. La sintaxis `-O "Patch:key=val"` parecía "obvia y mejor", pero v5 simplemente no la implementa. Cuando se hace pin de versión por compat (Bug #8), revisar que TODAS las invocaciones del CLI usen la sintaxis válida para esa versión, no la moderna.

---

## Patrones que vimos repetidos

Metadata de los 12 bugs, para referencia:

| # | Clase | Pattern |
|---|-------|---------|
| 1 | Integration | CLI externa con enum estricto de valores |
| 2 | Upstream | Repo upstream disponible → bloqueado por legal |
| 3 | Tooling | `gh` en CI necesita `GH_TOKEN` explícito |
| 4 | Language | String escapes diferentes en bash vs jq vs regex |
| 5 | Integration | Asset naming upstream cambia sin aviso |
| 6 | Bash | stdout = retorno; stderr = logging (convención no opcional) |
| 7 | Integration | Major version bump añade args requeridos |
| 7b | Integration | Major version bump remueve features |
| 8 | Integration | CLI exit 0 con resultado inválido (silent fail) — verificar output con tooling externo |
| 9 | Integration | Sintaxis CLI cambia entre majors — pinear versión y revisar TODAS las invocaciones |
| 10 | Upstream | Maintainer abandona + server-side block del producto = doble crisis simultánea |
| 11 | Config | Composite action YAML defaults ganan sobre bash `${VAR:-default}` |
| 12 | Heuristic | Selección automática naive ("primer match") rompe cuando hay variantes nuevas |

**Observación estructural:** 5 de 7 bugs fueron de **integración con upstream**. Mitigaciones aplicadas:
- Parametrización por env de repos + regexes (permite override sin código).
- Extracción directa de metadata donde el CLI no es estable (.rvp como ZIP).
- Wrappers de gh que discriminan causa de error (no más "not found" engañoso).

---

### <a id="bug-10"></a>Bug #10 — inotia00 archivado + YT 19.44.39 parcheado recibe HTTP 400 server-side

**Runs afectados:** v6 (#12, #13) en dispositivo físico · **Resuelto con migración a Morphe** (commit migración v7, ver abajo) · **Archivos tocados:** `project-a/scripts/apply-patches.sh`, `fetch-apks.sh`, `create-release.sh`, `.github/workflows/project-a-manual.yml`, `docs/*`

**Síntoma (en dispositivo Samsung A04e, abril 2026):**
```
App "YouTube" parcheado abre, UI carga (botones bottom visibles),
pero el contenido central muestra:
  "Se produjo un error"
  [REINTENTAR]
REINTENTAR no hace nada. No permite login.
YT Music parcheado (mismo build) funciona 100%.
```

**Causa raíz (descubierta 2026-04-21):** combinación de dos cosas:
1. **`inotia00/revanced-patches` fue archivado** (`archived: true`, last push 2026-03-10). Ya no recibe updates.
2. **YT 19.44.39 recibe HTTP 400 del server de Google.** Las versiones 19.x están bloqueadas server-side por YouTube en 2026. inotia00 v5.14.1 solo soporta hasta YT 20.05.46, todas rechazables.
3. **inotia00 NO expone públicamente `Spoof streaming data` / `Spoof client` como patch** para YT. Sin spoof, YouTube server rechaza el cliente parcheado.

YT Music funciona porque YTM usa una API diferente (YouTube TV API) que aún no tiene el mismo nivel de bloqueo server-side.

**Fix: migración completa a MorpheApp.** Los mismos desarrolladores ex-ReVanced crearon la org `MorpheApp` con:
- `MorpheApp/morphe-patches` v1.24.0 — nuevo set (YT 20.47.62 como top)
- `MorpheApp/morphe-cli` v1.7.0 — drop-in syntax compat con v5
- `MorpheApp/MicroG-RE` v6.1.3 — 8× más liviano que ReVanced/GmsCore
- **Incluye `Spoof video streams` patch** — EL que resuelve el HTTP 400

Cambios aplicados en commit de migración:
- `REVANCED_CLI_REPO=MorpheApp/morphe-cli`, tag `v1.7.0`
- `REVANCED_PATCHES_REPO=MorpheApp/morphe-patches` (formato `.mpp`)
- `REVANCED_GMSCORE_REPO=MorpheApp/MicroG-RE`
- Target YT=20.47.62, YTM=8.47.56
- Patches explícitos habilitados: `Custom branding`, `Change package name`
- Íconos oficiales Q4 2024 re-extraídos del YT 20.47.62 / YTM 8.47.56 con apktool, commiteados en `project-a/assets/morphe-icons/`

**Lección:** cuando un upstream abandona, verificar su estado (archived, last-push) antes de asumir que "solo cambió el naming". Y para apps como YouTube que bloquean cliente parcheado server-side, el patch crítico es "Spoof streaming data / video streams" — si el fork no lo tiene, migrar.

---

### <a id="bug-11"></a>Bug #11 — Composite action YAML defaults ganan sobre bash `${VAR:-default}`

**Run afectado:** #14 (primer intento migración Morphe, 2026-04-23) · **Resuelto en:** commit con sincronización de defaults action.yml + corrección doc MicroG-RE package · **Archivos tocados:** `.github/actions/revanced-build/action.yml`, `project-a/scripts/create-release.sh`, `docs/CONTINUIDAD.md`

**Síntoma (literal del log):**
```
env:
  REVANCED_CLI_REPO: ReVanced/revanced-cli           ← VIEJO
  REVANCED_CLI_TAG: v1.7.0                            ← NUEVO (inconsistente)
  REVANCED_PATCHES_REPO: inotia00/revanced-patches   ← VIEJO
...
[INFO]  patches-meta.json no existe — extrayendo metadata del .mpp directamente.
[ERR]   No encontré asset 'patches-.*\.mpp$' en inotia00/revanced-patches (gh devolvió vacío sin error).
##[error]Process completed with exit code 1.
```

**Causa raíz:** tras la migración a Morphe actualicé los defaults dentro de los scripts bash con patrón `"${VAR:-default_nuevo}"`, pero **el composite action `.github/actions/revanced-build/action.yml` seguía declarando inputs con `default: 'default_viejo'`**. El workflow caller (`project-a-manual.yml`) no pasa explícitamente esos inputs → action toma el default YAML → lo propaga como env al step → el `${VAR:-...}` del bash ya tiene valor (viejo), el fallback no aplica.

Resultado: el script consulta `inotia00/revanced-patches` (archivado, formato `.rvp`) buscando el asset `.mpp` de Morphe → no matchea → error engañoso ("asset no encontrado" cuando realmente estábamos apuntando al repo equivocado).

**Fix:** sincronizar los 4 defaults del composite action con los nuevos valores Morphe:
```yaml
revanced_cli_repo:     default: 'MorpheApp/morphe-cli'
revanced_patches_repo: default: 'MorpheApp/morphe-patches'
revanced_gmscore_repo: default: 'MorpheApp/MicroG-RE'
revanced_gmscore_regex: default: 'microg-[0-9.]+\.apk$'
```

**Lección (regla del repo):** cuando un composite action declara un `input.default` Y el bash script que lo consume usa `${VAR:-fallback}`, **el default del YAML SIEMPRE gana** (porque el YAML setea el env con un valor no-vacío antes de que el bash lo evalúe). Dos fuentes de verdad = desync garantizada.

Política adoptada: **defaults EN UN SOLO LUGAR (action.yml)**. El bash usa `${VAR:?required}` o verifica vacío explícitamente con `die` — nunca `${VAR:-...}` salvo para valores genuinamente opcionales (ej. `VERSION:-latest`).

---

### <a id="bug-12"></a>Bug #12 — Scraper APKMirror eligió variant arm-v7a por orden alfabético

**Run afectado:** v7 rebuild #2 (post-Bug #11) · **Resuelto en:** [`6f88d05`](https://github.com/alexyoj123-tech/yt-personal/commit/6f88d05) · **Archivo tocado:** `project-a/scripts/apkmirror_download.py`

**Síntoma (literal del log CI):**
```
[apkmirror] inspecting variant: youtube-music-8-47-56-android-apk-download
[apkmirror]   title: 'YouTube Music 8.47.56 (arm-v7a) (120-640dpi)'
[apkmirror]     → density range (multi-DPI bundle), skipping
[apkmirror] inspecting variant: youtube-music-8-47-56-2-android-apk-download
[apkmirror]   title: 'YouTube Music 8.47.56 (arm-v7a) (nodpi)'
[apkmirror] chose variant: 'YouTube Music 8.47.56 (arm-v7a) (nodpi)'    ← arm-v7a, NO arm64-v8a
[apkmirror] download failed: HTTP 403 Forbidden    ← APKMirror rechaza CDN para esta arch
```

**Causa raíz:** el scraper `apkmirror_download.py` recorría las variants disponibles **en orden alfabético** y elegía la primera con título `(nodpi)` que no fuera bundle. Para YT Music 8.47.56, APKMirror lista 4 variants:
1. `arm-v7a (120-640dpi)` ← bundle, skipped OK
2. `arm-v7a (nodpi)` ← elegido (PRIMERO con nodpi, pero arch equivocada)
3. `arm64-v8a (120-640dpi)` ← bundle
4. `arm64-v8a (nodpi)` ← QUE QUERÍAMOS

Para YT 19.x sólo había 1 variant universal nodpi → la heurística simple funcionaba. Para versiones 20.x+ con multi-arch, falla silenciosamente. El HTTP 403 del CDN de APKMirror para variant arm-v7a fue casualidad — el problema raíz era la selección de arch.

**Fix:** sistema de prioridad explícito en lugar de "primer match":
```python
priority = [
    ("arm64-v8a", "nodpi", "P1: arm64+nodpi ideal"),
    ("arm64-v8a", None, "P2: arm64 cualquier dpi"),
    (None, "nodpi", "P3: universal nodpi"),
    (None, None, "P4: cualquier non-bundle"),
]
# Recorre TODAS las variants, selecciona la que matchea la prioridad más alta
```

Además: rechazo explícito de variants `arm-v7a` (arch incompat con A04e arm64-v8a) y density-range bundles `(X-Ydpi)`.

**Lección:** "primer match" funciona accidentalmente cuando solo hay 1-2 opciones. Cuando el upstream añade variantes (multi-arch, multi-DPI), la heurística simple elige mal sin ruido. **Cuando hay enumeración de opciones, siempre orden por prioridad explícita** — no confiar en orden alfabético/temporal/lo-que-sea del proveedor.

---

## Incidencias cosméticas no-bloqueantes

### GmsCore dice "Limitado" en la UI pero todo funciona

**Síntoma:** al abrir la app "microG Services Core", en la pantalla
principal dice "Limitado" o "Limited" cerca del logo, aunque la
autocomprobación marca todo en verde y las apps parcheadas (YT, YTM)
funcionan con login normal.

**Causa:** el string "Limitado" viene de microG upstream — es la forma
en que marca que no es Google Play Services oficial. No indica
disfunción real.

**Evidencia de que es cosmético:** YouTube Music parcheado hace login
y streaming normal con este mismo GmsCore. Si la funcionalidad real
estuviera "limitada", YT Music no funcionaría.

**Acción:** ignorar el label. Si la autocomprobación está toda verde
y las apps del usuario funcionan, está correctamente configurado.

---

## <a id="nuevos-bugs"></a>Nuevos bugs

*(Sección vacía. Añadir aquí cuando encontremos nuevos bugs en runs futuros, mismo formato que los anteriores.)*

### Template

```markdown
### Bug #N — título corto

**Run afectado:** #NN · **Resuelto en:** `SHAcorto` · **Archivo tocado:** `path/archivo`

**Síntoma (literal):**
​```
...
​```

**Causa raíz:** ...

**Fix:** ...

**Lección:** ...
```
